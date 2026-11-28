package com.local.tasknotescompanion.domain

import com.local.tasknotescompanion.MainActivity
import com.local.tasknotescompanion.TaskFilter
import com.local.tasknotescompanion.filterBy
import com.local.tasknotescompanion.data.toEntity
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.notifications.ReminderResolver
import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class PhoneOnlyAlarmTest {
    private fun task() = TaskRecord(
        id = "event.md", path = "event.md", title = "Event", body = "Keep body", status = "open", priority = "normal",
        due = LocalDate.of(2026, 12, 6), dueTime = LocalTime.of(11, 0),
        scheduled = LocalDate.of(2026, 12, 5), scheduledTime = LocalTime.of(10, 0),
        tags = listOf("task"), projects = listOf("Project"), contexts = emptyList(),
        reminders = listOf(ReminderSpec.Relative("before", ReminderAnchor.SCHEDULED, Duration.ofMinutes(-5),
            alert = ReminderAlert(video = "clip.mp4", audio = "ring.mp3"))),
        recurrence = RecurrenceSpec(rrule = "FREQ=WEEKLY"), completedDate = null, modifiedAt = 0,
        frontmatter = TaskFrontmatter(mapOf("customField" to "keep")),
    )

    @Test fun convertingAndRestoringOnlyChangesTheAlarmMarker() {
        val original = task()
        val alarm = original.withPhoneOnlyAlarm(true)
        assertTrue(alarm.isPhoneOnlyAlarm)
        assertEquals(original.copy(frontmatter = alarm.frontmatter), alarm)
        val restored = alarm.withPhoneOnlyAlarm(false)
        assertFalse(restored.isPhoneOnlyAlarm)
        assertEquals(original.copy(frontmatter = restored.frontmatter), restored)
        assertSame(original, original.withPhoneOnlyAlarm(false))
    }

    @Test fun alarmSurvivesRoomCacheAndMarkdownWithoutLosingRecurrenceOrReminders() {
        val root = Files.createTempDirectory("phone-alarm").toFile()
        try {
            val alarm = task().withPhoneOnlyAlarm(true).toEntity().toRecord()
            assertTrue(alarm.isPhoneOnlyAlarm)
            val file = root.resolve("event.md")
            val parser = TaskNoteParser()
            file.writeText(parser.render(alarm, TaskMappingConfig()))
            val saved = parser.parse(file, root, TaskMappingConfig())!!
            assertTrue(saved.isPhoneOnlyAlarm)
            assertEquals(task().scheduled, saved.scheduled)
            assertEquals(task().dueTime, saved.dueTime)
            assertEquals("FREQ=WEEKLY", saved.recurrence!!.rrule)
            assertEquals("ring.mp3", saved.reminders.single().alert!!.audio)
            assertEquals("keep", saved.frontmatter.raw["customField"])
        } finally { root.deleteRecursively() }
    }

    @Test fun phoneRemindersKeepTheirTimesAndMediaAndUseTheAlarmPresentation() {
        val original = task()
        val preferences = NotificationPreferences()
        val normal = ReminderResolver().resolve(original, preferences)
        val alarm = ReminderResolver().resolve(original.withPhoneOnlyAlarm(true), preferences)
        assertEquals(normal.map { it.triggerAt }, alarm.map { it.triggerAt })
        assertTrue(alarm.all { it.alert?.style == "alarm" })
        assertEquals("ring.mp3", alarm.first { it.id == "before" }.alert!!.audio)
        val emptyMedia = original.copy(reminders = emptyList()).withPhoneOnlyAlarm(true)
        assertTrue(ReminderResolver().resolve(emptyMedia, preferences).all { it.alert?.style == "alarm" })
    }

    @Test fun alarmsTabIncludesOnlyActivePhoneAlarms() {
        val normal = task()
        val alarm = task().copy(id = "alarm.md").withPhoneOnlyAlarm(true)
        val completed = alarm.copy(id = "done.md", status = "done")
        assertEquals(listOf("alarm.md"), listOf(normal, alarm, completed).filterBy(TaskFilter.ALARMS, "").map { it.id })
    }
}
