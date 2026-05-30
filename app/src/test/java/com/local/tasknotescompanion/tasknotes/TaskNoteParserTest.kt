package com.local.tasknotescompanion.tasknotes

import com.local.tasknotescompanion.domain.DetectionMode
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.TaskMappingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

class TaskNoteParserTest {
    private val parser = TaskNoteParser()

    @Test
    fun parsesTaskNoteAndBody() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "task.md")
        file.writeText(
            """
            ---
            title: Call Alex
            status: todo
            due: 2026-05-13
            tags:
              - task
              - phone
            reminders:
              - 2026-05-13T09:00:00
            ---

            Bring the notes.
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())

        assertNotNull(task)
        assertEquals("Call Alex", task!!.title)
        assertEquals("Bring the notes.", task.body.trim())
        assertEquals("2026-05-13", task.due.toString())
        assertEquals(1, task.reminders.size)
    }

    @Test
    fun preservesUnknownFrontmatterOnRender() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "task.md")
        file.writeText(
            """
            ---
            title: Original
            status: todo
            tags: [task]
            customField: keep-me
            ---

            Body
            """.trimIndent(),
        )
        val config = TaskMappingConfig()
        val task = parser.parse(file, root, config)!!.copy(title = "Updated")

        val rendered = parser.render(task, config)

        assertTrue(rendered.contains("customField: keep-me"))
        assertTrue(rendered.contains("title: Updated"))
    }

    @Test
    fun addsDefaultTimeEstimateOnRender() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "task.md")
        file.writeText(
            """
            ---
            title: Original
            status: todo
            tags: [task]
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val rendered = parser.render(task, TaskMappingConfig())

        assertTrue(rendered.contains("timeEstimate: 0"))
        assertTrue(rendered.contains("taskSourceType: taskNotes"))
        assertTrue(rendered.contains("priority: none"))
    }

    @Test
    fun detectsPropertyAndFolderModes() {
        val root = Files.createTempDirectory("vault").toFile()
        val folder = File(root, "Tasks").apply { mkdirs() }
        val propertyFile = File(root, "property.md")
        propertyFile.writeText("---\ntitle: Property\nisTask: true\n---\n")
        val folderFile = File(folder, "folder.md")
        folderFile.writeText("---\ntitle: Folder\n---\n")

        val byProperty = parser.parse(propertyFile, root, TaskMappingConfig(detectionMode = DetectionMode.PROPERTY))
        val byFolder = parser.parse(folderFile, root, TaskMappingConfig(detectionMode = DetectionMode.FOLDER, taskFolder = "Tasks"))

        assertNotNull(byProperty)
        assertNotNull(byFolder)
    }

    @Test
    fun calculatesNextRecurrence() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "repeat.md")
        file.writeText(
            """
            ---
            title: Water plants
            status: todo
            tags: [task]
            due: 2026-05-12
            recurrence: every 2 weeks
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val next = parser.nextOccurrence(task)

        assertEquals("2026-05-26", next!!.due.toString())
    }

    @Test
    fun parsesTaskNotesAbsoluteAndRelativeReminders() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "reminders.md")
        file.writeText(
            """
            ---
            title: Reminders
            status: todo
            due: 2026-05-14
            tags: [task]
            reminders:
              - id: call_now
                type: absolute
                dateTime: 2026-05-13T16:30:00
                description: Call
              - id: due_minus_15
                type: relative
                relatedTo: due
                offset: -PT15M
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val rendered = parser.render(task, TaskMappingConfig())

        assertEquals(2, task.reminders.size)
        assertTrue(rendered.contains("type: absolute"))
        assertTrue(rendered.contains("dateTime: 2026-05-13T16:30"))
        assertTrue(rendered.contains("type: relative"))
        assertTrue(rendered.contains("offset: -PT15M"))
    }

    @Test
    fun parsesAndRendersDueAndScheduledTimes() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "timed.md")
        file.writeText(
            """
            ---
            title: Timed
            status: todo
            tags: [task]
            due: 2026-05-14T10:30:00
            scheduled: 2026-05-13T14:45:00
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val renderedWithTime = parser.render(task, TaskMappingConfig())
        val renderedDateOnly = parser.render(task.copy(dueTime = null), TaskMappingConfig())
        val roundTripped = parser.parse(writeRendered(root, "roundtrip.md", renderedWithTime), root, TaskMappingConfig())!!

        assertEquals("2026-05-14", task.due.toString())
        assertEquals("10:30", task.dueTime.toString())
        assertEquals("2026-05-13", task.scheduled.toString())
        assertEquals("14:45", task.scheduledTime.toString())
        assertEquals("14:45", roundTripped.scheduledTime.toString())
        assertTrue(renderedWithTime.contains("due:"))
        assertTrue(renderedWithTime.contains("T"))
        assertTrue(renderedWithTime.contains("scheduled: 2026-05-13T14:45"))
        assertTrue(renderedDateOnly.contains("due: 2026-05-14"))
    }

    private fun writeRendered(root: File, name: String, text: String): File {
        val file = File(root, name)
        file.writeText(text)
        return file
    }

    @Test
    fun parsesFrontmatterWithUtf8Bom() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "bom.md")
        file.writeText("\uFEFF---\ntitle: BOM\nstatus: todo\ntags: [task]\n---\n")

        val task = parser.parse(file, root, TaskMappingConfig())

        assertNotNull(task)
        assertEquals("BOM", task!!.title)
    }

    @Test
    fun rendersWholeDayReminderOffsetsLikeTaskNotes() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "reminders-day.md")
        file.writeText(
            """
            ---
            title: Reminders
            status: open
            scheduled: 2026-05-14
            tags: [task]
            reminders:
              - id: scheduled_minus_day
                type: relative
                relatedTo: scheduled
                offset: -PT24H
              - id: scheduled_now
                type: relative
                relatedTo: scheduled
                offset: PT0S
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val rendered = parser.render(task, TaskMappingConfig())

        assertTrue(rendered.contains("offset: -P1D"))
        assertTrue(rendered.contains("offset: PT0M"))
    }

    @Test
    fun parsesAndRendersRepeatingReminderExtension() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "repeat-reminder.md")
        file.writeText(
            """
            ---
            title: Repeat reminder
            status: open
            scheduled: 2026-05-27T10:00
            tags: [task]
            reminders:
              - id: nag_until_done
                type: relative
                relatedTo: scheduled
                offset: PT0M
                repeatEvery: PT10M
                repeatUntil: completed
                description: Every 10 minutes until complete
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val reminder = task.reminders.single() as ReminderSpec.Relative
        val rendered = parser.render(task, TaskMappingConfig())

        assertEquals(Duration.ofMinutes(10), reminder.repeatEvery)
        assertTrue(reminder.repeatUntilCompleted)
        assertTrue(rendered.contains("repeatEvery: PT10M"))
        assertTrue(rendered.contains("repeatUntil: completed"))
    }

    @Test
    fun parsesAndRendersRichReminderAlertExtension() {
        val root = Files.createTempDirectory("vault").toFile()
        val file = File(root, "rich-reminder.md")
        file.writeText(
            """
            ---
            title: Rich reminder
            status: open
            scheduled: 2026-05-27T10:00
            tags: [task]
            reminders:
              - id: rich
                type: relative
                relatedTo: scheduled
                offset: PT0M
                alert:
                  style: fullscreen
                  note: Soy milk preferably
                  image: TaskNotes Companion/Media/milk.jpg
                  audio: TaskNotes Companion/Media/hangouts.mp3
                  audioLoop: true
                  audioUntil: dismiss
                  allowOverlay: true
                  futureField: kept
            ---
            """.trimIndent(),
        )

        val task = parser.parse(file, root, TaskMappingConfig())!!
        val alert = task.reminders.single().alert!!
        val rendered = parser.render(task, TaskMappingConfig())

        assertEquals("Soy milk preferably", alert.note)
        assertEquals("TaskNotes Companion/Media/milk.jpg", alert.image)
        assertEquals("TaskNotes Companion/Media/hangouts.mp3", alert.audio)
        assertTrue(alert.allowOverlay)
        assertTrue(rendered.contains("futureField: kept"))
        assertTrue(rendered.contains("audioUntil: dismiss"))
    }

    @Test
    fun createsFileNameFromTaskTitle() {
        assertEquals("call-alex.md", parser.fileNameForTitle("Call Alex"))
        assertEquals("follow-up-2.md", parser.fileNameForTitle("Follow-up #2"))
    }

    @Test
    fun createsUniqueSiblingFileWhenTitleCollides() {
        val root = Files.createTempDirectory("vault").toFile()
        val current = File(root, "old.md").apply { writeText("") }
        File(root, "new-title.md").writeText("")

        val target = parser.uniqueSiblingFile(current, "New Title")

        assertEquals("new-title-2.md", target.name)
    }
}
