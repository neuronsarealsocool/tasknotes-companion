package com.local.tasknotescompanion.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.local.tasknotescompanion.MainActivity
import com.local.tasknotescompanion.R
import com.local.tasknotescompanion.RichReminderActivity
import com.local.tasknotescompanion.RichReminderAudioService
import com.local.tasknotescompanion.data.ScheduledNotificationDao
import com.local.tasknotescompanion.data.ScheduledNotificationEntity
import com.local.tasknotescompanion.data.TaskMappingStore
import com.local.tasknotescompanion.data.TaskDao
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.domain.ResolvedReminder
import com.local.tasknotescompanion.domain.TaskRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.io.File

class ReminderScheduler(
    private val context: Context,
    private val scheduledDao: ScheduledNotificationDao,
    private val taskDao: TaskDao,
    private val mappingStore: TaskMappingStore,
    private val preferencesStore: NotificationPreferencesStore,
    private val resolver: ReminderResolver = ReminderResolver(),
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun rescheduleTask(task: TaskRecord) = withContext(Dispatchers.IO) {
        cancelTask(task.id)
        val prefs = preferencesStore.load()
        val rows = resolver.resolve(task, prefs)
            .mapNotNull { it.nextRepeatOccurrenceIfNeeded() }
            .filter { it.isSchedulable() }
            .map { it.toEntity("task") }
        scheduledDao.insertAll(rows)
        rows.forEach(::scheduleRow)
        Log.i(TAG, "Scheduled ${rows.size} reminders for ${task.id}")
    }

    suspend fun cancelTask(taskId: String) = withContext(Dispatchers.IO) {
        scheduledDao.byTask(taskId).forEach(::cancelRow)
        scheduledDao.deleteByTask(taskId)
    }

    suspend fun rescheduleAll(tasks: List<TaskRecord>) = withContext(Dispatchers.IO) {
        scheduledDao.all().forEach(::cancelRow)
        scheduledDao.deleteAll()
        tasks.forEach { rescheduleTask(it) }
    }

    suspend fun scheduleSnooze(task: TaskRecord, minutes: Long, label: String) = withContext(Dispatchers.IO) {
        val trigger = if (minutes == TOMORROW_MINUTES) {
            LocalDate.now().plusDays(1).atTime(LocalTime.of(9, 0))
        } else {
            LocalDateTime.now().plusMinutes(minutes)
        }
        val row = ScheduledNotificationEntity(
            id = "snooze_${task.id}_${System.currentTimeMillis()}",
            taskId = task.id,
            taskPath = task.path,
            taskTitle = task.title,
            reminderId = "snooze",
            triggerAtMillis = trigger.toMillis(),
            kind = "snooze",
            description = label,
            repeatEveryMillis = null,
            repeatUntilCompleted = false,
            alertStyle = null,
            alertNote = null,
            alertImage = null,
            alertAudio = null,
            alertAudioLoop = true,
            alertAudioUntil = null,
            alertAllowOverlay = false,
        )
        scheduledDao.insertAll(listOf(row))
        scheduleRow(row)
    }

    suspend fun showScheduledNotification(scheduleId: String) {
        val row = scheduledDao.findById(scheduleId)
        if (row == null) {
            Log.w(TAG, "No scheduled notification row for $scheduleId")
            return
        }
        scheduledDao.deleteById(scheduleId)
        Log.i(TAG, "Showing notification $scheduleId for ${row.taskId}")
        showTaskNotification(row)
        if (row.hasRichAlert()) {
            showRichReminder(row)
        }
    }

    fun showAlarmNotification(intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val taskPath = intent.getStringExtra(EXTRA_TASK_PATH) ?: ""
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Task reminder"
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: scheduleId
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "reminder"
        val description = intent.getStringExtra(EXTRA_DESCRIPTION)
        val triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER_AT_MILLIS, System.currentTimeMillis())
        val repeatEveryMillis = intent.getLongExtra(EXTRA_REPEAT_EVERY_MILLIS, 0L).takeIf { it > 0L }
        val repeatUntilCompleted = intent.getBooleanExtra(EXTRA_REPEAT_UNTIL_COMPLETED, false)
        val alertStyle = intent.getStringExtra(EXTRA_ALERT_STYLE)
        val alertNote = intent.getStringExtra(EXTRA_ALERT_NOTE)
        val alertImage = intent.getStringExtra(EXTRA_ALERT_IMAGE)
        val alertAudio = intent.getStringExtra(EXTRA_ALERT_AUDIO)
        val alertAudioLoop = intent.getBooleanExtra(EXTRA_ALERT_AUDIO_LOOP, true)
        val alertAudioUntil = intent.getStringExtra(EXTRA_ALERT_AUDIO_UNTIL)
        val alertAllowOverlay = intent.getBooleanExtra(EXTRA_ALERT_ALLOW_OVERLAY, false)
        Log.i(TAG, "Showing notification $scheduleId for $taskId")
        val row = ScheduledNotificationEntity(
            id = scheduleId,
            taskId = taskId,
            taskPath = taskPath,
            taskTitle = taskTitle,
            reminderId = reminderId,
            triggerAtMillis = triggerAtMillis,
            kind = kind,
            description = description,
            repeatEveryMillis = repeatEveryMillis,
            repeatUntilCompleted = repeatUntilCompleted,
            alertStyle = alertStyle,
            alertNote = alertNote,
            alertImage = alertImage,
            alertAudio = alertAudio,
            alertAudioLoop = alertAudioLoop,
            alertAudioUntil = alertAudioUntil,
            alertAllowOverlay = alertAllowOverlay,
        )
        showTaskNotification(row)
        if (row.hasRichAlert()) {
            showRichReminder(row)
        }
    }

    suspend fun scheduleNextRepeatIfNeeded(intent: Intent) = withContext(Dispatchers.IO) {
        val repeatEveryMillis = intent.getLongExtra(EXTRA_REPEAT_EVERY_MILLIS, 0L).takeIf { it > 0L } ?: return@withContext
        if (!intent.getBooleanExtra(EXTRA_REPEAT_UNTIL_COMPLETED, false)) return@withContext
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return@withContext
        val task = taskDao.findById(taskId)?.toRecord() ?: return@withContext
        if (task.isDone) {
            Log.i(TAG, "Not repeating ${intent.getStringExtra(EXTRA_SCHEDULE_ID)} because $taskId is complete")
            return@withContext
        }
        val deliveredAt = intent.getLongExtra(EXTRA_TRIGGER_AT_MILLIS, System.currentTimeMillis())
        var nextAt = deliveredAt + repeatEveryMillis
        val now = System.currentTimeMillis()
        while (nextAt <= now) nextAt += repeatEveryMillis
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: "repeat"
        val nextRow = ScheduledNotificationEntity(
            id = "${taskId}_${reminderId}_repeat_$nextAt".replace(Regex("[^A-Za-z0-9_\\-]"), "_"),
            taskId = task.id,
            taskPath = task.path,
            taskTitle = task.title,
            reminderId = reminderId,
            triggerAtMillis = nextAt,
            kind = intent.getStringExtra(EXTRA_KIND) ?: "reminder",
            description = intent.getStringExtra(EXTRA_DESCRIPTION),
            repeatEveryMillis = repeatEveryMillis,
            repeatUntilCompleted = true,
            alertStyle = intent.getStringExtra(EXTRA_ALERT_STYLE),
            alertNote = intent.getStringExtra(EXTRA_ALERT_NOTE),
            alertImage = intent.getStringExtra(EXTRA_ALERT_IMAGE),
            alertAudio = intent.getStringExtra(EXTRA_ALERT_AUDIO),
            alertAudioLoop = intent.getBooleanExtra(EXTRA_ALERT_AUDIO_LOOP, true),
            alertAudioUntil = intent.getStringExtra(EXTRA_ALERT_AUDIO_UNTIL),
            alertAllowOverlay = intent.getBooleanExtra(EXTRA_ALERT_ALLOW_OVERLAY, false),
        )
        scheduledDao.insertAll(listOf(nextRow))
        scheduleRow(nextRow)
        Log.i(TAG, "Scheduled repeat reminder ${nextRow.id} at $nextAt")
    }

    fun showDailySummary(today: Int, overdue: Int, upcoming: Int) {
        if (!canPostNotifications()) return
        val count = today + overdue
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Task summary")
            .setContentText("$today today, $overdue overdue, $upcoming upcoming")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$today tasks today\n$overdue overdue\n$upcoming upcoming"))
            .setNumber(count)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DAILY_SUMMARY_ID, notification)
    }

    private fun showTaskNotification(row: ScheduledNotificationEntity) {
        if (!canPostNotifications()) {
            Log.w(TAG, "Notifications are not enabled; skipping ${row.id}")
            return
        }
        val completeIntent = actionIntent(ACTION_COMPLETE, row, "complete-${row.taskId}".hashCode())
        val snooze10 = actionIntent(ACTION_SNOOZE_10, row, "snooze10-${row.id}".hashCode())
        val snooze30 = actionIntent(ACTION_SNOOZE_30, row, "snooze30-${row.id}".hashCode())
        val snooze1h = actionIntent(ACTION_SNOOZE_1H, row, "snooze1h-${row.id}".hashCode())
        val tomorrow = actionIntent(ACTION_SNOOZE_TOMORROW, row, "tomorrow-${row.id}".hashCode())
        val openTask = PendingIntent.getActivity(
            context,
            "open-${row.taskId}".hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TASK)
                .putExtra(EXTRA_TASK_ID, row.taskId)
                .putExtra(EXTRA_TASK_PATH, row.taskPath)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreen = if (row.hasRichAlert()) {
            PendingIntent.getActivity(
                context,
                "rich-${row.id}".hashCode(),
                Intent(context, RichReminderActivity::class.java)
                    .setAction(ACTION_RICH_REMINDER)
                    .putReminderExtras(row)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(row.taskTitle)
            .setContentText(row.description ?: row.kind.defaultNotificationText())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openTask)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_launcher_foreground, "Complete", completeIntent)
            .addAction(R.drawable.ic_launcher_foreground, "10m", snooze10)
            .addAction(R.drawable.ic_launcher_foreground, "30m", snooze30)
            .addAction(R.drawable.ic_launcher_foreground, "1h", snooze1h)
            .addAction(R.drawable.ic_launcher_foreground, "Tomorrow", tomorrow)
            .apply {
                if (fullScreen != null) setFullScreenIntent(fullScreen, true)
            }
            .build()
        NotificationManagerCompat.from(context).notify(row.id.hashCode(), notification)
    }

    private fun showRichReminder(row: ScheduledNotificationEntity) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RichReminderAudioService::class.java)
                .setAction(RichReminderAudioService.ACTION_SHOW_RICH_REMINDER)
                .putReminderExtras(row),
        )
    }

    private fun actionIntent(action: String, row: ScheduledNotificationEntity, requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, row.taskId)
                .putExtra(EXTRA_SCHEDULE_ID, row.id)
                .putExtra(EXTRA_TASK_PATH, row.taskPath),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleRow(row: ScheduledNotificationEntity) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            row.id.hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_SCHEDULE_ID, row.id)
                .putExtra(EXTRA_TASK_ID, row.taskId)
                .putExtra(EXTRA_TASK_PATH, row.taskPath)
                .putExtra(EXTRA_TASK_TITLE, row.taskTitle)
                .putExtra(EXTRA_REMINDER_ID, row.reminderId)
                .putExtra(EXTRA_KIND, row.kind)
                .putExtra(EXTRA_DESCRIPTION, row.description)
                .putExtra(EXTRA_TRIGGER_AT_MILLIS, row.triggerAtMillis)
                .putExtra(EXTRA_REPEAT_EVERY_MILLIS, row.repeatEveryMillis ?: 0L)
                .putExtra(EXTRA_REPEAT_UNTIL_COMPLETED, row.repeatUntilCompleted)
                .putReminderExtras(row),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.triggerAtMillis, pendingIntent)
            Log.i(TAG, "Scheduled inexact reminder ${row.id} at ${row.triggerAtMillis}")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.triggerAtMillis, pendingIntent)
            Log.i(TAG, "Scheduled exact reminder ${row.id} at ${row.triggerAtMillis}")
        }
    }

    private fun scheduleRichActivityRow(row: ScheduledNotificationEntity) {
        val pendingIntent = richAlarmServicePendingIntent(row, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val alarmInfo = AlarmManager.AlarmClockInfo(row.triggerAtMillis, pendingIntent)
        alarmManager.setAlarmClock(alarmInfo, pendingIntent)
        Log.i(TAG, "Scheduled rich full-screen reminder ${row.id} at ${row.triggerAtMillis}")
    }

    private fun cancelRow(row: ScheduledNotificationEntity) {
        val broadcastIntent = PendingIntent.getBroadcast(
            context,
            row.id.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (broadcastIntent != null) alarmManager.cancel(broadcastIntent)
        val serviceIntent = richAlarmServicePendingIntent(row, PendingIntent.FLAG_NO_CREATE)
        if (serviceIntent != null) alarmManager.cancel(serviceIntent)
    }

    private fun richAlarmServicePendingIntent(
        row: ScheduledNotificationEntity,
        flags: Int,
    ): PendingIntent? {
        return PendingIntent.getForegroundService(
            context,
            "rich-alarm-${row.id}".hashCode(),
            Intent(context, RichReminderAudioService::class.java)
                .setAction(RichReminderAudioService.ACTION_SHOW_RICH_REMINDER)
                .putReminderExtras(row),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ResolvedReminder.toEntity(kind: String): ScheduledNotificationEntity {
        return ScheduledNotificationEntity(
            id = "${taskId}_${id}".replace(Regex("[^A-Za-z0-9_\\-]"), "_"),
            taskId = taskId,
            taskPath = taskPath,
            taskTitle = taskTitle,
            reminderId = id,
            triggerAtMillis = triggerAt.toMillis(),
            kind = source.ifBlank { kind },
            description = description,
            repeatEveryMillis = repeatEvery?.toMillis(),
            repeatUntilCompleted = repeatUntilCompleted,
            alertStyle = alert?.style ?: "fullscreen",
            alertNote = alert?.note,
            alertImage = alert?.image,
            alertAudio = alert?.audio,
            alertAudioLoop = alert?.audioLoop ?: true,
            alertAudioUntil = alert?.audioUntil,
            alertAllowOverlay = alert?.allowOverlay ?: false,
        )
    }

    private fun ScheduledNotificationEntity.hasRichAlert(): Boolean {
        return alertStyle == "fullscreen" || !alertNote.isNullOrBlank() || !alertImage.isNullOrBlank() || !alertAudio.isNullOrBlank()
    }

    private fun Intent.putReminderExtras(row: ScheduledNotificationEntity): Intent {
        return putExtra(EXTRA_SCHEDULE_ID, row.id)
            .putExtra(EXTRA_TASK_ID, row.taskId)
            .putExtra(EXTRA_TASK_PATH, row.taskPath)
            .putExtra(EXTRA_TASK_TITLE, row.taskTitle)
            .putExtra(EXTRA_REMINDER_ID, row.reminderId)
            .putExtra(EXTRA_KIND, row.kind)
            .putExtra(EXTRA_DESCRIPTION, row.description)
            .putExtra(EXTRA_TRIGGER_AT_MILLIS, row.triggerAtMillis)
            .putExtra(EXTRA_REPEAT_EVERY_MILLIS, row.repeatEveryMillis ?: 0L)
            .putExtra(EXTRA_REPEAT_UNTIL_COMPLETED, row.repeatUntilCompleted)
            .putExtra(EXTRA_ALERT_STYLE, row.alertStyle)
            .putExtra(EXTRA_ALERT_NOTE, row.alertNote)
            .putExtra(EXTRA_ALERT_IMAGE, resolveVaultPath(row.alertImage))
            .putExtra(EXTRA_ALERT_AUDIO, resolveVaultPath(row.alertAudio))
            .putExtra(EXTRA_ALERT_AUDIO_LOOP, row.alertAudioLoop)
            .putExtra(EXTRA_ALERT_AUDIO_UNTIL, row.alertAudioUntil)
            .putExtra(EXTRA_ALERT_ALLOW_OVERLAY, row.alertAllowOverlay)
    }

    private fun resolveVaultPath(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val file = File(value)
        if (file.isAbsolute) return value
        return File(mappingStore.vaultPath(), value).absolutePath
    }

    private fun ResolvedReminder.nextRepeatOccurrenceIfNeeded(now: LocalDateTime = LocalDateTime.now()): ResolvedReminder? {
        if (isSchedulable(now)) return this
        val repeat = repeatEvery ?: return this
        if (!repeatUntilCompleted || repeat.isZero || repeat.isNegative) return this
        var next = triggerAt.plus(repeat)
        while (!next.isAfter(now)) next = next.plus(repeat)
        return copy(triggerAt = next)
    }

    companion object {
        const val CHANNEL_ID = "task_reminders_alarm_v2"
        const val RICH_CHANNEL_ID = "task_rich_reminders_alarm_v1"
        const val SERVICE_CHANNEL_ID = "task_reminders_service_v1"
        const val ACTION_SHOW = "com.local.tasknotescompanion.SHOW_REMINDER"
        const val ACTION_OPEN_TASK = "com.local.tasknotescompanion.OPEN_TASK"
        const val ACTION_COMPLETE = "com.local.tasknotescompanion.COMPLETE_TASK"
        const val ACTION_RICH_REMINDER = "com.local.tasknotescompanion.RICH_REMINDER"
        const val ACTION_SNOOZE_10 = "com.local.tasknotescompanion.SNOOZE_10"
        const val ACTION_SNOOZE_30 = "com.local.tasknotescompanion.SNOOZE_30"
        const val ACTION_SNOOZE_1H = "com.local.tasknotescompanion.SNOOZE_1H"
        const val ACTION_SNOOZE_TOMORROW = "com.local.tasknotescompanion.SNOOZE_TOMORROW"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TASK_PATH = "path"
        const val EXTRA_TASK_TITLE = "title"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_TRIGGER_AT_MILLIS = "triggerAtMillis"
        const val EXTRA_REPEAT_EVERY_MILLIS = "repeatEveryMillis"
        const val EXTRA_REPEAT_UNTIL_COMPLETED = "repeatUntilCompleted"
        const val EXTRA_ALERT_STYLE = "alertStyle"
        const val EXTRA_ALERT_NOTE = "alertNote"
        const val EXTRA_ALERT_IMAGE = "alertImage"
        const val EXTRA_ALERT_AUDIO = "alertAudio"
        const val EXTRA_ALERT_AUDIO_LOOP = "alertAudioLoop"
        const val EXTRA_ALERT_AUDIO_UNTIL = "alertAudioUntil"
        const val EXTRA_ALERT_ALLOW_OVERLAY = "alertAllowOverlay"
        const val TOMORROW_MINUTES = -1L
        private const val DAILY_SUMMARY_ID = 710100
        private const val TAG = "TaskNotesReminder"
    }
}

private fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun String.defaultNotificationText(): String = when (this) {
    "due" -> "Due reminder"
    "scheduled" -> "Scheduled now"
    "snooze" -> "Snoozed reminder"
    else -> "Task reminder"
}
