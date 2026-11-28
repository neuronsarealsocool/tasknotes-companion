package com.local.tasknotescompanion.domain

const val PhoneOnlyAlarmField = "companion_alarm"

val TaskRecord.isPhoneOnlyAlarm: Boolean
    get() = frontmatter.raw[PhoneOnlyAlarmField]?.toString()?.equals("true", ignoreCase = true) == true

fun TaskRecord.withPhoneOnlyAlarm(enabled: Boolean): TaskRecord {
    if (!enabled && PhoneOnlyAlarmField !in frontmatter.raw) return this
    return copy(frontmatter = TaskFrontmatter(frontmatter.raw + (PhoneOnlyAlarmField to enabled)))
}

fun TaskRecord.alarmAlert(alert: ReminderAlert?): ReminderAlert? =
    if (isPhoneOnlyAlarm) (alert ?: editorReminderOverlay()).copy(style = "alarm") else alert
