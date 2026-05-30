package com.local.tasknotescompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.local.tasknotescompanion.TaskNotesApp
import com.local.tasknotescompanion.data.toRecord
import java.time.LocalDate

class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TaskNotesApp
        return runCatching {
            val prefs = app.notificationPreferencesStore.load()
            if (prefs.dailySummaryEnabled) {
                val today = LocalDate.now()
                val tasks = app.database.taskDao().snapshot().map { it.toRecord() }
                val open = tasks.filter { !it.isDone }
                app.reminderScheduler.showDailySummary(
                    today = open.count { it.due == today || it.scheduled == today },
                    overdue = open.count { it.due != null && it.due.isBefore(today) },
                    upcoming = open.count { listOfNotNull(it.due, it.scheduled).any { date -> date.isAfter(today) } },
                )
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
