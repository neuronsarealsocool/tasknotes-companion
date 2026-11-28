package com.local.tasknotescompanion

import android.content.Context
import android.content.Intent
import android.util.Log
import com.local.tasknotescompanion.domain.isReminderDeliveryDismissed
import com.local.tasknotescompanion.notifications.ReminderScheduler
import kotlinx.coroutines.*
import java.io.File

/** Watch only the displayed task while its reminder is visible, including over other apps. */
class ReminderSyncWatcher(context: Context, source: Intent, private val onDismiss: () -> Unit) {
    private val app = context.applicationContext as TaskNotesApp
    private val taskId = source.getStringExtra(ReminderScheduler.EXTRA_TASK_ID)
    private val taskPath = source.getStringExtra(ReminderScheduler.EXTRA_TASK_PATH)
    private val deliveryAt = source.getLongExtra(ReminderScheduler.EXTRA_TRIGGER_AT_MILLIS, 0L)
        .takeIf { it > 0L } ?: System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var missingChecks = 0

    fun start() {
        if (taskId.isNullOrBlank() && taskPath.isNullOrBlank()) return
        scope.launch {
            while (isActive) {
                val dismissed = withContext(Dispatchers.IO) {
                    try {
                        val task = app.repository.findTask(taskId, taskPath, refreshFromDisk = true)
                        if (task == null && !taskPath.isNullOrBlank() && !File(taskPath).exists()) {
                            missingChecks++
                            if (missingChecks >= 2) {
                                if (!taskId.isNullOrBlank()) app.reminderScheduler.cancelTask(taskId)
                                return@withContext true
                            }
                        } else missingChecks = 0
                        if (task != null && task.isReminderDeliveryDismissed(deliveryAt)) {
                            app.reminderScheduler.rescheduleTask(task)
                            true
                        } else false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        Log.w("ReminderSyncWatcher", "Could not check the active reminder note", failure)
                        false
                    }
                }
                if (dismissed) {
                    onDismiss()
                    break
                }
                delay(1500)
            }
        }
    }

    fun stop() { scope.cancel() }
}
