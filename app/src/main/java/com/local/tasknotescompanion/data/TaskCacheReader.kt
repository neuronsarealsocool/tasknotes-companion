package com.local.tasknotescompanion.data

import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.domain.TaskRecord
import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import java.io.File

internal data class TaskCacheRead(val task: TaskRecord, val rebuilt: Boolean)

/** Cached YAML is disposable; the original Markdown remains authoritative. */
internal fun readCachedTask(
    row: TaskEntity,
    root: File,
    config: TaskMappingConfig,
    parser: TaskNoteParser,
): TaskCacheRead {
    return try {
        TaskCacheRead(row.toRecord(), rebuilt = false)
    } catch (cacheFailure: Exception) {
        val original = parser.parse(File(row.path), root, config)
            ?: throw IllegalStateException("Cached task could not be rebuilt from its note", cacheFailure)
        TaskCacheRead(original.copy(id = row.id), rebuilt = true)
    }
}
