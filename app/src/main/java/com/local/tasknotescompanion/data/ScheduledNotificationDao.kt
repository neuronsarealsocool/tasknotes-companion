package com.local.tasknotescompanion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledNotificationDao {
    @Query("SELECT * FROM scheduled_notifications")
    suspend fun all(): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE taskId = :taskId")
    suspend fun byTask(taskId: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE id = :id")
    suspend fun findById(id: String): ScheduledNotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ScheduledNotificationEntity>)

    @Query("DELETE FROM scheduled_notifications WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("DELETE FROM scheduled_notifications WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_notifications")
    suspend fun deleteAll()
}
