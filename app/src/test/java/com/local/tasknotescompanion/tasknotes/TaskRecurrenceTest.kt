package com.local.tasknotescompanion.tasknotes

import com.local.tasknotescompanion.TaskFilter
import com.local.tasknotescompanion.filterBy
import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.domain.TaskFrontmatter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

class TaskRecurrenceTest {
    private val recurrence = TaskRecurrence()
    private val today = LocalDate.of(2026, 11, 26)
    private val now = today.atTime(15, 0)
    private val config = TaskMappingConfig()

    private fun task(rule: String = "DTSTART:20261029;FREQ=WEEKLY;INTERVAL=2;BYDAY=TH", extra: String = "") =
        Files.createTempDirectory("recurrence").toFile().let { root ->
            try {
                val note = File(root, "recurring.md")
                note.writeText("---\ntitle: Catch up\ntags: [task]\nstatus: open\nscheduled: 2026-10-29\nrecurrence: '$rule'\n$extra\n---\nBody")
                TaskNoteParser().parse(note, root, config)!!
            } finally { root.deleteRecursively() }
        }

    @Test fun fortnightlyNoteWithOldScheduledDateAppearsTodayWithoutRewritingItsSeries() {
        val original = task()
        val displayed = listOf(original).filterBy(TaskFilter.TODAY, "", now).single()
        assertEquals(today, displayed.scheduled)
        assertEquals(today, displayed.occurrenceDate)
        assertEquals(LocalDate.of(2026, 10, 29), original.scheduled)
        assertEquals("2026-10-29", original.frontmatter.raw["scheduled"].toString())
    }

    @Test fun excludesCompletedAndSkippedInstancesFromTodayAndFindsNextUpcoming() {
        val original = task(extra = "complete_instances: [2026-11-26]\nskipped_instances: [2026-12-10]")
        assertTrue(listOf(original).filterBy(TaskFilter.TODAY, "", now).isEmpty())
        val displayed = listOf(original).filterBy(TaskFilter.UPCOMING, "", now).single()
        assertEquals(LocalDate.of(2026, 12, 24), displayed.scheduled)
    }

    @Test fun honoursYearlyMonthlyAndFiniteRules() {
        assertEquals(LocalDate.of(2026, 12, 3), recurrence.nextDate(task("DTSTART:20261203;FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=3"), today))
        assertEquals(LocalDate.of(2026, 11, 27), recurrence.nextDate(task("DTSTART:20260101;FREQ=MONTHLY;BYDAY=-1FR"), today))
        assertNull(recurrence.nextDate(task("DTSTART:20261029;FREQ=WEEKLY;COUNT=2"), today))
        assertNull(recurrence.nextDate(task("DTSTART:20261029;FREQ=WEEKLY;UNTIL=20261101"), today))
    }

    @Test fun keepsClockComponentsAndHandlesMultilineAndLegacyRules() {
        assertEquals(today, recurrence.nextDate(task("DTSTART:20261029T233000Z;FREQ=WEEKLY;INTERVAL=2;BYDAY=TH"), today))
        assertEquals(today, recurrence.nextDate(task("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH"), today))
        val multiline = task().let { it.copy(recurrence = it.recurrence!!.copy(rrule = "DTSTART:20261029\nRRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TH")) }
        assertEquals(today, recurrence.nextDate(multiline, today))
    }

    @Test fun preservesExplicitOffPatternNextCommitment() {
        val original = task().copy(scheduled = today.plusDays(1))
        assertEquals(today.plusDays(1), recurrence.forDisplay(original, today.plusDays(1)).scheduled)
        val completionAnchored = original.copy(frontmatter = TaskFrontmatter(original.frontmatter.raw + ("recurrence_anchor" to "completion")))
        assertEquals(original.scheduled, recurrence.forDisplay(completionAnchored, today.plusMonths(1)).scheduled)
    }

    @Test fun completingOneOccurrenceKeepsSeriesOpenAndPreservesHistoryAndUnknownFields() {
        val original = task(extra = "complete_instances: [2026-11-12]\ncustomField: keep-me")
        val completed = recurrence.complete(recurrence.forDisplay(original, today), config, now)!!
        assertFalse(completed.isDone)
        assertEquals(LocalDate.of(2026, 12, 10), completed.scheduled)
        assertEquals(listOf("2026-11-12", "2026-11-26"), completed.frontmatter.raw["complete_instances"])
        assertEquals("keep-me", completed.frontmatter.raw["customField"])
        assertEquals(original.recurrence, completed.recurrence)
        val rendered = TaskNoteParser().render(completed, config)
        assertTrue(rendered.contains("complete_instances:"))
        assertTrue(rendered.contains("customField: keep-me"))
    }

    @Test fun completedSeriesStaysDoneAndInvalidRuleDoesNotCrashTheList() {
        val completed = task().copy(status = "done", completedDate = now)
        assertTrue(listOf(completed).filterBy(TaskFilter.TODAY, "", now).isEmpty())
        assertEquals(completed, recurrence.forDisplay(completed, today))
        assertNull(recurrence.nextDate(task("FREQ=INVALID"), today))
    }
}
