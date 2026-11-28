package com.local.tasknotescompanion.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.local.tasknotescompanion.TaskNotesApp
import com.local.tasknotescompanion.data.toRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskNotesApp
        if (intent.action == ReminderScheduler.ACTION_SHOW) {
            Log.i(TAG, "Received ${intent.action}")
            app.reminderScheduler.showAlarmNotification(intent)
            intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID)?.let { scheduleId ->
                Thread {
                    runBlocking(Dispatchers.IO) {
                        runCatching { app.reminderScheduler.scheduleNextRepeatIfNeeded(intent) }
                            .onFailure { Log.e(TAG, "Failed to schedule repeat reminder", it) }
                        runCatching { app.database.scheduledNotificationDao().deleteById(scheduleId) }
                            .onFailure { Log.e(TAG, "Failed to delete delivered reminder", it) }
                    }
                }.start()
            }
            return
        }
        val pending = goAsync()
        Thread {
            try {
                runBlocking(Dispatchers.IO) {
                    runCatching {
                        Log.i(TAG, "Received ${intent.action}")
                        when (intent.action) {
                            ReminderScheduler.ACTION_COMPLETE -> {
                                val task = intent.task(app)
                                if (task != null) {
                                    app.repository.complete(task, intent.dismissedAt(), intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER_AT_MILLIS, 0L))
                                    app.reminderScheduler.cancelTask(task.id)
                                    app.database.taskDao().findById(task.id)?.toRecord()?.let { app.reminderScheduler.rescheduleTask(it) }
                                }
                            }
                            ReminderScheduler.ACTION_DISMISS -> {
                                intent.task(app)?.let { app.repository.dismissReminder(it, intent.dismissedAt(), intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER_AT_MILLIS, 0L)) }
                            }
                            ReminderScheduler.ACTION_SNOOZE_10 -> snooze(app, intent, 10, "Snoozed 10 minutes")
                            ReminderScheduler.ACTION_SNOOZE_30 -> snooze(app, intent, 30, "Snoozed 30 minutes")
                            ReminderScheduler.ACTION_SNOOZE_1H -> snooze(app, intent, 60, "Snoozed 1 hour")
                            ReminderScheduler.ACTION_SNOOZE_TOMORROW -> snooze(app, intent, ReminderScheduler.TOMORROW_MINUTES, "Snoozed until tomorrow")
                        }
                    }.onFailure {
                        Log.e(TAG, "Reminder action failed", it)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private suspend fun snooze(app: TaskNotesApp, intent: Intent, minutes: Long, label: String) {
        val task = intent.task(app) ?: return
        app.reminderScheduler.scheduleSnooze(task, minutes, label)
    }

    private fun Intent.dismissedAt(): Long = getLongExtra(ReminderScheduler.EXTRA_DISMISSED_AT_MILLIS, 0L)
        .takeIf { it > 0L } ?: System.currentTimeMillis()

    private suspend fun Intent.task(app: TaskNotesApp) = app.repository.findTask(
        getStringExtra(ReminderScheduler.EXTRA_TASK_ID), getStringExtra(ReminderScheduler.EXTRA_TASK_PATH),
    )

    private companion object {
        const val TAG = "TaskNotesReminder"
    }
}
