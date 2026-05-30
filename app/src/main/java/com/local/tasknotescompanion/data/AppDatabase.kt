package com.local.tasknotescompanion.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TaskEntity::class, ScheduledNotificationEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun scheduledNotificationDao(): ScheduledNotificationDao
}
