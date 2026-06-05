package com.local.tasknotescompanion.tasknotes

import com.local.tasknotescompanion.domain.DetectionMode
import com.local.tasknotescompanion.domain.RecurrenceSpec
import com.local.tasknotescompanion.domain.RecurrenceUnit
import com.local.tasknotescompanion.domain.ReminderAlert
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.domain.TaskRecord
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class TaskNoteParser {
    private val load = Load(LoadSettings.builder().build())
    private val dump = Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build())

    fun parse(file: File, root: File, config: TaskMappingConfig): TaskRecord? {
        val text = file.readText()
        val (rawYaml, body) = splitFrontmatter(text)
        val raw = parseYaml(rawYaml)
        if (!isTask(file, root, raw, config)) return null

        val title = raw[config.titleField]?.toString()?.ifBlank { null }
            ?: file.nameWithoutExtension
        val status = raw[config.statusField]?.toString() ?: config.openStatus
        val dueTemporal = parseTemporal(raw[config.dueField])
        val scheduledTemporal = parseTemporal(raw[config.scheduledField])
        val completed = parseDateTime(raw[config.completedField])
        val reminders = parseReminders(raw[config.remindersField], dueTemporal.date, scheduledTemporal.date)

        return TaskRecord(
            id = stableId(root, file),
            path = file.absolutePath,
            title = title,
            body = body.trimStart('\n'),
            status = status,
            priority = raw[config.priorityField]?.toString(),
            due = dueTemporal.date,
            dueTime = dueTemporal.time,
            scheduled = scheduledTemporal.date,
            scheduledTime = scheduledTemporal.time,
            tags = readStringList(raw[config.tagsField]),
            projects = readStringList(raw[config.projectsField]),
            contexts = readStringList(raw[config.contextsField]),
            reminders = reminders,
            recurrence = parseRecurrence(raw[config.recurrenceField]),
            completedDate = completed,
            modifiedAt = file.lastModified(),
            frontmatter = TaskFrontmatter(raw),
        )
    }

    fun render(task: TaskRecord, config: TaskMappingConfig): String {
        val updated = task.frontmatter.raw.toMutableMap()
        updated[config.titleField] = task.title
        updated[config.statusField] = task.status
        putOrRemove(updated, config.priorityField, task.priority)
        putOrRemove(updated, config.dueField, formatTemporal(task.due, task.dueTime))
        putOrRemove(updated, config.scheduledField, formatTemporal(task.scheduled, task.scheduledTime))
        putOrRemove(updated, config.tagsField, task.tags.takeIf { it.isNotEmpty() })
        putOrRemove(updated, config.projectsField, task.projects.takeIf { it.isNotEmpty() })
        putOrRemove(updated, config.contextsField, task.contexts.takeIf { it.isNotEmpty() })
        putOrRemove(updated, config.recurrenceField, task.recurrence?.let { it.rrule ?: "every ${it.interval} ${it.unit.name.lowercase()}" })
        putOrRemove(updated, config.remindersField, task.reminders.map { it.toYamlMap() }.takeIf { it.isNotEmpty() })
        putOrRemove(updated, config.completedField, task.completedDate?.toString())
        updated.putIfAbsent("timeEstimate", 0)
        updated.putIfAbsent("taskSourceType", "taskNotes")
        updated.putIfAbsent(config.priorityField, "none")
        val now = OffsetDateTime.now()
        updated[config.modifiedField] = now.toString()
        updated["updated"] = now.toLocalDateTime().withSecond(0).withNano(0).toString()

        return "---\n${dump.dumpToString(updated).trim()}\n---\n\n${task.body.trimStart()}"
    }

    fun createNewFile(root: File, config: TaskMappingConfig, title: String): File {
        val folder = if (config.taskFolder.isBlank()) root else File(root, config.taskFolder)
        folder.mkdirs()
        return uniqueFile(folder, fileNameForTitle(title))
    }

    fun fileNameForTitle(title: String): String {
        val safeName = title.lowercase()
            .replace(Regex("[^a-z0-9 _-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .ifBlank { "task-${UUID.randomUUID()}" }
        return "$safeName.md"
    }

    fun uniqueSiblingFile(currentFile: File, title: String): File {
        val folder = currentFile.parentFile ?: error("Task file has no parent folder")
        val desired = File(folder, fileNameForTitle(title))
        if (desired.absolutePath.equals(currentFile.absolutePath, ignoreCase = true)) return currentFile
        return uniqueFile(folder, desired.name)
    }

    fun complete(task: TaskRecord, config: TaskMappingConfig, completed: Boolean): TaskRecord {
        return task.copy(
            status = if (completed) config.doneStatus else config.openStatus,
            completedDate = if (completed) LocalDateTime.now() else null,
        )
    }

    fun nextOccurrence(task: TaskRecord): TaskRecord? {
        val recurrence = task.recurrence ?: return null
        if (recurrence.rrule != null) return null
        val nextDue = task.due?.plus(recurrence)
        val nextScheduled = task.scheduled?.plus(recurrence)
        return task.copy(
            id = UUID.randomUUID().toString(),
            path = task.path,
            status = "todo",
            due = nextDue,
            dueTime = task.dueTime,
            scheduled = nextScheduled,
            scheduledTime = task.scheduledTime,
            completedDate = null,
            reminders = task.reminders.map { reminder ->
                when (reminder) {
                    is ReminderSpec.Absolute -> reminder.copy(absoluteTime = reminder.absoluteTime.plus(recurrence))
                    is ReminderSpec.Relative -> reminder
                }
            },
        )
    }

    private fun splitFrontmatter(text: String): Pair<String, String> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return "" to text
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end == -1) return "" to text
        return normalized.substring(3, end).trim() to normalized.substring(end + 4)
    }

    private fun parseYaml(yaml: String): Map<String, Any?> {
        if (yaml.isBlank()) return emptyMap()
        val loaded = load.loadFromString(yaml) as? Map<*, *> ?: return emptyMap()
        return sanitizeYamlMap(loaded)
    }

    private fun sanitizeYamlMap(map: Map<*, *>, seen: MutableSet<Int> = mutableSetOf()): Map<String, Any?> {
        val identity = System.identityHashCode(map)
        if (!seen.add(identity)) return emptyMap()
        return map.entries.mapNotNull { entry ->
            val value = sanitizeYamlValue(entry.value, seen) ?: return@mapNotNull null
            entry.key.toString() to value
        }.toMap()
            .also { seen.remove(identity) }
    }

    private fun sanitizeYamlValue(value: Any?, seen: MutableSet<Int>): Any? {
        return when (value) {
            null, is String, is Number, is Boolean -> value
            is Map<*, *> -> sanitizeYamlMap(value, seen)
            is Iterable<*> -> value.map { sanitizeYamlValue(it, seen) }
            else -> value.toString()
        }
    }

    private fun isTask(file: File, root: File, raw: Map<String, Any?>, config: TaskMappingConfig): Boolean {
        return when (config.detectionMode) {
            DetectionMode.TAG -> readStringList(raw[config.tagsField]).any {
                it.trimStart('#').equals(config.detectionTag.trimStart('#'), ignoreCase = true)
            }
            DetectionMode.PROPERTY -> raw[config.detectionProperty]?.toString()
                ?.equals(config.detectionPropertyValue, ignoreCase = true) == true
            DetectionMode.FOLDER -> config.taskFolder.isNotBlank() &&
                file.relativeTo(root).path.replace('\\', '/').startsWith(config.taskFolder.trim('/'))
        }
    }

    private fun readStringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Iterable<*> -> value.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
        is String -> value.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
        else -> listOf(value.toString())
    }

    private fun parseDate(value: Any?): LocalDate? = when (value) {
        null -> null
        is LocalDate -> value
        else -> runCatching { LocalDate.parse(value.toString().take(10)) }.getOrNull()
    }

    private data class ParsedTemporal(val date: LocalDate?, val time: LocalTime?)

    private fun parseTemporal(value: Any?): ParsedTemporal {
        if (value == null) return ParsedTemporal(null, null)
        if (value is LocalDate) return ParsedTemporal(value, null)
        val text = value.toString().trim()
        if (text.isBlank()) return ParsedTemporal(null, null)
        val dateOnly = runCatching { LocalDate.parse(text) }.getOrNull()
        if (dateOnly != null) return ParsedTemporal(dateOnly, null)
        val dateTime = parseDateTimeLoose(text)
        return ParsedTemporal(dateTime?.toLocalDate(), dateTime?.toLocalTime()?.withSecond(0)?.withNano(0))
    }

    private fun formatTemporal(date: LocalDate?, time: LocalTime?): String? {
        if (date == null) return null
        if (time == null) return date.toString()
        return date.atTime(time).withSecond(0).withNano(0).toString()
    }

    private fun parseDateTime(value: Any?): LocalDateTime? {
        if (value == null) return null
        val text = value.toString()
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay() }.getOrNull()
    }

    private fun parseReminders(value: Any?, due: LocalDate?, scheduled: LocalDate?): List<ReminderSpec> {
        val defaultDate = due ?: scheduled
        if (value is Iterable<*>) {
            return value.mapNotNull { parseReminderEntry(it, defaultDate) }
        }
        return readStringList(value).mapIndexedNotNull { index, text ->
            val dateTime = parseDateTimeLoose(text)
                ?: defaultDate?.let { date ->
                    runCatching { LocalTime.parse(text, DateTimeFormatter.ISO_LOCAL_TIME) }.getOrNull()?.let(date::atTime)
                }
            dateTime?.let {
                ReminderSpec.Absolute(
                    id = "legacy_${index}_${text.hashCode()}",
                    absoluteTime = it,
                    description = "Reminder",
                    raw = emptyMap(),
                )
            }
        }
    }

    private fun parseReminderEntry(value: Any?, defaultDate: LocalDate?): ReminderSpec? {
        val map = value as? Map<*, *> ?: return value?.toString()?.let { text ->
            parseDateTimeLoose(text)?.let {
                ReminderSpec.Absolute("legacy_${text.hashCode()}", it, "Reminder")
            }
        }
        val raw = map.entries.associate { it.key.toString() to it.value }
        val id = raw["id"]?.toString()?.ifBlank { null } ?: "rem_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val description = raw["description"]?.toString()
        val repeatEvery = raw["repeatEvery"]?.toString()?.let { parseDuration(it) }
        val repeatUntilCompleted = raw["repeatUntil"]?.toString()?.equals("completed", ignoreCase = true) == true
        val alert = parseAlert(raw["alert"])
        return when (raw["type"]?.toString()?.lowercase()) {
            "absolute" -> {
                val time = raw["absoluteTime"] ?: raw["dateTime"] ?: return null
                parseDateTimeLoose(time.toString())?.let {
                    ReminderSpec.Absolute(id, it, description, raw, repeatEvery, repeatUntilCompleted, alert)
                }
            }
            "relative" -> {
                val relatedTo = when (raw["relatedTo"]?.toString()?.lowercase()) {
                    "scheduled" -> ReminderAnchor.SCHEDULED
                    "due" -> ReminderAnchor.DUE
                    else -> return null
                }
                val offset = raw["offset"]?.toString()?.let { parseDuration(it) } ?: return null
                ReminderSpec.Relative(id, relatedTo, offset, description, raw, repeatEvery, repeatUntilCompleted, alert)
            }
            else -> {
                val dateTime = raw["absoluteTime"]?.toString()?.let(::parseDateTimeLoose)
                    ?: raw["dateTime"]?.toString()?.let(::parseDateTimeLoose)
                    ?: defaultDate?.atTime(LocalTime.of(9, 0))
                dateTime?.let { ReminderSpec.Absolute(id, it, description, raw, repeatEvery, repeatUntilCompleted, alert) }
            }
        }
    }

    private fun parseAlert(value: Any?): ReminderAlert? {
        val raw = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return null
        return ReminderAlert(
            style = raw["style"]?.toString() ?: "fullscreen",
            note = raw["note"]?.toString(),
            image = raw["image"]?.toString(),
            video = raw["video"]?.toString(),
            audio = raw["audio"]?.toString(),
            audioLoop = raw["audioLoop"]?.toString()?.toBooleanStrictOrNull() ?: true,
            audioUntil = raw["audioUntil"]?.toString() ?: "dismiss",
            allowOverlay = raw["allowOverlay"]?.toString()?.toBooleanStrictOrNull() ?: false,
            raw = raw,
        )
    }

    private fun ReminderSpec.toYamlMap(): Map<String, Any?> {
        val map = raw.toMutableMap()
        map["id"] = id
        description?.let { map["description"] = it } ?: map.remove("description")
        repeatEvery?.let { map["repeatEvery"] = formatDuration(it) } ?: map.remove("repeatEvery")
        if (repeatUntilCompleted) {
            map["repeatUntil"] = "completed"
        } else {
            map.remove("repeatUntil")
        }
        alert?.let { map["alert"] = it.toYamlMap() } ?: map.remove("alert")
        when (this) {
            is ReminderSpec.Absolute -> {
                map["type"] = "absolute"
                map["dateTime"] = absoluteTime.toString()
                map.remove("absoluteTime")
                map.remove("relatedTo")
                map.remove("offset")
            }
            is ReminderSpec.Relative -> {
                map["type"] = "relative"
                map["relatedTo"] = relatedTo.name.lowercase()
                map["offset"] = formatDuration(offset)
                map.remove("absoluteTime")
                map.remove("dateTime")
            }
        }
        return map
    }

    private fun ReminderAlert.toYamlMap(): Map<String, Any?> {
        val map = raw.toMutableMap()
        map["style"] = style
        note?.let { map["note"] = it } ?: map.remove("note")
        image?.let { map["image"] = it } ?: map.remove("image")
        video?.let { map["video"] = it } ?: map.remove("video")
        audio?.let { map["audio"] = it } ?: map.remove("audio")
        map["audioLoop"] = audioLoop
        map["audioUntil"] = audioUntil
        map["allowOverlay"] = allowOverlay
        return map
    }

    private fun parseDateTimeLoose(value: String): LocalDateTime? {
        val text = value.trim()
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atTime(LocalTime.of(9, 0)) }.getOrNull()
    }

    private fun parseDuration(value: String): Duration? {
        val normalized = value.trim()
        return runCatching { Duration.parse(normalized) }.getOrNull()
            ?: runCatching {
                if (normalized.startsWith("+")) Duration.parse(normalized.drop(1)) else null
            }.getOrNull()
    }

    private fun formatDuration(duration: Duration): String {
        val sign = if (duration.isNegative) "-" else ""
        val absolute = duration.abs()
        if (absolute.isZero) return "PT0M"
        val wholeDays = absolute.toDays()
        return if (wholeDays > 0 && absolute == Duration.ofDays(wholeDays)) {
            "${sign}P${wholeDays}D"
        } else {
            "$sign$absolute"
        }
    }

    private fun parseRecurrence(value: Any?): RecurrenceSpec? {
        val original = value?.toString()?.trim() ?: return null
        if (original.isBlank()) return null
        if (original.contains("FREQ=", ignoreCase = true) || original.startsWith("DTSTART:", ignoreCase = true)) {
            val interval = Regex("""(?i)(?:^|;)INTERVAL=(\d+)""").find(original)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val unit = when {
                original.contains("FREQ=WEEKLY", ignoreCase = true) -> RecurrenceUnit.WEEK
                original.contains("FREQ=MONTHLY", ignoreCase = true) -> RecurrenceUnit.MONTH
                original.contains("FREQ=YEARLY", ignoreCase = true) -> RecurrenceUnit.YEAR
                else -> RecurrenceUnit.DAY
            }
            return RecurrenceSpec(interval, unit, original)
        }
        val text = original.lowercase()
        val interval = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 1
        val unit = when {
            "week" in text -> RecurrenceUnit.WEEK
            "month" in text -> RecurrenceUnit.MONTH
            "year" in text -> RecurrenceUnit.YEAR
            else -> RecurrenceUnit.DAY
        }
        return RecurrenceSpec(interval, unit)
    }

    private fun LocalDate.plus(recurrence: RecurrenceSpec): LocalDate = when (recurrence.unit) {
        RecurrenceUnit.DAY -> plusDays(recurrence.interval.toLong())
        RecurrenceUnit.WEEK -> plusWeeks(recurrence.interval.toLong())
        RecurrenceUnit.MONTH -> plusMonths(recurrence.interval.toLong())
        RecurrenceUnit.YEAR -> plusYears(recurrence.interval.toLong())
    }

    private fun LocalDateTime.plus(recurrence: RecurrenceSpec): LocalDateTime = when (recurrence.unit) {
        RecurrenceUnit.DAY -> plusDays(recurrence.interval.toLong())
        RecurrenceUnit.WEEK -> plusWeeks(recurrence.interval.toLong())
        RecurrenceUnit.MONTH -> plusMonths(recurrence.interval.toLong())
        RecurrenceUnit.YEAR -> plusYears(recurrence.interval.toLong())
    }

    private fun stableId(root: File, file: File): String = file.relativeTo(root).path.replace('\\', '/')

    private fun putOrRemove(map: MutableMap<String, Any?>, key: String, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(folder, "${name.removeSuffix(".md")}-$suffix.md")
            suffix++
        }
        return candidate
    }
}
