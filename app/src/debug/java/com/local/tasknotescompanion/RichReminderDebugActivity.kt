package com.local.tasknotescompanion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class RichReminderDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, RichReminderAudioService::class.java)
                .setAction(RichReminderAudioService.ACTION_SHOW_RICH_REMINDER)
                .putExtras(intent),
        )
        finish()
    }
}
