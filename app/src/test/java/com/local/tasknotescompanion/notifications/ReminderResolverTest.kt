package com.local.tasknotescompanion.notifications

import com.local.tasknotescompanion.domain.NotificationPreferences
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.TaskFrontmatter
import com.local.tasknotescompanion.domain.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderResolverTest {
    private val resolver = ReminderResolver()

    @Test
    fun resolvesRelativeReminderAgainstDueDateOnlyAnchorTime() {
        val task = task(
            due = LocalDate.parse("2026-05-14"),
            reminders = listOf(
                ReminderSpec.Relative("due_minus_15", ReminderAnchor.DUE, Duration.ofMinutes(-15)),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(dateOnlyAnchorTime = LocalTime.of(9, 0), overdueReminderEnabled = false))

        assertEquals(LocalDateTime.parse("2026-05-14T08:45:00"), resolved.single().triggerAt)
    }

    @Test
    fun resolvesRelativeReminderAgainstDueTimeWhenPresent() {
        val task = task(
            due = LocalDate.parse("2026-05-14"),
            dueTime = LocalTime.parse("15:30"),
            reminders = listOf(
                ReminderSpec.Relative("due_minus_15", ReminderAnchor.DUE, Duration.ofMinutes(-15)),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(dateOnlyAnchorTime = LocalTime.of(9, 0), overdueReminderEnabled = false))

        assertEquals(LocalDateTime.parse("2026-05-14T15:15:00"), resolved.single().triggerAt)
    }

    @Test
    fun createsScheduledNotificationWithoutDueDate() {
        val task = task(
            scheduled = LocalDate.parse("2026-05-14"),
            scheduledTime = LocalTime.parse("15:30"),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(dateOnlyAnchorTime = LocalTime.of(9, 0), overdueReminderEnabled = false))

        assertEquals(1, resolved.size)
        assertEquals("scheduled", resolved.single().source)
        assertEquals(LocalDateTime.parse("2026-05-14T15:30:00"), resolved.single().triggerAt)
    }

    @Test
    fun keepsAfterScheduledReminderWhenAnchorHasPassedButReminderIsFuture() {
        val task = task(
            scheduled = LocalDate.parse("2026-05-14"),
            scheduledTime = LocalTime.parse("15:30"),
            reminders = listOf(
                ReminderSpec.Relative("scheduled_plus_14_days", ReminderAnchor.SCHEDULED, Duration.ofDays(14)),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(overdueReminderEnabled = false))
            .single { it.id == "scheduled_plus_14_days" }

        assertEquals(LocalDateTime.parse("2026-05-28T15:30:00"), resolved.triggerAt)
        assertTrue(resolved.isSchedulable(LocalDateTime.parse("2026-05-16T09:00:00")))
    }

    @Test
    fun keepsSoonAfterScheduledReminderWhenEventTimeAlreadyPassed() {
        val task = task(
            scheduled = LocalDate.parse("2026-05-26"),
            scheduledTime = LocalTime.parse("16:00"),
            reminders = listOf(
                ReminderSpec.Relative("scheduled_plus_10_minutes", ReminderAnchor.SCHEDULED, Duration.ofMinutes(10)),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(overdueReminderEnabled = false))
            .single { it.id == "scheduled_plus_10_minutes" }

        assertEquals(LocalDateTime.parse("2026-05-26T16:10:00"), resolved.triggerAt)
        assertTrue(resolved.isSchedulable(LocalDateTime.parse("2026-05-26T16:05:00")))
    }

    @Test
    fun usesTaskNotesReminderTimeForDateOnlyRelativeReminder() {
        val task = task(
            scheduled = LocalDate.parse("2026-05-14"),
            reminders = listOf(
                ReminderSpec.Relative(
                    id = "scheduled_minus_day",
                    relatedTo = ReminderAnchor.SCHEDULED,
                    offset = Duration.ofDays(-1),
                    raw = mapOf("reminderTime" to "10:15"),
                ),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(dateOnlyAnchorTime = LocalTime.of(9, 0), overdueReminderEnabled = false))
            .single { it.id == "scheduled_minus_day" }

        assertEquals(LocalDateTime.parse("2026-05-13T10:15:00"), resolved.triggerAt)
    }

    @Test
    fun carriesRepeatingReminderMetadata() {
        val task = task(
            scheduled = LocalDate.parse("2026-05-27"),
            scheduledTime = LocalTime.parse("10:00"),
            reminders = listOf(
                ReminderSpec.Relative(
                    id = "repeat_every_10",
                    relatedTo = ReminderAnchor.SCHEDULED,
                    offset = Duration.ZERO,
                    repeatEvery = Duration.ofMinutes(10),
                    repeatUntilCompleted = true,
                ),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(overdueReminderEnabled = false))
            .single { it.id == "repeat_every_10" }

        assertEquals(Duration.ofMinutes(10), resolved.repeatEvery)
        assertTrue(resolved.repeatUntilCompleted)
    }

    @Test
    fun treatsJustPassedZeroMinuteReminderAsSchedulable() {
        val task = task(
            due = LocalDate.parse("2026-05-27"),
            dueTime = LocalTime.parse("10:00"),
            reminders = listOf(
                ReminderSpec.Relative("due_at_time", ReminderAnchor.DUE, Duration.ZERO),
            ),
        )

        val resolved = resolver.resolve(task, NotificationPreferences(overdueReminderEnabled = false))
            .single { it.id == "due_at_time" }

        assertTrue(resolved.isSchedulable(LocalDateTime.parse("2026-05-27T10:00:05")))
    }

    @Test
    fun skipsCompletedTasks() {
        val task = task(
            completedDate = LocalDateTime.parse("2026-05-13T12:00:00"),
            reminders = listOf(ReminderSpec.Absolute("call", LocalDateTime.parse("2026-05-14T09:00:00"))),
        )

        assertTrue(resolver.resolve(task, NotificationPreferences()).isEmpty())
    }

    private fun task(
        due: LocalDate? = null,
        dueTime: LocalTime? = null,
        scheduled: LocalDate? = null,
        scheduledTime: LocalTime? = null,
        reminders: List<ReminderSpec> = emptyList(),
        completedDate: LocalDateTime? = null,
    ) = TaskRecord(
        id = "task.md",
        path = "/vault/task.md",
        title = "Task",
        body = "",
        status = "todo",
        priority = null,
        due = due,
        dueTime = dueTime,
        scheduled = scheduled,
        scheduledTime = scheduledTime,
        tags = listOf("task"),
        projects = emptyList(),
        contexts = emptyList(),
        reminders = reminders,
        recurrence = null,
        completedDate = completedDate,
        modifiedAt = 0,
        frontmatter = TaskFrontmatter(emptyMap()),
    )
}
