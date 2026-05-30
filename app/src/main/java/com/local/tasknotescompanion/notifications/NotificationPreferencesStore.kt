package com.local.tasknotescompanion.notifications

import android.content.Context
import com.local.tasknotescompanion.domain.NotificationPreferences
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import java.time.Duration
import java.time.LocalTime

class NotificationPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)

    fun load(): NotificationPreferences {
        migrateLegacyDateOnlyAnchorTime()
        migrateAfterScheduledDefaultReminders()
        return NotificationPreferences(
            remindersEnabled = prefs.getBoolean("remindersEnabled", true),
            dateOnlyAnchorTime = parseTime(prefs.getString("dateOnlyAnchorTime", DEFAULT_DATE_ONLY_ANCHOR)!!),
            defaultReminders = parseDefaultReminders(prefs.getString("defaultReminders", DEFAULT_REMINDERS)!!),
            dailySummaryEnabled = prefs.getBoolean("dailySummaryEnabled", true),
            dailySummaryTime = parseTime(prefs.getString("dailySummaryTime", "08:00")!!),
            overdueReminderEnabled = prefs.getBoolean("overdueReminderEnabled", true),
            badgeEnabled = prefs.getBoolean("badgeEnabled", true),
        )
    }

    fun save(value: NotificationPreferences) {
        prefs.edit()
            .putBoolean("remindersEnabled", value.remindersEnabled)
            .putString("dateOnlyAnchorTime", value.dateOnlyAnchorTime.toString())
            .putString("defaultReminders", value.defaultReminders.joinToString("\n") { "${formatDuration(it.offset)}|${it.relatedTo.name.lowercase()}" })
            .putBoolean("dailySummaryEnabled", value.dailySummaryEnabled)
            .putString("dailySummaryTime", value.dailySummaryTime.toString())
            .putBoolean("overdueReminderEnabled", value.overdueReminderEnabled)
            .putBoolean("badgeEnabled", value.badgeEnabled)
            .apply()
    }

    private fun parseTime(value: String): LocalTime = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(10, 15))

    private fun migrateLegacyDateOnlyAnchorTime() {
        if (prefs.getBoolean("dateOnlyAnchorTimeMigratedToTaskNotesDefault", false)) return
        val current = prefs.getString("dateOnlyAnchorTime", null)
        if (current == null || current == "09:00") {
            prefs.edit().putString("dateOnlyAnchorTime", DEFAULT_DATE_ONLY_ANCHOR).apply()
        }
        prefs.edit().putBoolean("dateOnlyAnchorTimeMigratedToTaskNotesDefault", true).apply()
    }

    private fun migrateAfterScheduledDefaultReminders() {
        if (prefs.getBoolean("afterScheduledDefaultRemindersAdded", false)) return
        val existing = prefs.getString("defaultReminders", null)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: DEFAULT_REMINDERS.lines().filter { it.isNotBlank() }.toMutableList()
        AFTER_SCHEDULED_DEFAULT_REMINDERS.lines()
            .filter { it.isNotBlank() }
            .forEach { reminder ->
                if (existing.none { it.equals(reminder, ignoreCase = true) }) {
                    existing += reminder
                }
            }
        prefs.edit()
            .putString("defaultReminders", existing.joinToString("\n"))
            .putBoolean("afterScheduledDefaultRemindersAdded", true)
            .apply()
    }

    private fun parseDefaultReminders(value: String): List<ReminderSpec.Relative> {
        return value.lines().filter { it.isNotBlank() }.mapIndexedNotNull { index, line ->
            val parts = line.split("|", limit = 2)
            val offset = runCatching { Duration.parse(parts.first()) }.getOrNull() ?: return@mapIndexedNotNull null
            val anchor = if (parts.getOrNull(1)?.equals("scheduled", true) == true) ReminderAnchor.SCHEDULED else ReminderAnchor.DUE
            ReminderSpec.Relative(
                id = "default_${anchor.name.lowercase()}_${index}_${offset.toString().replace("-", "minus_")}",
                relatedTo = anchor,
                offset = offset,
                description = "Default reminder",
            )
        }
    }

    private fun formatDuration(duration: Duration): String {
        return if (duration.isNegative) "-${duration.negated()}" else duration.toString()
    }

    private companion object {
        const val DEFAULT_DATE_ONLY_ANCHOR = "10:15"
        const val AFTER_SCHEDULED_DEFAULT_REMINDERS = "PT5M|scheduled\nPT10M|scheduled\nPT15M|scheduled\nPT30M|scheduled\nPT1H|scheduled\nPT2H|scheduled"
        const val DEFAULT_REMINDERS = "-PT15M|due\n$AFTER_SCHEDULED_DEFAULT_REMINDERS"
    }
}
