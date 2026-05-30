package com.local.tasknotescompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.local.tasknotescompanion.TaskNotesApp
import com.local.tasknotescompanion.data.toRecord

class NotificationResyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TaskNotesApp
        return runCatching {
            app.repository.scanVault()
            val tasks = app.database.taskDao().snapshot().map { it.toRecord() }
            app.reminderScheduler.rescheduleAll(tasks)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
