package com.local.tasknotescompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RichReminderDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_RICH_REMINDER) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, RichReminderAudioService::class.java)
                .setAction(RichReminderAudioService.ACTION_SHOW_RICH_REMINDER)
                .putExtras(intent),
        )
    }

    companion object {
        const val ACTION_TEST_RICH_REMINDER = "com.local.tasknotescompanion.TEST_RICH_REMINDER"
    }
}
