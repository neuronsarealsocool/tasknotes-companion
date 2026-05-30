package com.local.tasknotescompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.local.tasknotescompanion.TaskNotesApp
import com.local.tasknotescompanion.data.toRecord

class VaultScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TaskNotesApp
        return runCatching {
            app.repository.scanVault()
            app.reminderScheduler.rescheduleAll(app.database.taskDao().snapshot().map { it.toRecord() })
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
