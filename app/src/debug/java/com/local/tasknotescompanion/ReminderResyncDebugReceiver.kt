package com.local.tasknotescompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.local.tasknotescompanion.data.toRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ReminderResyncDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_RESYNC_REMINDERS) return
        val pendingResult = goAsync()
        Thread {
            runCatching {
                val app = context.applicationContext as TaskNotesApp
                runBlocking(Dispatchers.IO) {
                    val count = app.repository.scanVault()
                    val tasks = app.database.taskDao().snapshot().map { it.toRecord() }
                    app.reminderScheduler.rescheduleAll(tasks)
                    val scheduled = app.database.scheduledNotificationDao().all()
                    Log.i(TAG, "Debug resync scanned $count notes, indexed ${tasks.size} tasks, scheduled ${scheduled.size} notifications")
                    scheduled.forEach { row ->
                        Log.i(TAG, "Debug scheduled ${row.id} at ${row.triggerAtMillis}, alertStyle=${row.alertStyle}, description=${row.description}")
                    }
                }
            }.onFailure {
                Log.e(TAG, "Debug reminder resync failed", it)
            }
            pendingResult.finish()
        }.start()
    }

    companion object {
        const val ACTION_DEBUG_RESYNC_REMINDERS = "com.local.tasknotescompanion.DEBUG_RESYNC_REMINDERS"
        private const val TAG = "TaskNotesDebug"
    }
}
