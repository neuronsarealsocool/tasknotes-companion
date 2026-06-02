package com.local.tasknotescompanion

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.local.tasknotescompanion.notifications.ReminderReceiver
import com.local.tasknotescompanion.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class RichReminderActivity : ComponentActivity() {
    private var sourceIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()
        sourceIntent = intent
        handleDeliveredReminder(intent)
        startReminderAudio(intent)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sourceIntent = intent
        handleDeliveredReminder(intent)
        startReminderAudio(intent)
        render(intent)
    }

    override fun onBackPressed() {
        dismiss()
    }

    private fun render(intent: Intent) {
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE).orEmpty().ifBlank { "Task reminder" }
        val note = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_NOTE)
            ?: intent.getStringExtra(ReminderScheduler.EXTRA_DESCRIPTION)
            ?: ""
        val imagePath = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_IMAGE)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFFCFBF8.toInt())

                addView(ScrollView(context).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(32), dp(28), dp(20))

                        addView(TextView(context).apply {
                            text = title
                            textSize = 24f
                            setTextColor(0xFF111111.toInt())
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { setMargins(0, 0, 0, dp(16)) })

                        imagePath?.let { path ->
                            val file = File(path)
                            Log.i(TAG, "Loading rich reminder image ${file.absolutePath}; exists=${file.exists()}; size=${file.length()}")
                            val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                                .onFailure { Log.e(TAG, "Failed to decode rich reminder image: ${file.absolutePath}", it) }
                                .getOrNull()
                            if (bitmap != null) {
                                val imageHeight = (resources.displayMetrics.heightPixels * 0.38f).toInt()
                                addView(ImageView(context).apply {
                                    setImageBitmap(bitmap)
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                }, LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    imageHeight,
                                ).apply { setMargins(0, 0, 0, dp(16)) })
                            }
                        }

                        if (note.isNotBlank()) {
                            addView(TextView(context).apply {
                                text = note
                                textSize = 18f
                                setTextColor(0xFF111111.toInt())
                                gravity = Gravity.CENTER
                                setLineSpacing(dp(2).toFloat(), 1.0f)
                            }, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ))
                        }
                    })
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
                        addView(actionButton("Edit") { editTask() }, weightedButtonParams())
                        addView(actionButton("Snooze") { snooze() }, weightedButtonParams())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, dp(16), 0, dp(8)) })

                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(actionButton("Silence") { silence() }, weightedButtonParams())
                        addView(actionButton("Complete") { complete() }, weightedButtonParams())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ))

                    addView(actionButton("Dismiss") { dismiss() }.apply {
                        backgroundTintList = ColorStateList.valueOf(0xFF2E7655.toInt())
                        setTextColor(0xFFFFFFFF.toInt())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(56),
                    ).apply { setMargins(dp(8), dp(12), dp(8), 0) })
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            },
        )
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

    private fun startReminderAudio(intent: Intent) {
        val audio = intent.getStringExtra(ReminderScheduler.EXTRA_ALERT_AUDIO).takeUnless { it.isNullOrBlank() } ?: return
        ContextCompat.startForegroundService(
            this,
            Intent(this, RichReminderAudioService::class.java)
                .setAction(RichReminderAudioService.ACTION_PLAY)
                .putExtra(RichReminderAudioService.EXTRA_AUDIO_PATH, audio)
                .putExtra(RichReminderAudioService.EXTRA_LOOP, intent.getBooleanExtra(ReminderScheduler.EXTRA_ALERT_AUDIO_LOOP, true)),
        )
    }

    private fun handleDeliveredReminder(intent: Intent) {
        val scheduleId = intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID) ?: return
        Thread {
            val app = applicationContext as TaskNotesApp
            runBlocking(Dispatchers.IO) {
                runCatching { app.reminderScheduler.scheduleNextRepeatIfNeeded(intent) }
                    .onFailure { Log.e(TAG, "Failed to schedule repeat reminder from rich activity", it) }
                runCatching { app.database.scheduledNotificationDao().deleteById(scheduleId) }
                    .onFailure { Log.e(TAG, "Failed to delete delivered rich reminder", it) }
            }
        }.start()
    }

    private fun editTask() {
        stopAudio()
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(ReminderScheduler.ACTION_OPEN_TASK)
            .putExtra(ReminderScheduler.EXTRA_TASK_ID, sourceIntent?.getStringExtra(ReminderScheduler.EXTRA_TASK_ID))
            .putExtra(ReminderScheduler.EXTRA_TASK_PATH, sourceIntent?.getStringExtra(ReminderScheduler.EXTRA_TASK_PATH))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun snooze() {
        sendReminderAction(ReminderScheduler.ACTION_SNOOZE_10)
        dismiss()
    }

    private fun complete() {
        sendReminderAction(ReminderScheduler.ACTION_COMPLETE)
        dismiss()
    }

    private fun silence() {
        stopAudio()
    }

    private fun dismiss() {
        stopAudio()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun stopAudio() {
        startService(Intent(this, RichReminderAudioService::class.java).setAction(RichReminderAudioService.ACTION_STOP))
    }

    private fun sendReminderAction(action: String) {
        sendBroadcast(Intent(this, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderScheduler.EXTRA_TASK_ID, sourceIntent?.getStringExtra(ReminderScheduler.EXTRA_TASK_ID))
            .putExtra(ReminderScheduler.EXTRA_TASK_PATH, sourceIntent?.getStringExtra(ReminderScheduler.EXTRA_TASK_PATH))
            .putExtra(ReminderScheduler.EXTRA_SCHEDULE_ID, sourceIntent?.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID)))
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "RichReminderActivity"
    }
}
