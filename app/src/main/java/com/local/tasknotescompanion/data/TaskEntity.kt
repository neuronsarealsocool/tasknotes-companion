package com.local.tasknotescompanion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val path: String,
    val title: String,
    val body: String,
    val status: String,
    val priority: String?,
    val due: String?,
    val dueTime: String?,
    val scheduled: String?,
    val scheduledTime: String?,
    val tags: String,
    val projects: String,
    val contexts: String,
    val reminders: String,
    val recurrence: String?,
    val completedDate: String?,
    val modifiedAt: Long,
    val rawFrontmatter: String,
)
