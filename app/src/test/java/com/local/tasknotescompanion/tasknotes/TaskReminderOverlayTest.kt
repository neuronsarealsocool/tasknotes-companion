package com.local.tasknotescompanion.tasknotes

import com.local.tasknotescompanion.data.toEntity
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.domain.*
import com.local.tasknotescompanion.notifications.ReminderResolver
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class TaskReminderOverlayTest {
    private fun task() = TaskRecord(
        id = "note.md", path = "note.md", title = "Existing task", body = "Keep this body.",
        status = "open", priority = "none", due = null, dueTime = null,
        scheduled = LocalDate.of(2026, 12, 5), scheduledTime = LocalTime.of(10, 0),
        tags = listOf("task"), projects = emptyList(), contexts = emptyList(), reminders = emptyList(),
        recurrence = null, completedDate = null, modifiedAt = 0,
        frontmatter = TaskFrontmatter(mapOf("customField" to "keep-me")),
    )

    @Test fun sharedNoteEditKeepsReminderTimesIdsAndIndividualMediaOverrides() {
        val first = ReminderSpec.Relative("before", ReminderAnchor.SCHEDULED, Duration.ofMinutes(-5),
            alert = ReminderAlert(note = "Old", image = "first.png", raw = mapOf("customAlert" to "keep")))
        val second = ReminderSpec.Absolute("absolute", task().scheduled!!.atTime(9, 0),
            alert = ReminderAlert(note = "Different", image = "second.png"))
        val edited = listOf(first, second).editSharedOverlay(first.alert!!, first.alert.copy(note = "Updated"))
        assertEquals(listOf("before", "absolute"), edited.map { it.id })
        assertEquals(listOf("Updated", "Updated"), edited.map { it.alert!!.note })
        assertEquals(listOf("first.png", "second.png"), edited.map { it.alert!!.image })
        assertEquals(first.offset, (edited.first() as ReminderSpec.Relative).offset)
        assertEquals(second.absoluteTime, (edited.last() as ReminderSpec.Absolute).absoluteTime)
        assertEquals("keep", edited.first().alert!!.raw["customAlert"])
    }

    @Test fun removingSharedVideoClearsEveryReminderWithoutRemovingPhotos() {
        val alert = ReminderAlert(video = "first.mp4", image = "first.png")
        val reminders = listOf(
            ReminderSpec.Relative("one", ReminderAnchor.SCHEDULED, Duration.ZERO, alert = alert),
            ReminderSpec.Relative("two", ReminderAnchor.SCHEDULED, Duration.ofMinutes(-5), alert = alert.copy(video = "other.mp4")),
        )
        val edited = reminders.editSharedOverlay(alert, alert.copy(video = null))
        assertTrue(edited.all { it.alert!!.video == null })
        assertTrue(edited.all { it.alert!!.image == "first.png" })
    }

    @Test fun overlayPersistsWithoutExplicitRemindersAndIsUsedForScheduledNotification() {
        val overlay = ReminderAlert(note = "Shared note", video = "clip.mp4", audio = "sound.mp3", audioLoop = false)
        val stored = task().withReminderOverlay(overlay).toEntity().toRecord()
        assertEquals(overlay.note, stored.editorReminderOverlay().note)
        assertEquals(false, stored.savedReminderOverlay()!!.audioLoop)
        assertEquals("keep-me", stored.frontmatter.raw["customField"])
        val resolved = ReminderResolver().resolve(stored, NotificationPreferences(overdueReminderEnabled = false)).single()
        assertEquals("Shared note", resolved.alert!!.note)
        assertEquals("clip.mp4", resolved.alert.video)
    }

    @Test fun markdownRoundTripKeepsSharedOverlayAndUnrelatedFields() {
        val root = Files.createTempDirectory("editor-overlay").toFile()
        try {
            val file = File(root, "note.md")
            val parser = TaskNoteParser()
            val edited = task().withReminderOverlay(ReminderAlert(note = "Edited", image = "photo.png", video = null))
            file.writeText(parser.render(edited, TaskMappingConfig()))
            val restored = parser.parse(file, root, TaskMappingConfig())!!
            assertEquals("Edited", restored.savedReminderOverlay()!!.note)
            assertEquals("photo.png", restored.savedReminderOverlay()!!.image)
            assertEquals("Keep this body.", restored.body)
            assertEquals("keep-me", restored.frontmatter.raw["customField"])
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitReminderOverlayWinsOverTheSavedSharedDefault() {
        val individual = ReminderAlert(note = "Individual override")
        val edited = task().copy(reminders = listOf(
            ReminderSpec.Relative("before", ReminderAnchor.SCHEDULED, Duration.ofMinutes(-5), alert = individual),
        )).withReminderOverlay(ReminderAlert(note = "Shared default"))
        val resolved = ReminderResolver().resolve(edited, NotificationPreferences())
        assertEquals("Individual override", resolved.first { it.id == "before" }.alert!!.note)
        assertEquals("Shared default", resolved.first { it.source == "scheduled" }.alert!!.note)
    }
}
