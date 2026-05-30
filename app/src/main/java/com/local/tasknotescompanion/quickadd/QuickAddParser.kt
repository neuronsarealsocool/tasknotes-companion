package com.local.tasknotescompanion.quickadd

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

enum class QuickAddDateTarget { SCHEDULED, DUE }

data class QuickAddDraft(
    val rawText: String,
    val title: String,
    val dateTarget: QuickAddDateTarget = QuickAddDateTarget.SCHEDULED,
    val scheduled: LocalDate? = null,
    val scheduledTime: LocalTime? = null,
    val due: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val tags: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val contexts: List<String> = emptyList(),
    val priority: String? = null,
    val timeEstimateMinutes: Int? = null,
    val tokens: List<QuickAddToken> = emptyList(),
)

data class QuickAddToken(
    val kind: QuickAddTokenKind,
    val text: String,
)

enum class QuickAddTokenKind { DATE, TIME, TAG, PROJECT, CONTEXT, PRIORITY, TASK_FILE }

class QuickAddParser(private val todayProvider: () -> LocalDate = { LocalDate.now() }) {
    fun parse(text: String, target: QuickAddDateTarget = QuickAddDateTarget.SCHEDULED): QuickAddDraft {
        val removals = mutableListOf<IntRange>()
        val tokens = mutableListOf<QuickAddToken>()
        val tags = MetadataRegex.findAll(text).filter { it.value.startsWith("#") }.map {
            removals += it.range
            tokens += QuickAddToken(QuickAddTokenKind.TAG, it.value)
            it.value.drop(1)
        }.toList()
        val projects = MetadataRegex.findAll(text).filter { it.value.startsWith("+") }.map {
            removals += it.range
            tokens += QuickAddToken(QuickAddTokenKind.PROJECT, it.value)
            it.value.drop(1)
        }.toList()
        val contexts = MetadataRegex.findAll(text).filter { it.value.startsWith("@") && parseTimeToken(it.value) == null }.map {
            removals += it.range
            tokens += QuickAddToken(QuickAddTokenKind.CONTEXT, it.value)
            it.value.drop(1)
        }.toList()

        var priority: String? = null
        PriorityRegex.find(text)?.let {
            priority = it.groupValues[1].lowercase()
            removals += it.range
            tokens += QuickAddToken(QuickAddTokenKind.PRIORITY, priority!!)
        }

        val range = RangeRegex.find(text)
        val parsedRange = range?.let {
            val start = parseDatePhrase(it.groupValues[1], defaultMonth = null)
            val end = parseDatePhrase(it.groupValues[2], defaultMonth = start?.month)
            if (start != null && end != null) {
                removals += it.range
                tokens += QuickAddToken(QuickAddTokenKind.DATE, it.value)
                start to normalizeRangeEnd(start, end)
            } else {
                null
            }
        }

        val timeMatch = TimeRegex.findAll(text)
            .firstOrNull { match -> removals.none { it.intersects(match.range) } }
        val time = timeMatch?.let {
            parseTimeToken(it.value)?.also { parsed ->
                removals += it.range
                tokens += QuickAddToken(QuickAddTokenKind.TIME, it.value)
            }
        }

        var singleDate: LocalDate? = null
        if (parsedRange == null) {
            DatePatterns.firstNotNullOfOrNull { regex ->
                regex.findAll(text).firstOrNull { match -> removals.none { it.intersects(match.range) } }?.let { match ->
                    parseDatePhrase(match.value, defaultMonth = null)?.also {
                        removals += match.range
                        tokens += QuickAddToken(QuickAddTokenKind.DATE, match.value)
                    }
                }
            }?.let { singleDate = it }
        }
        if (parsedRange == null && singleDate == null && time != null) {
            singleDate = todayProvider()
        }

        tokens += QuickAddToken(QuickAddTokenKind.TASK_FILE, "Task File")

        val scheduledDate = parsedRange?.first ?: if (target == QuickAddDateTarget.SCHEDULED) singleDate else null
        val dueDate = if (parsedRange != null) null else if (target == QuickAddDateTarget.DUE) singleDate else null
        val timeEstimateMinutes = parsedRange?.let { (start, end) ->
            val minutes = Duration.between(
                start.atTime(time ?: LocalTime.MIDNIGHT),
                end.plusDays(1).atTime(time ?: LocalTime.MIDNIGHT),
            ).toMinutes()
            minutes.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        }
        return QuickAddDraft(
            rawText = text,
            title = text.trim(),
            dateTarget = target,
            scheduled = scheduledDate,
            scheduledTime = if (scheduledDate != null) time else null,
            due = dueDate,
            dueTime = if (dueDate != null) time else null,
            tags = tags,
            projects = projects,
            contexts = contexts,
            priority = priority,
            timeEstimateMinutes = timeEstimateMinutes,
            tokens = tokens,
        )
    }

    private fun parseDatePhrase(value: String, defaultMonth: Month?): LocalDate? {
        val text = value.trim().lowercase()
        val today = todayProvider()
        if (text == "today") return today
        if (text == "tomorrow") return today.plusDays(1)
        DayOfWeek.entries.firstOrNull {
            text == it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() ||
                text == it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
        }?.let { return nextDayOfWeek(today, it) }
        IsoDateRegex.matchEntire(text)?.let { return runCatching { LocalDate.parse(text) }.getOrNull() }
        NumericDateRegex.matchEntire(text)?.let {
            val day = it.groupValues[1].toInt()
            val month = it.groupValues[2].toInt()
            return nextDate(today, month, day)
        }
        DayOnlyRegex.matchEntire(text)?.let {
            val month = defaultMonth ?: today.month
            val day = it.groupValues[1].toInt()
            return nextDate(today, month.value, day)
        }
        DayMonthRegex.matchEntire(text)?.let {
            val day = it.groupValues[1].toInt()
            val month = parseMonth(it.groupValues[2]) ?: return null
            return nextDate(today, month.value, day)
        }
        MonthDayRegex.matchEntire(text)?.let {
            val month = parseMonth(it.groupValues[1]) ?: return null
            val day = it.groupValues[2].toInt()
            return nextDate(today, month.value, day)
        }
        return null
    }

    private fun normalizeRangeEnd(start: LocalDate, end: LocalDate): LocalDate {
        return if (end.isBefore(start)) end.plusMonths(1) else end
    }

    private fun nextDate(today: LocalDate, month: Int, day: Int): LocalDate? {
        val currentYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (currentYear.isBefore(today)) currentYear.plusYears(1) else currentYear
    }

    private fun nextDayOfWeek(today: LocalDate, day: DayOfWeek): LocalDate {
        var candidate = today
        while (candidate.dayOfWeek != day) candidate = candidate.plusDays(1)
        return candidate
    }

    private fun parseMonth(value: String): Month? {
        val normalized = value.lowercase().take(3)
        return Month.entries.firstOrNull {
            it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase().take(3) == normalized
        }
    }

    private fun parseTimeToken(value: String): LocalTime? {
        val text = value.trim()
            .replace(Regex("""(?i)^@\s*"""), "")
            .replace(Regex("""(?i)^at\s+"""), "")
            .lowercase()
        TimeWithAmPmRegex.matchEntire(text)?.let {
            var hour = it.groupValues[1].toInt()
            val minute = it.groupValues[2].ifBlank { "0" }.toInt()
            val meridiem = it.groupValues[3]
            if (meridiem == "pm" && hour != 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        Time24Regex.matchEntire(text)?.let {
            return runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
        }
        return null
    }

    private fun IntRange.intersects(other: IntRange): Boolean = first <= other.last && other.first <= last

    private companion object {
        val MetadataRegex = Regex("""(?<!\S)[#@+][A-Za-z][A-Za-z0-9_-]*""")
        val PriorityRegex = Regex("""(?i)\b(low|medium|high)\s+priority\b""")
        val RangeRegex = Regex("""(?i)\b(?:from\s+)?([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?|\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]+|\d{1,2}/\d{1,2}|\d{4}-\d{2}-\d{2}|\d{1,2}(?:st|nd|rd|th)?|today|tomorrow)\s*(?:to|-)\s*([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?|\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]+|\d{1,2}/\d{1,2}|\d{4}-\d{2}-\d{2}|\d{1,2}(?:st|nd|rd|th)?|today|tomorrow)\b""")
        val DatePatterns = listOf(
            Regex("""(?i)\b\d{4}-\d{2}-\d{2}\b"""),
            Regex("""(?i)\b\d{1,2}/\d{1,2}\b"""),
            Regex("""(?i)\b\d{1,2}(?:st|nd|rd|th)?\s+(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)\b"""),
            Regex("""(?i)\b(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)\s+\d{1,2}(?:st|nd|rd|th)?\b"""),
            Regex("""(?i)\b(today|tomorrow|monday|mon|tuesday|tue|wednesday|wed|thursday|thu|friday|fri|saturday|sat|sunday|sun)\b"""),
        )
        val TimeRegex = Regex("""(?i)(?<!\S)(?:@\s*|at\s+)?(?:\d{1,2}(?:(?::|\s+)\d{2})?\s*(?:am|pm)|\d{1,2}:\d{2})(?!\S)""")
        val TimeWithAmPmRegex = Regex("""(\d{1,2})(?:(?::|\s+)(\d{2}))?\s*(am|pm)""")
        val Time24Regex = Regex("""(\d{1,2}):(\d{2})""")
        val IsoDateRegex = Regex("""\d{4}-\d{2}-\d{2}""")
        val NumericDateRegex = Regex("""(\d{1,2})/(\d{1,2})""")
        val DayOnlyRegex = Regex("""(\d{1,2})(?:st|nd|rd|th)?""")
        val DayMonthRegex = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]+)""")
        val MonthDayRegex = Regex("""([a-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?""")
    }
}
