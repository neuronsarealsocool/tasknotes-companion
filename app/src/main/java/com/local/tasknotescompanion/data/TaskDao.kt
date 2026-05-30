package com.local.tasknotescompanion.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY COALESCE(due, scheduled, '9999-12-31'), title COLLATE NOCASE")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY COALESCE(due, scheduled, '9999-12-31'), title COLLATE NOCASE")
    suspend fun snapshot(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun findById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE path = :path")
    suspend fun findByPath(path: String): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE path NOT IN (:paths)")
    suspend fun deleteMissing(paths: List<String>)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
