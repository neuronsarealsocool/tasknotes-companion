package com.local.tasknotescompanion

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.local.tasknotescompanion.domain.reminderWebLinks
import com.local.tasknotescompanion.notifications.ReminderScheduler

fun Context.cancelRichReminderNotification(source: Intent?) {
    val scheduleId = source?.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_ID) ?: "rich-reminder"
    NotificationManagerCompat.from(this).cancel(scheduleId.hashCode().takeUnless { it == 0 } ?: 710102)
}

fun TextView.setReminderWebLinks(value: String, onOpened: () -> Unit) {
    val linkedText = SpannableString(value)
    for (link in reminderWebLinks(value)) {
        linkedText.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onSuccess { onOpened() }.onFailure {
                    Toast.makeText(context, "Could not open this web address", Toast.LENGTH_SHORT).show()
                }
            }
        }, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    text = linkedText
    setLinkTextColor(0xFF145EA8.toInt())
    movementMethod = LinkMovementMethod.getInstance()
}
