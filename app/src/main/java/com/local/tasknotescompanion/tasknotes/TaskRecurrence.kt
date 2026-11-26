package com.local.tasknotescompanion.tasknotes

import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.domain.TaskRecord
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Resolves virtual TaskNotes instances without changing the Markdown series. */
class TaskRecurrence {
    fun nextDate(task: TaskRecord, from: LocalDate): LocalDate? = runCatching {
        val text = task.recurrence?.rrule ?: return null
        val startText = Regex("(?i)DTSTART(?:;[^:]+)?:([^;\\s]+)").find(text)?.groupValues?.get(1)
        // TaskNotes encodes local calendar components in its UTC recurrence anchor.
        // Keep those components rather than shifting all-day/clock dates across zones.
        val start = startText?.let { DateTime.parse(it) } ?: (task.scheduled ?: task.due)?.let {
            DateTime.parse(it.format(DateTimeFormatter.BASIC_ISO_DATE))
        } ?: return null
        val ruleText = text.replace(Regex("(?i)DTSTART(?:;[^:]+)?:[^;\\s]+;?"), "")
            .replace(Regex("(?i)RRULE:"), "").trim().trim(';')
        val iterator = RecurrenceRule(ruleText).iterator(start)
        val target = if (start.isAllDay) {
            DateTime.parse(from.format(DateTimeFormatter.BASIC_ISO_DATE))
        } else {
            DateTime.parse(from.format(DateTimeFormatter.BASIC_ISO_DATE) + "T000000" + if (start.isFloating) "" else "Z")
        }
        iterator.fastForward(target)
        val excluded = instanceDates(task, "complete_instances") + instanceDates(task, "skipped_instances")
        // Bound enumeration even for a rule whose every future date is excluded.
        repeat(10_000) {
            if (!iterator.hasNext()) return null
            val occurrence = iterator.nextDateTime()
            val date = LocalDate.of(occurrence.year, occurrence.month + 1, occurrence.dayOfMonth)
            if (date >= from && date !in excluded) return date
        }
        null
    }.getOrNull()

    fun forDisplay(task: TaskRecord, today: LocalDate): TaskRecord {
        if (task.isDone || task.recurrence?.rrule == null) return task
        val explicit = task.scheduled
        // Completion-anchored series keep their concrete commitment until completed.
        val completionAnchor = task.frontmatter.raw["recurrence_anchor"] == "completion"
        val next = if (completionAnchor && explicit != null && explicit !in excludedDates(task)) {
            explicit
        } else {
            val pattern = nextDate(task, today)
            val planned = explicit?.takeIf { it >= today && it !in excludedDates(task) }
            listOfNotNull(pattern, planned).minOrNull()
        } ?: return task
        return task.copy(scheduled = next, occurrenceDate = next)
    }

    fun complete(task: TaskRecord, config: TaskMappingConfig, now: LocalDateTime): TaskRecord? {
        if (task.recurrence?.rrule == null) return null
        val date = task.occurrenceDate ?: task.scheduled ?: now.toLocalDate()
        val history = instanceDates(task, "complete_instances") + date
        val raw = task.frontmatter.raw.toMutableMap().apply {
            put("complete_instances", history.sorted().map { it.toString() })
            put("skipped_instances", (instanceDates(task, "skipped_instances") - date).sorted().map { it.toString() })
        }
        val completed = task.copy(frontmatter = TaskFrontmatter(raw), occurrenceDate = null)
        val completionAnchor = raw["recurrence_anchor"] == "completion"
        val from = (if (completionAnchor) maxOf(date, now.toLocalDate()) else date).plusDays(1)
        val next = nextDate(completed, from)
        return completed.copy(
            scheduled = next ?: task.scheduled,
            status = if (next == null) config.doneStatus else config.openStatus,
            completedDate = if (next == null) now else null,
        )
    }

    private fun excludedDates(task: TaskRecord) =
        instanceDates(task, "complete_instances") + instanceDates(task, "skipped_instances")

    private fun instanceDates(task: TaskRecord, field: String): Set<LocalDate> {
        val value = task.frontmatter.raw[field]
        val values = if (value is Iterable<*>) value else listOfNotNull(value)
        return values.mapNotNull { runCatching { LocalDate.parse(it.toString().take(10)) }.getOrNull() }.toSet()
    }
}
