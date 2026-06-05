package com.local.tasknotescompanion.data

import com.local.tasknotescompanion.domain.RecurrenceSpec
import com.local.tasknotescompanion.domain.RecurrenceUnit
import com.local.tasknotescompanion.domain.ReminderAlert
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.domain.TaskRecord
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime

private val dump = Dump(DumpSettings.builder().setDefaultFlowStyle(org.snakeyaml.engine.v2.common.FlowStyle.BLOCK).build())
private val load = Load(LoadSettings.builder().build())

fun TaskRecord.toEntity(): TaskEntity {
    return TaskEntity(
        id = id,
        path = path,
        title = title,
        body = body,
        status = status,
        priority = priority,
        due = due?.toString(),
        dueTime = dueTime?.toString(),
        scheduled = scheduled?.toString(),
        scheduledTime = scheduledTime?.toString(),
        tags = tags.joinToString("\n"),
        projects = projects.joinToString("\n"),
        contexts = contexts.joinToString("\n"),
        reminders = dump.dumpToString(reminders.map { sanitizeYamlMap(it.toYamlMap()) }),
        recurrence = recurrence?.let { it.rrule ?: "${it.interval} ${it.unit.name.lowercase()}" },
        completedDate = completedDate?.toString(),
        modifiedAt = modifiedAt,
        rawFrontmatter = dump.dumpToString(sanitizeYamlMap(frontmatter.raw)),
    )
}

fun TaskEntity.toRecord(): TaskRecord {
    val raw = (load.loadFromString(rawFrontmatter) as? Map<*, *>)
        ?.entries
        ?.associate { it.key.toString() to it.value }
        .orEmpty()
    return TaskRecord(
        id = id,
        path = path,
        title = title,
        body = body,
        status = status,
        priority = priority,
        due = due?.let(LocalDate::parse),
        dueTime = dueTime?.let(LocalTime::parse),
        scheduled = scheduled?.let(LocalDate::parse),
        scheduledTime = scheduledTime?.let(LocalTime::parse),
        tags = tags.lines().filter { it.isNotBlank() },
        projects = projects.lines().filter { it.isNotBlank() },
        contexts = contexts.lines().filter { it.isNotBlank() },
        reminders = parseStoredReminders(reminders),
        recurrence = recurrence?.let { parseStoredRecurrence(it) },
        completedDate = completedDate?.let(LocalDateTime::parse),
        modifiedAt = modifiedAt,
        frontmatter = TaskFrontmatter(raw),
    )
}

private fun parseStoredRecurrence(value: String): RecurrenceSpec? {
    val text = value.trim()
    if (text.isBlank()) return null
    if (text.contains("FREQ=", ignoreCase = true) || text.startsWith("DTSTART:", ignoreCase = true)) {
        val interval = Regex("""(?i)(?:^|;)INTERVAL=(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val unit = when {
            text.contains("FREQ=WEEKLY", ignoreCase = true) -> RecurrenceUnit.WEEK
            text.contains("FREQ=MONTHLY", ignoreCase = true) -> RecurrenceUnit.MONTH
            text.contains("FREQ=YEARLY", ignoreCase = true) -> RecurrenceUnit.YEAR
            else -> RecurrenceUnit.DAY
        }
        return RecurrenceSpec(interval, unit, text)
    }
    val parts = text.split(" ")
    return RecurrenceSpec(
        parts.firstOrNull()?.toIntOrNull() ?: 1,
        RecurrenceUnit.valueOf(parts.getOrElse(1) { "day" }.uppercase()),
    )
}

private fun ReminderSpec.toYamlMap(): Map<String, Any?> {
    val base = raw.toMutableMap()
    base["id"] = id
    description?.let { base["description"] = it } ?: base.remove("description")
    repeatEvery?.let { base["repeatEvery"] = formatDuration(it) } ?: base.remove("repeatEvery")
    if (repeatUntilCompleted) {
        base["repeatUntil"] = "completed"
    } else {
        base.remove("repeatUntil")
    }
    alert?.let { base["alert"] = it.toYamlMap() } ?: base.remove("alert")
    when (this) {
        is ReminderSpec.Absolute -> {
            base["type"] = "absolute"
            base["dateTime"] = absoluteTime.toString()
            base.remove("absoluteTime")
            base.remove("relatedTo")
            base.remove("offset")
        }
        is ReminderSpec.Relative -> {
            base["type"] = "relative"
            base["relatedTo"] = relatedTo.name.lowercase()
            base["offset"] = if (offset.isNegative) "-${offset.negated()}" else offset.toString()
            base.remove("absoluteTime")
            base.remove("dateTime")
        }
    }
    return base
}

private fun ReminderAlert.toYamlMap(): Map<String, Any?> {
    val base = raw.toMutableMap()
    base["style"] = style
    note?.let { base["note"] = it } ?: base.remove("note")
    image?.let { base["image"] = it } ?: base.remove("image")
    video?.let { base["video"] = it } ?: base.remove("video")
    audio?.let { base["audio"] = it } ?: base.remove("audio")
    base["audioLoop"] = audioLoop
    base["audioUntil"] = audioUntil
    base["allowOverlay"] = allowOverlay
    return base
}

private fun parseStoredReminders(value: String): List<ReminderSpec> {
    if (value.isBlank()) return emptyList()
    val yaml = load.loadFromString(value)
    if (yaml is Iterable<*>) {
        return yaml.mapNotNull { parseReminderMap(it as? Map<*, *>) }
    }
    return value.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val parts = line.split("|", limit = 2)
        parts.firstOrNull()?.let { date ->
            runCatching {
                ReminderSpec.Absolute(
                    id = "legacy_${date.hashCode()}",
                    absoluteTime = LocalDateTime.parse(date),
                    description = parts.getOrNull(1),
                )
            }.getOrNull()
        }
    }
}

private fun parseReminderMap(map: Map<*, *>?): ReminderSpec? {
    if (map == null) return null
    val raw = map.entries.associate { it.key.toString() to it.value }
    val id = raw["id"]?.toString() ?: return null
    val description = raw["description"]?.toString()
    val repeatEvery = raw["repeatEvery"]?.toString()?.let { parseDuration(it) }
    val repeatUntilCompleted = raw["repeatUntil"]?.toString()?.equals("completed", ignoreCase = true) == true
    val alert = parseAlert(raw["alert"])
    return when (raw["type"]?.toString()?.lowercase()) {
        "absolute" -> runCatching {
            val dateTime = raw["dateTime"] ?: raw["absoluteTime"] ?: return null
            ReminderSpec.Absolute(
                id = id,
                absoluteTime = parseStoredDateTime(dateTime.toString()) ?: return null,
                description = description,
                raw = raw,
                repeatEvery = repeatEvery,
                repeatUntilCompleted = repeatUntilCompleted,
                alert = alert,
            )
        }.getOrNull()
        "relative" -> runCatching {
            ReminderSpec.Relative(
                id = id,
                relatedTo = when (raw["relatedTo"]!!.toString().lowercase()) {
                    "scheduled" -> ReminderAnchor.SCHEDULED
                    else -> ReminderAnchor.DUE
                },
                offset = Duration.parse(raw["offset"]!!.toString()),
                description = description,
                raw = raw,
                repeatEvery = repeatEvery,
                repeatUntilCompleted = repeatUntilCompleted,
                alert = alert,
            )
        }.getOrNull()
        else -> null
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

private fun parseStoredDateTime(value: String): LocalDateTime? {
    val text = value.trim()
    return runCatching { LocalDateTime.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDate.parse(text.take(10)).atTime(LocalTime.of(9, 0)) }.getOrNull()
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
