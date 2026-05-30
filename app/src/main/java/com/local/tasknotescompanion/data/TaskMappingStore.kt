package com.local.tasknotescompanion.data

import android.content.Context
import com.local.tasknotescompanion.domain.DetectionMode
import com.local.tasknotescompanion.domain.TaskMappingConfig

class TaskMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("tasknotes_settings", Context.MODE_PRIVATE)

    fun load(): TaskMappingConfig = TaskMappingConfig(
        detectionMode = DetectionMode.valueOf(prefs.getString("detectionMode", DetectionMode.TAG.name)!!),
        detectionTag = prefs.getString("detectionTag", "task")!!,
        detectionProperty = prefs.getString("detectionProperty", "isTask")!!,
        detectionPropertyValue = prefs.getString("detectionPropertyValue", "true")!!,
        taskFolder = prefs.getString("taskFolder", "")!!,
        titleField = prefs.getString("titleField", "title")!!,
        statusField = prefs.getString("statusField", "status")!!,
        priorityField = prefs.getString("priorityField", "priority")!!,
        dueField = prefs.getString("dueField", "due")!!,
        scheduledField = prefs.getString("scheduledField", "scheduled")!!,
        contextsField = prefs.getString("contextsField", "contexts")!!,
        projectsField = prefs.getString("projectsField", "projects")!!,
        tagsField = prefs.getString("tagsField", "tags")!!,
        recurrenceField = prefs.getString("recurrenceField", "recurrence")!!,
        remindersField = prefs.getString("remindersField", "reminders")!!,
        createdField = prefs.getString("createdField", "dateCreated")!!,
        modifiedField = prefs.getString("modifiedField", "dateModified")!!,
        completedField = prefs.getString("completedField", "completedDate")!!,
        openStatus = prefs.getString("openStatus", "open")!!,
        doneStatus = prefs.getString("doneStatus", "done")!!,
    )

    fun save(config: TaskMappingConfig) {
        prefs.edit()
            .putString("detectionMode", config.detectionMode.name)
            .putString("detectionTag", config.detectionTag)
            .putString("detectionProperty", config.detectionProperty)
            .putString("detectionPropertyValue", config.detectionPropertyValue)
            .putString("taskFolder", config.taskFolder)
            .putString("titleField", config.titleField)
            .putString("statusField", config.statusField)
            .putString("priorityField", config.priorityField)
            .putString("dueField", config.dueField)
            .putString("scheduledField", config.scheduledField)
            .putString("contextsField", config.contextsField)
            .putString("projectsField", config.projectsField)
            .putString("tagsField", config.tagsField)
            .putString("recurrenceField", config.recurrenceField)
            .putString("remindersField", config.remindersField)
            .putString("createdField", config.createdField)
            .putString("modifiedField", config.modifiedField)
            .putString("completedField", config.completedField)
            .putString("openStatus", config.openStatus)
            .putString("doneStatus", config.doneStatus)
            .apply()
    }

    fun vaultPath(): String = prefs.getString("vaultPath", "")!!

    fun saveVaultPath(path: String) {
        prefs.edit().putString("vaultPath", path).apply()
    }
}
