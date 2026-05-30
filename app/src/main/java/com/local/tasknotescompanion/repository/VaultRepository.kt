package com.local.tasknotescompanion.repository

import com.local.tasknotescompanion.data.TaskDao
import com.local.tasknotescompanion.data.TaskMappingStore
import com.local.tasknotescompanion.data.toEntity
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.domain.TaskRecord
import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.quickadd.QuickAddDraft
import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime

class VaultRepository(
    private val dao: TaskDao,
    private val mappingStore: TaskMappingStore,
    private val parser: TaskNoteParser = TaskNoteParser(),
) {
    fun observeTasks(): Flow<List<TaskRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    suspend fun scanVault(): Int = withContext(Dispatchers.IO) {
        val root = File(mappingStore.vaultPath())
        if (!root.isDirectory) return@withContext 0
        val config = mappingStore.load()
        val tasks = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .mapNotNull { parser.parse(it, root, config) }
            .toList()
        if (tasks.isNotEmpty()) {
            dao.upsertAll(tasks.map { it.toEntity() })
            dao.deleteMissing(tasks.map { it.path })
        } else {
            dao.deleteAll()
        }
        tasks.size
    }

    suspend fun save(task: TaskRecord): TaskRecord = withContext(Dispatchers.IO) {
        val config = mappingStore.load()
        val root = File(mappingStore.vaultPath())
        val oldFile = File(task.path)
        val targetFile = parser.uniqueSiblingFile(oldFile, task.title)
        val taskForWrite = task.copy(
            id = targetFile.relativeTo(root).path.replace('\\', '/'),
            path = targetFile.absolutePath,
        )
        atomicWrite(targetFile, parser.render(taskForWrite, config))
        if (!targetFile.absolutePath.equals(oldFile.absolutePath, ignoreCase = true) && oldFile.exists()) {
            oldFile.delete()
            dao.deleteById(task.id)
        }
        val parsed = parser.parse(targetFile, root, config)!!
        dao.upsert(parsed.toEntity())
        parsed
    }

    suspend fun create(title: String, reminders: List<com.local.tasknotescompanion.domain.ReminderSpec> = emptyList()): TaskRecord {
        return create(QuickAddDraft(rawText = title, title = title), reminders)
    }

    suspend fun create(draft: QuickAddDraft, reminders: List<com.local.tasknotescompanion.domain.ReminderSpec> = emptyList()): TaskRecord = withContext(Dispatchers.IO) {
        val root = File(mappingStore.vaultPath())
        val config = mappingStore.load()
        val title = draft.title.trim().ifBlank { "New task" }
        val file = parser.createNewFile(root, config, title)
        val now = OffsetDateTime.now()
        val nowMinute = now.toLocalDateTime().withSecond(0).withNano(0).toString()
        val tags = ((if (config.detectionMode.name == "TAG") listOf(config.detectionTag) else emptyList()) + draft.tags)
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .distinct()
        val task = TaskRecord(
            id = file.relativeTo(root).path.replace('\\', '/'),
            path = file.absolutePath,
            title = title,
            body = "",
            status = config.openStatus,
            priority = draft.priority ?: "none",
            due = draft.due,
            dueTime = draft.dueTime,
            scheduled = draft.scheduled,
            scheduledTime = draft.scheduledTime,
            tags = tags,
            projects = draft.projects.distinct(),
            contexts = draft.contexts.distinct(),
            reminders = reminders,
            recurrence = null,
            completedDate = null,
            modifiedAt = System.currentTimeMillis(),
            frontmatter = TaskFrontmatter(
                mapOf(
                    config.titleField to title,
                    config.statusField to config.openStatus,
                    config.priorityField to (draft.priority ?: "none"),
                    config.createdField to now.toString(),
                    "timeEstimate" to (draft.timeEstimateMinutes ?: 0),
                    "taskSourceType" to "taskNotes",
                    "created" to nowMinute,
                    "updated" to nowMinute,
                ),
            ),
        )
        atomicWrite(file, parser.render(task, config))
        val parsed = parser.parse(file, root, config) ?: task
        dao.upsert(parsed.toEntity())
        parsed
    }

    suspend fun complete(task: TaskRecord) = withContext(Dispatchers.IO) {
        val config = mappingStore.load()
        val completed = parser.complete(task, config, true)
        save(completed)
        parser.nextOccurrence(completed)?.let { next ->
            val root = File(mappingStore.vaultPath())
            val nextFile = parser.createNewFile(root, config, next.title)
            val nextTask = next.copy(
                id = nextFile.relativeTo(root).path.replace('\\', '/'),
                path = nextFile.absolutePath,
                status = config.openStatus,
            )
            atomicWrite(nextFile, parser.render(nextTask, config))
            parser.parse(nextFile, root, config)?.let { dao.upsert(it.toEntity()) }
        }
    }

    suspend fun delete(task: TaskRecord) = withContext(Dispatchers.IO) {
        val file = File(task.path)
        if (file.exists()) {
            check(file.delete()) { "Could not delete ${file.absolutePath}" }
        }
        dao.deleteById(task.id)
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}
