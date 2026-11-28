package com.local.tasknotescompanion.domain

const val ReminderDismissedThroughField = "companion_reminder_dismissed_through"

val TaskRecord.reminderDismissedThrough: Long
    get() = frontmatter.raw[ReminderDismissedThroughField]?.toString()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

fun TaskRecord.withReminderDismissedThrough(through: Long): TaskRecord {
    val latest = maxOf(reminderDismissedThrough, through)
    if (latest <= 0L) return this
    return copy(frontmatter = TaskFrontmatter(frontmatter.raw + (ReminderDismissedThroughField to latest)))
}

fun TaskRecord.dismissReminders(nowMillis: Long, triggerAtMillis: Long = 0L): TaskRecord =
    withReminderDismissedThrough(maxOf(nowMillis, triggerAtMillis))
