package com.local.tasknotescompanion.data

import android.content.Context
import androidx.core.content.edit
import com.local.tasknotescompanion.domain.TaskPriority
import com.local.tasknotescompanion.domain.decodePriorities
import com.local.tasknotescompanion.domain.encodePriorities
import com.local.tasknotescompanion.domain.mergePriorities
import java.io.File

class TaskPriorityStore(context: Context) {
    private val prefs = context.getSharedPreferences("task_priority_options", Context.MODE_PRIVATE)
    private fun key(vaultPath: String) = "custom:${vaultPath.trimEnd('/', '\\')}"

    fun load(vaultPath: String): List<TaskPriority> {
        val imported = if (vaultPath.isBlank()) emptyList() else runCatching {
            decodePriorities(File(vaultPath, ".obsidian/plugins/tasknotes/data.json").readText())
        }.getOrDefault(emptyList())
        return mergePriorities(imported, decodePriorities(prefs.getString(key(vaultPath), "").orEmpty()))
    }

    fun add(vaultPath: String, priority: TaskPriority) {
        val custom = decodePriorities(prefs.getString(key(vaultPath), "").orEmpty())
        prefs.edit { putString(key(vaultPath), encodePriorities(custom.filterNot { it.value == priority.value } + priority)) }
    }
}
