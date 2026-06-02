package com.local.tasknotescompanion.notifications

import com.local.tasknotescompanion.domain.NotificationPreferences
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.ResolvedReminder
import com.local.tasknotescompanion.domain.TaskRecord
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderResolver {
    fun resolve(task: TaskRecord, preferences: NotificationPreferences): List<ResolvedReminder> {
        if (!preferences.remindersEnabled || task.isDone) return emptyList()
        val explicit = task.reminders.mapNotNull { resolveReminder(task, it, preferences) }
        val dueReminder = if (preferences.overdueReminderEnabled && task.due != null) {
            ResolvedReminder(
                id = "due_${task.id}",
                taskId = task.id,
                taskPath = task.path,
            taskTitle = task.title,
            triggerAt = task.due.atTime(task.dueTime ?: preferences.dateOnlyAnchorTime),
            description = "Due today",
            source = "due",
        )
        } else {
            null
        }
        val scheduledReminder = task.scheduled?.let {
            ResolvedReminder(
                id = "scheduled_${task.id}",
                taskId = task.id,
                taskPath = task.path,
            taskTitle = task.title,
            triggerAt = it.atTime(task.scheduledTime ?: preferences.dateOnlyAnchorTime),
            description = "Scheduled now",
            source = "scheduled",
        )
        }
        return (explicit + listOfNotNull(dueReminder, scheduledReminder))
            .distinctBy { it.id }
            .sortedWith(compareBy<ResolvedReminder> { it.triggerAt }.thenBy { it.id })
    }

    private fun resolveReminder(
        task: TaskRecord,
        reminder: ReminderSpec,
        preferences: NotificationPreferences,
    ): ResolvedReminder? {
        val trigger = when (reminder) {
            is ReminderSpec.Absolute -> reminder.absoluteTime
            is ReminderSpec.Relative -> {
                val reminderTime = reminder.raw["reminderTime"]?.toString()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val base = when (reminder.relatedTo) {
                    ReminderAnchor.DUE -> task.due?.atTime(task.dueTime ?: reminderTime ?: preferences.dateOnlyAnchorTime)
                    ReminderAnchor.SCHEDULED -> task.scheduled?.atTime(task.scheduledTime ?: reminderTime ?: preferences.dateOnlyAnchorTime)
                } ?: return null
                base.plus(reminder.offset)
            }
        }
        return ResolvedReminder(
            id = reminder.id,
            taskId = task.id,
            taskPath = task.path,
            taskTitle = task.title,
            triggerAt = trigger,
            description = reminder.description,
            source = "reminder",
            repeatEvery = reminder.repeatEvery,
            repeatUntilCompleted = reminder.repeatUntilCompleted,
            alert = reminder.alert,
        )
    }
}

fun ResolvedReminder.isSchedulable(now: LocalDateTime = LocalDateTime.now()): Boolean {
    return !triggerAt.isBefore(now.minusSeconds(ImmediateReminderGraceSeconds))
}

private const val ImmediateReminderGraceSeconds = 10L
