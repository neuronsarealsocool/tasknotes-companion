package com.local.tasknotescompanion

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.local.tasknotescompanion.notifications.ReminderScheduler
import com.local.tasknotescompanion.notifications.ReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class RichReminderAudioService : Service() {
    private var player: MediaPlayer? = null
    private var overlayView: View? = null
    private var overlayIntent: Intent? = null

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
        removeOverlay()
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
        val showedOverlay = showOverlayIfAllowed(intent)
        if (!showedOverlay) {
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
            Log.i(TAG, "Posted rich full-screen notification fallback for $scheduleId")
        }

        handleDeliveredReminder(intent)
        val audio = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_AUDIO)
        if (!audio.isNullOrBlank()) {
            play(audio, intent.getBooleanExtra(ReminderScheduler.EXTRA_ALERT_AUDIO_LOOP, true), ensureForeground = false)
        } else if (!showedOverlay) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showOverlayIfAllowed(intent: Intent): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission is not granted; falling back to full-screen notification")
            return false
        }
        overlayIntent = Intent(intent)
        removeOverlay()
        return runCatching {
            val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE).orEmpty().ifBlank { "Task reminder" }
            val note = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_NOTE)
                ?: intent.getStringExtra(ReminderScheduler.EXTRA_DESCRIPTION)
                ?: ""
            val imagePath = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_IMAGE)
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFFCFBF8.toInt())

                addView(FrameLayout(context).apply {
                    setBackgroundColor(0xFFFCFBF8.toInt())

                    imagePath?.let { path ->
                        val file = File(path)
                        Log.i(TAG, "Loading overlay image ${file.absolutePath}; exists=${file.exists()}; size=${file.length()}")
                        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                            .onFailure { Log.e(TAG, "Failed to decode overlay image: ${file.absolutePath}", it) }
                            .getOrNull()
                        if (bitmap != null) {
                            addView(ImageView(context).apply {
                                setImageBitmap(bitmap)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }, FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ))
                        }
                    }

                    addView(TextView(context).apply {
                        text = title
                        textSize = 28f
                        setTextColor(0xFF111111.toInt())
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        setBackgroundColor(0xDDFCFCF8.toInt())
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    ).apply { setMargins(dp(16), dp(24), dp(16), 0) })

                    if (note.isNotBlank()) {
                        addView(TextView(context).apply {
                            text = note
                            textSize = 20f
                            setTextColor(0xFF111111.toInt())
                            gravity = Gravity.CENTER
                            setLineSpacing(dp(2).toFloat(), 1.0f)
                            setPadding(dp(16), dp(10), dp(16), dp(10))
                            setBackgroundColor(0xDDFCFCF8.toInt())
                        }, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                        ).apply { setMargins(dp(16), 0, dp(16), dp(20)) })
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(40), dp(12), dp(40), dp(24))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(actionButton("Edit") { editTaskFromOverlay() }, weightedButtonParams())
                        addView(actionButton("Snooze") { snoozeFromOverlay() }, weightedButtonParams())
                    })
                    addView(actionButton("Complete") { completeFromOverlay() }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(50),
                    ).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) })
                    addView(actionButton("Dismiss") { dismissOverlayReminder() }.apply {
                        backgroundTintList = ColorStateList.valueOf(0xFF2E7655.toInt())
                        setTextColor(0xFFFFFFFF.toInt())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(56),
                    ).apply { setMargins(dp(8), dp(12), dp(8), 0) })
                })
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                android.graphics.PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.CENTER
            }
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(view, params)
            overlayView = view
            Log.i(TAG, "Showing rich reminder overlay for ${intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID)}")
            true
        }.onFailure {
            Log.e(TAG, "Failed to show rich reminder overlay", it)
        }.getOrDefault(false)
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure {
            Log.w(TAG, "Failed to remove rich reminder overlay", it)
        }
        overlayView = null
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
        removeOverlay()
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

    private fun editTaskFromOverlay() {
        val source = overlayIntent
        dismissOverlayReminder(stopAudio = true)
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(ReminderScheduler.ACTION_OPEN_TASK)
            .putExtra(ReminderScheduler.EXTRA_TASK_ID, source?.getStringExtra(ReminderScheduler.EXTRA_TASK_ID))
            .putExtra(ReminderScheduler.EXTRA_TASK_PATH, source?.getStringExtra(ReminderScheduler.EXTRA_TASK_PATH))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun snoozeFromOverlay() {
        sendReminderAction(ReminderScheduler.ACTION_SNOOZE_10)
        dismissOverlayReminder()
    }

    private fun completeFromOverlay() {
        sendReminderAction(ReminderScheduler.ACTION_COMPLETE)
        dismissOverlayReminder()
    }

    private fun dismissOverlayReminder(stopAudio: Boolean = true) {
        removeOverlay()
        if (stopAudio) stopPlayback()
    }

    private fun sendReminderAction(action: String) {
        val source = overlayIntent ?: return
        sendBroadcast(Intent(this, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderScheduler.EXTRA_TASK_ID, source.getStringExtra(ReminderScheduler.EXTRA_TASK_ID))
            .putExtra(ReminderScheduler.EXTRA_TASK_PATH, source.getStringExtra(ReminderScheduler.EXTRA_TASK_PATH))
            .putExtra(ReminderScheduler.EXTRA_SCHEDULE_ID, source.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID)))
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            minHeight = dp(48)
            setOnClickListener { action() }
        }
    }

    private fun weightedButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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
