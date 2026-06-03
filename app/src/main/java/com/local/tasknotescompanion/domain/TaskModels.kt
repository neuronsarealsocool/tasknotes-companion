package com.local.tasknotescompanion.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Duration

enum class DetectionMode { TAG, PROPERTY, FOLDER }

data class TaskMappingConfig(
    val detectionMode: DetectionMode = DetectionMode.TAG,
    val detectionTag: String = "task",
    val detectionProperty: String = "isTask",
    val detectionPropertyValue: String = "true",
    val taskFolder: String = "",
    val titleField: String = "title",
    val statusField: String = "status",
    val priorityField: String = "priority",
    val dueField: String = "due",
    val scheduledField: String = "scheduled",
    val contextsField: String = "contexts",
    val projectsField: String = "projects",
    val tagsField: String = "tags",
    val recurrenceField: String = "recurrence",
    val remindersField: String = "reminders",
    val createdField: String = "dateCreated",
    val modifiedField: String = "dateModified",
    val completedField: String = "completedDate",
    val openStatus: String = "open",
    val doneStatus: String = "done",
)

data class TaskFrontmatter(
    val raw: Map<String, Any?>,
)

sealed interface ReminderSpec {
    val id: String
    val description: String?
    val raw: Map<String, Any?>
    val repeatEvery: Duration?
    val repeatUntilCompleted: Boolean
    val alert: ReminderAlert?

    data class Absolute(
        override val id: String,
        val absoluteTime: LocalDateTime,
        override val description: String? = null,
        override val raw: Map<String, Any?> = emptyMap(),
        override val repeatEvery: Duration? = null,
        override val repeatUntilCompleted: Boolean = false,
        override val alert: ReminderAlert? = null,
    ) : ReminderSpec

    data class Relative(
        override val id: String,
        val relatedTo: ReminderAnchor,
        val offset: Duration,
        override val description: String? = null,
        override val raw: Map<String, Any?> = emptyMap(),
        override val repeatEvery: Duration? = null,
        override val repeatUntilCompleted: Boolean = false,
        override val alert: ReminderAlert? = null,
    ) : ReminderSpec
}

enum class ReminderAnchor { DUE, SCHEDULED }

data class ReminderAlert(
    val style: String = "fullscreen",
    val note: String? = null,
    val image: String? = null,
    val video: String? = null,
    val audio: String? = null,
    val audioLoop: Boolean = true,
    val audioUntil: String = "dismiss",
    val allowOverlay: Boolean = false,
    val raw: Map<String, Any?> = emptyMap(),
)

data class ResolvedReminder(
    val id: String,
    val taskId: String,
    val taskPath: String,
    val taskTitle: String,
    val triggerAt: LocalDateTime,
    val description: String?,
    val source: String,
    val repeatEvery: Duration? = null,
    val repeatUntilCompleted: Boolean = false,
    val alert: ReminderAlert? = null,
)

data class SnoozeRecord(
    val id: String,
    val taskId: String,
    val triggerAt: LocalDateTime,
    val label: String,
)

data class NotificationPreferences(
    val remindersEnabled: Boolean = true,
    val dateOnlyAnchorTime: LocalTime = LocalTime.of(10, 15),
    val defaultReminders: List<ReminderSpec.Relative> = emptyList(),
    val dailySummaryEnabled: Boolean = true,
    val dailySummaryTime: LocalTime = LocalTime.of(8, 0),
    val overdueReminderEnabled: Boolean = true,
    val badgeEnabled: Boolean = true,
)

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

data class RecurrenceSpec(
    val interval: Int = 1,
    val unit: RecurrenceUnit = RecurrenceUnit.DAY,
)

data class ValidationIssue(
    val field: String,
    val message: String,
)

data class TaskRecord(
    val id: String,
    val path: String,
    val title: String,
    val body: String,
    val status: String,
    val priority: String?,
    val due: LocalDate?,
    val dueTime: LocalTime?,
    val scheduled: LocalDate?,
    val scheduledTime: LocalTime?,
    val tags: List<String>,
    val projects: List<String>,
    val contexts: List<String>,
    val reminders: List<ReminderSpec>,
    val recurrence: RecurrenceSpec?,
    val completedDate: LocalDateTime?,
    val modifiedAt: Long,
    val frontmatter: TaskFrontmatter,
) {
    val isDone: Boolean
        get() = completedDate != null || status.equals("done", ignoreCase = true)
}
