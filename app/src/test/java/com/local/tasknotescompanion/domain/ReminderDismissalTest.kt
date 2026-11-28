package com.local.tasknotescompanion.domain

import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReminderDismissalTest {
    private fun withTask(check: (TaskRecord, TaskNoteParser, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("reminder-dismissal").toFile()
        try {
            val parser = TaskNoteParser()
            val file = root.resolve("event.md")
            file.writeText("---\ntitle: Event\nstatus: open\ntags: [task]\nscheduled: 2026-12-05T10:00\nrecurrence: FREQ=DAILY\ncustom: preserve\n---\nKeep body\n")
            check(parser.parse(file, root, TaskMappingConfig())!!, parser, root)
        } finally { root.deleteRecursively() }
    }

    @Test fun dismissalOnlyAddsMetadataAndKeepsFutureRecurrence() = withTask { task, _, _ ->
        val dismissed = task.dismissReminders(1000, 900)
        assertEquals(1000, dismissed.reminderDismissedThrough)
        assertEquals(task.copy(frontmatter = dismissed.frontmatter), dismissed)
        assertEquals("open", dismissed.status)
        assertEquals("FREQ=DAILY", dismissed.recurrence?.rrule)
    }

    @Test fun dismissalNeverMovesBackwardAndCoversTheDeliveredTrigger() = withTask { task, _, _ ->
        val dismissed = task.dismissReminders(1000, 1100)
        assertEquals(1100, dismissed.reminderDismissedThrough)
        assertEquals(1100, dismissed.dismissReminders(900, 800).reminderDismissedThrough)
        assertSame(task, task.withReminderDismissedThrough(0))
    }

    @Test fun completionAndMarkdownRoundTripKeepDismissalAndRecurrence() = withTask { task, parser, root ->
        val completed = parser.complete(task, TaskMappingConfig(), true).dismissReminders(1000)
        val file = root.resolve("completed.md")
        file.writeText(parser.render(completed, TaskMappingConfig()))
        val saved = parser.parse(file, root, TaskMappingConfig())!!
        assertEquals(1000, saved.reminderDismissedThrough)
        assertEquals("FREQ=DAILY", saved.recurrence?.rrule)
        assertEquals("preserve", saved.frontmatter.raw["custom"])
        assertTrue(saved.body.contains("Keep body"))
    }

    @Test fun remoteDismissalClosesCurrentDeliveryButNotTheNextOne() = withTask { task, _, _ ->
        val dismissed = task.dismissReminders(1000)
        assertTrue(dismissed.isReminderDeliveryDismissed(1000))
        assertFalse(dismissed.isReminderDeliveryDismissed(1001))
        assertFalse(task.isReminderDeliveryDismissed(1000))
        assertTrue(task.copy(status = "done").isReminderDeliveryDismissed(1001))
    }
}
