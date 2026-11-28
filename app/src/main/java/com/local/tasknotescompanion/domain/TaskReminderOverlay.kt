package com.local.tasknotescompanion.domain

private const val OverlayField = "companion_reminder_overlay"

fun TaskRecord.savedReminderOverlay(): ReminderAlert? {
    val raw = (frontmatter.raw[OverlayField] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        ?: return null
    return ReminderAlert(
        style = raw["style"] as? String ?: "fullscreen",
        note = raw["note"] as? String,
        image = raw["image"] as? String,
        video = raw["video"] as? String,
        audio = raw["audio"] as? String,
        audioLoop = raw["audioLoop"] as? Boolean ?: true,
        audioUntil = raw["audioUntil"] as? String ?: "dismiss",
        allowOverlay = raw["allowOverlay"] as? Boolean ?: false,
        raw = raw,
    )
}

fun TaskRecord.editorReminderOverlay(): ReminderAlert =
    savedReminderOverlay() ?: reminders.firstNotNullOfOrNull { it.alert } ?: ReminderAlert()

fun TaskRecord.withReminderOverlay(alert: ReminderAlert): TaskRecord {
    val raw = alert.raw + mapOf(
        "style" to alert.style, "note" to alert.note, "image" to alert.image,
        "video" to alert.video, "audio" to alert.audio, "audioLoop" to alert.audioLoop,
        "audioUntil" to alert.audioUntil, "allowOverlay" to alert.allowOverlay,
    )
    return copy(frontmatter = TaskFrontmatter(frontmatter.raw + (OverlayField to raw.filterValues { it != null })))
}

/** Apply only edited shared fields; keep each reminder's other overrides and metadata. */
fun List<ReminderSpec>.editSharedOverlay(previous: ReminderAlert, next: ReminderAlert): List<ReminderSpec> = map { reminder ->
    val original = reminder.alert ?: ReminderAlert()
    val alert = original.copy(
        style = if (next.style != previous.style) next.style else original.style,
        note = if (next.note != previous.note) next.note else original.note,
        image = if (next.image != previous.image) next.image else original.image,
        video = if (next.video != previous.video) next.video else original.video,
        audio = if (next.audio != previous.audio) next.audio else original.audio,
        audioLoop = if (next.audioLoop != previous.audioLoop) next.audioLoop else original.audioLoop,
        audioUntil = if (next.audioUntil != previous.audioUntil) next.audioUntil else original.audioUntil,
        allowOverlay = if (next.allowOverlay != previous.allowOverlay) next.allowOverlay else original.allowOverlay,
    )
    when (reminder) {
        is ReminderSpec.Absolute -> reminder.copy(alert = alert)
        is ReminderSpec.Relative -> reminder.copy(alert = alert)
    }
}
