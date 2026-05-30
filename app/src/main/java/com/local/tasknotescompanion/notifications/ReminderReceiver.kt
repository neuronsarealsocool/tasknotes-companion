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
        runBlocking(Dispatchers.IO) {
            runCatching {
                Log.i(TAG, "Received ${intent.action}")
                when (intent.action) {
                    ReminderScheduler.ACTION_COMPLETE -> {
                        val task = intent.task(app)
                        if (task != null) {
                            app.repository.complete(task)
                            app.reminderScheduler.cancelTask(task.id)
                        }
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
    }

    private suspend fun snooze(app: TaskNotesApp, intent: Intent, minutes: Long, label: String) {
        val task = intent.task(app) ?: return
        app.reminderScheduler.scheduleSnooze(task, minutes, label)
    }

    private suspend fun Intent.task(app: TaskNotesApp) =
        getStringExtra(ReminderScheduler.EXTRA_TASK_ID)
            ?.let { app.database.taskDao().findById(it) }
            ?.toRecord()

    private companion object {
        const val TAG = "TaskNotesReminder"
    }
}
