package com.local.tasknotescompanion

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.local.tasknotescompanion.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class RichReminderAudioService : Service() {
    private var player: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play(intent.getStringExtra(EXTRA_AUDIO_PATH), intent.getBooleanExtra(EXTRA_LOOP, true))
            ACTION_SHOW_RICH_REMINDER -> showRichReminder(intent)
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun showRichReminder(intent: Intent) {
        val scheduleId = intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID) ?: "rich-reminder"
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE).orEmpty().ifBlank { "Task reminder" }
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_NOTE)
            ?: intent.getStringExtra(ReminderScheduler.EXTRA_DESCRIPTION)
            ?: "Task reminder"
        val activityIntent = Intent(this, RichReminderActivity::class.java)
            .setAction(ReminderScheduler.ACTION_RICH_REMINDER)
            .putExtras(intent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            "rich-service-$scheduleId".hashCode(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationId = scheduleId.hashCode().takeUnless { it == 0 } ?: RICH_NOTIFICATION_ID
        startForeground(
            AUDIO_NOTIFICATION_ID,
            NotificationCompat.Builder(this, ReminderScheduler.SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Reminder active")
                .setContentText(title)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build(),
        )
        val notification = NotificationCompat.Builder(this, ReminderScheduler.RICH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setAutoCancel(false)
            .build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
        Log.i(TAG, "Posted rich full-screen notification for $scheduleId")

        handleDeliveredReminder(intent)
        val audio = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_AUDIO)
        if (!audio.isNullOrBlank()) {
            play(audio, intent.getBooleanExtra(ReminderScheduler.EXTRA_ALERT_AUDIO_LOOP, true), ensureForeground = false)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleDeliveredReminder(intent: Intent) {
        val scheduleId = intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID) ?: return
        Thread {
            val app = applicationContext as TaskNotesApp
            runBlocking(Dispatchers.IO) {
                runCatching { app.reminderScheduler.scheduleNextRepeatIfNeeded(intent) }
                    .onFailure { Log.e(TAG, "Failed to schedule repeat reminder from rich service", it) }
                runCatching { app.database.scheduledNotificationDao().deleteById(scheduleId) }
                    .onFailure { Log.e(TAG, "Failed to delete delivered rich reminder", it) }
            }
        }.start()
    }

    private fun play(path: String?, loop: Boolean, ensureForeground: Boolean = true) {
        if (path.isNullOrBlank()) {
            Log.w(TAG, "No audio path supplied")
            return
        }
        releasePlayer()
        if (ensureForeground) {
            startForeground(
                AUDIO_NOTIFICATION_ID,
                NotificationCompat.Builder(this, ReminderScheduler.SERVICE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Reminder audio playing")
                .setContentText("Tap the reminder to silence or dismiss")
                .setOngoing(true)
                .build(),
            )
        }
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $path")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(file.absolutePath)
                isLooping = loop
                prepare()
                start()
            }
            Log.i(TAG, "Playing reminder audio ${file.absolutePath}, loop=$loop")
        }.onFailure {
            Log.e(TAG, "Failed to play reminder audio: $path", it)
            releasePlayer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    companion object {
        const val ACTION_PLAY = "com.local.tasknotescompanion.PLAY_RICH_REMINDER_AUDIO"
        const val ACTION_SHOW_RICH_REMINDER = "com.local.tasknotescompanion.SHOW_RICH_REMINDER"
        const val ACTION_STOP = "com.local.tasknotescompanion.STOP_RICH_REMINDER_AUDIO"
        const val EXTRA_AUDIO_PATH = "audioPath"
        const val EXTRA_LOOP = "loop"
        private const val AUDIO_NOTIFICATION_ID = 710101
        private const val RICH_NOTIFICATION_ID = 710102
        private const val TAG = "RichReminderAudio"
    }
}
