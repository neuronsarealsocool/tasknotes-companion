package com.local.tasknotescompanion.data

import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TaskCacheReaderTest {
    private val config = TaskMappingConfig()

    private fun withNote(test: (File, TaskNoteParser, TaskEntity) -> Unit) {
        val root = Files.createTempDirectory("cache-recovery").toFile()
        try {
            val file = File(root, "note.md")
            file.writeText("""
                ---
                title: Original note
                tags: [task]
                status: open
                scheduled: 2026-11-28T10:00
                customField: keep-me
                reminders:
                  - id: before
                    type: relative
                    relatedTo: scheduled
                    offset: -PT5M
                    alert:
                      style: fullscreen
                      audioLoop: true
                      allowOverlay: false
                ---
                Original body.
            """.trimIndent())
            val parser = TaskNoteParser()
            test(root, parser, parser.parse(file, root, config)!!.toEntity())
        } finally { root.deleteRecursively() }
    }

    @Test fun rebuildsAliasLimitFailureFromMarkdownWithoutChangingTheNote() = withNote { root, parser, row ->
        val file = File(row.path)
        val original = file.readBytes()
        val excessiveAliases = "- &rem {id: broken, type: relative, relatedTo: scheduled, offset: -PT5M}\n" +
            List(60) { "- *rem\n" }.joinToString("")
        val repaired = readCachedTask(row.copy(reminders = excessiveAliases), root, config, parser)
        assertTrue(repaired.rebuilt)
        assertEquals(1, repaired.task.reminders.size)
        assertEquals("before", repaired.task.reminders.single().id)
        assertEquals("keep-me", repaired.task.frontmatter.raw["customField"])
        assertArrayEquals(original, file.readBytes())
        assertFalse(readCachedTask(repaired.task.toEntity(), root, config, parser).rebuilt)
    }

    @Test fun rebuildsSharedWriterCorruptionInsteadOfSilentlyDroppingReminders() = withNote { root, parser, row ->
        val corrupt = """
            - &rem
              id: before
              type: *rem
              offset: -PT5M
              relatedTo: scheduled
        """.trimIndent()
        val repaired = readCachedTask(row.copy(reminders = corrupt), root, config, parser)
        assertTrue(repaired.rebuilt)
        assertEquals("before", repaired.task.reminders.single().id)
        val brokenBoolean = """
            - &rem
              id: before
              type: relative
              offset: -PT5M
              relatedTo: scheduled
              alert:
                allowOverlay: *rem
        """.trimIndent()
        assertTrue(readCachedTask(row.copy(reminders = brokenBoolean), root, config, parser).rebuilt)
    }

    @Test fun rebuildsBrokenRawMetadataAndKeepsHealthyCacheUsableWithoutFileAccess() = withNote { root, parser, row ->
        assertTrue(readCachedTask(row.copy(rawFrontmatter = "title: [broken"), root, config, parser).rebuilt)
        File(row.path).delete()
        val healthy = readCachedTask(row, root, config, parser)
        assertFalse(healthy.rebuilt)
        assertEquals("Original note", healthy.task.title)
    }

    @Test fun concurrentCacheAndNoteConversionsDoNotShareYamlState() = withNote { root, parser, row ->
        val source = row.toRecord()
        val workers = Executors.newFixedThreadPool(8)
        val start = CyclicBarrier(8)
        try {
            val jobs = (0 until 8).map { worker -> Callable {
                start.await(10, TimeUnit.SECONDS)
                repeat(40) { iteration ->
                    val title = "Worker $worker pass $iteration"
                    val task = source.copy(title = title, reminders = (0 until 64).map { index ->
                        val reminder = source.reminders.single() as com.local.tasknotescompanion.domain.ReminderSpec.Relative
                        reminder.copy(id = "rem_$index", raw = mapOf("customEmptyMap" to emptyMap<String, Any>()))
                    })
                    val cached = task.toEntity()
                    val restored = cached.toRecord()
                    assertEquals(title, restored.title)
                    assertEquals(64, restored.reminders.size)
                    assertEquals("rem_63", restored.reminders.last().id)
                    // Exercise the shared repository parser's writer concurrently too.
                    val rendered = parser.render(task, config)
                    assertTrue(rendered.contains("title: $title"))
                    val ownFile = File(root, "worker-$worker.md").apply { writeText(rendered) }
                    val parsed = parser.parse(ownFile, root, config)!!
                    assertEquals(title, parsed.title)
                    assertEquals(64, parsed.reminders.size)
                }
            } }
            workers.invokeAll(jobs, 60, TimeUnit.SECONDS).forEach { it.get() }
        } finally { workers.shutdownNow() }
    }
}
