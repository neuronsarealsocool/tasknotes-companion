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
        val hasScheduledAtTimeReminder = task.reminders.any {
            it is ReminderSpec.Relative && it.relatedTo == ReminderAnchor.SCHEDULED && it.offset.isZero
        }
        val explicit = task.reminders.mapNotNull { resolveReminder(task, it, preferences, hasScheduledAtTimeReminder) }
        val explicitTriggerTimes = explicit.map { it.triggerAt }.toSet()
        val dueReminder = if (preferences.overdueReminderEnabled && task.due != null) {
            val dueAt = task.due.atTime(task.dueTime ?: preferences.dateOnlyAnchorTime)
            if (dueAt in explicitTriggerTimes) {
                null
            } else {
                ResolvedReminder(
                    id = "due_${task.id}",
                    taskId = task.id,
                    taskPath = task.path,
                    taskTitle = task.title,
                    triggerAt = dueAt,
                    description = "Due today",
                    source = "due",
                )
            }
        } else {
            null
        }
        val scheduledReminder = task.scheduled?.let {
            val scheduledAt = it.atTime(task.scheduledTime ?: preferences.dateOnlyAnchorTime)
            if (scheduledAt in explicitTriggerTimes) {
                null
            } else {
                ResolvedReminder(
                    id = "scheduled_${task.id}",
                    taskId = task.id,
                    taskPath = task.path,
                    taskTitle = task.title,
                    triggerAt = scheduledAt,
                    description = "Scheduled now",
                    source = "scheduled",
                )
            }
        }
        return (explicit + listOfNotNull(dueReminder, scheduledReminder))
            .distinctBy { it.id }
            .sortedWith(compareBy<ResolvedReminder> { it.triggerAt }.thenBy { it.id })
    }

    private fun resolveReminder(
        task: TaskRecord,
        reminder: ReminderSpec,
        preferences: NotificationPreferences,
        hasScheduledAtTimeReminder: Boolean,
    ): ResolvedReminder? {
        val trigger = when (reminder) {
            is ReminderSpec.Absolute -> reminder.absoluteTime
            is ReminderSpec.Relative -> {
                val reminderTime = reminder.raw["reminderTime"]?.toString()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val base = when (reminder.relatedTo) {
                    ReminderAnchor.DUE -> {
                        val dueBase = task.due?.atTime(task.dueTime ?: reminderTime ?: preferences.dateOnlyAnchorTime)
                        dueBase ?: if (
                            task.scheduled != null &&
                            reminder.offset.isZero &&
                            reminder.alert != null &&
                            !hasScheduledAtTimeReminder
                        ) {
                            task.scheduled.atTime(task.scheduledTime ?: reminderTime ?: preferences.dateOnlyAnchorTime)
                        } else {
                            null
                        }
                    }
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
