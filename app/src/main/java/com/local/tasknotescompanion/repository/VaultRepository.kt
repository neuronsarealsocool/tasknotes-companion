package com.local.tasknotescompanion.repository

import com.local.tasknotescompanion.data.TaskDao
import com.local.tasknotescompanion.data.TaskMappingStore
import com.local.tasknotescompanion.data.TaskEntity
import com.local.tasknotescompanion.data.readCachedTask
import com.local.tasknotescompanion.data.toEntity
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.domain.dismissReminders
import com.local.tasknotescompanion.domain.reminderDismissedThrough
import com.local.tasknotescompanion.domain.withReminderDismissedThrough
import com.local.tasknotescompanion.domain.PhoneOnlyAlarmField
import com.local.tasknotescompanion.domain.ReminderAlert
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.RecurrenceSpec
import com.local.tasknotescompanion.domain.TaskRecord
import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.quickadd.QuickAddDraft
import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime

class VaultRepository(
    private val dao: TaskDao,
    private val mappingStore: TaskMappingStore,
    private val parser: TaskNoteParser = TaskNoteParser(),
) {
    private val writeMutex = Mutex()

    fun observeTasks(): Flow<List<TaskRecord>> = dao.observeAll()
        .map { rows -> rows.mapNotNull { readTask(it) } }
        .flowOn(Dispatchers.IO)

    suspend fun findTask(id: String?, path: String?): TaskRecord? {
        val row = id?.let { dao.findById(it) } ?: path?.let { dao.findByPath(it) } ?: return null
        return withContext(Dispatchers.IO) { readTask(row) }
    }

    private suspend fun readTask(row: TaskEntity): TaskRecord? {
        return try {
            val result = readCachedTask(row, File(mappingStore.vaultPath()), mappingStore.load(), parser)
            if (result.rebuilt) dao.upsert(result.task.toEntity())
            result.task
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("VaultRepository", "Could not rebuild cached task: ${row.path}", failure)
            null
        }
    }

    suspend fun scanVault(): Int = withContext(Dispatchers.IO) {
        val root = File(mappingStore.vaultPath())
        if (!root.isDirectory) return@withContext 0
        val config = mappingStore.load()
        val tasks = root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { parser.parse(file, root, config) }.getOrElse {
                    android.util.Log.w("VaultRepository", "Could not read task note: ${file.absolutePath}", it)
                    null
                }
            }
            .toList()
        if (tasks.isNotEmpty()) {
            dao.upsertAll(tasks.map { it.toEntity() })
            dao.deleteMissing(tasks.map { it.id })
        } else {
            dao.deleteAll()
        }
        tasks.size
    }

    suspend fun save(task: TaskRecord): TaskRecord = withContext(Dispatchers.IO) {
        writeMutex.withLock { saveTask(task) }
    }

    private suspend fun saveTask(task: TaskRecord): TaskRecord {
        val config = mappingStore.load()
        val root = File(mappingStore.vaultPath())
        val oldFile = File(task.path)
        val original = parser.parse(oldFile, root, config)
        val originalTitle = original?.title
        val targetFile = if (task.title == originalTitle) oldFile else parser.uniqueSiblingFile(oldFile, task.title)
        val taskForWrite = task.withReminderDismissedThrough(original?.reminderDismissedThrough ?: 0L).copy(
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
        return parsed
    }

    suspend fun create(title: String, reminders: List<ReminderSpec> = emptyList()): TaskRecord {
        return create(QuickAddDraft(rawText = title, title = title), reminders)
    }

    suspend fun create(draft: QuickAddDraft, reminders: List<ReminderSpec> = emptyList()): TaskRecord = withContext(Dispatchers.IO) {
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
        val taskReminders = (if (draft.suppressDefaultReminders) draft.reminders else reminders + draft.reminders)
            .map { it.withDefaultAlert(draft.alert ?: ReminderAlert(style = "fullscreen")) }
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
            reminders = taskReminders,
            recurrence = draft.recurrenceRule?.let { RecurrenceSpec(rrule = it) },
            completedDate = null,
            modifiedAt = System.currentTimeMillis(),
            frontmatter = TaskFrontmatter(
                buildMap {
                    if (draft.phoneOnlyAlarm) put(PhoneOnlyAlarmField, true)
                    put(config.titleField, title)
                    put(config.statusField, config.openStatus)
                    put(config.priorityField, draft.priority ?: "none")
                    put(config.createdField, now.toString())
                    put("timeEstimate", draft.timeEstimateMinutes ?: 0)
                    put("taskSourceType", "taskNotes")
                    put("created", nowMinute)
                    put("updated", nowMinute)
                    draft.recurrenceRule?.let { put(config.recurrenceField, it) }
                    draft.recurrenceAnchor?.let { put("recurrence_anchor", it) }
                },
            ),
        )
        atomicWrite(file, parser.render(task, config))
        val parsed = parser.parse(file, root, config) ?: task
        dao.upsert(parsed.toEntity())
        parsed
    }

    private fun ReminderSpec.withDefaultAlert(alert: ReminderAlert): ReminderSpec {
        return when (this) {
            is ReminderSpec.Absolute -> copy(alert = this.alert ?: alert)
            is ReminderSpec.Relative -> copy(alert = this.alert ?: alert)
        }
    }

    suspend fun dismissReminder(task: TaskRecord, nowMillis: Long, triggerAtMillis: Long = 0L) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val root = File(mappingStore.vaultPath())
            val latest = parser.parse(File(task.path), root, mappingStore.load()) ?: return@withLock
            saveTask(latest.dismissReminders(nowMillis, triggerAtMillis))
        }
    }

    suspend fun complete(task: TaskRecord, nowMillis: Long = System.currentTimeMillis(), triggerAtMillis: Long = 0L) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val config = mappingStore.load()
            val root = File(mappingStore.vaultPath())
            val series = parser.parse(File(task.path), root, config) ?: dao.findById(task.id)?.toRecord() ?: task
            val completed = parser.complete(series.copy(occurrenceDate = task.occurrenceDate), config, true)
                .dismissReminders(nowMillis, triggerAtMillis)
            saveTask(completed)
            parser.nextOccurrence(completed)?.let { next ->
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
