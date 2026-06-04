package com.local.tasknotescompanion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_notifications")
data class ScheduledNotificationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val taskPath: String,
    val taskTitle: String,
    val reminderId: String,
    val triggerAtMillis: Long,
    val kind: String,
    val description: String?,
    val repeatEveryMillis: Long?,
    val repeatUntilCompleted: Boolean,
    val repeatWindows: String?,
    val alertStyle: String?,
    val alertNote: String?,
    val alertImage: String?,
    val alertVideo: String?,
    val alertAudio: String?,
    val alertAudioLoop: Boolean,
    val alertAudioUntil: String?,
    val alertAllowOverlay: Boolean,
)
