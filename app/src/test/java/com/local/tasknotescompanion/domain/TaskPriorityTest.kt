package com.local.tasknotescompanion.domain

import com.local.tasknotescompanion.tasknotes.TaskNoteParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TaskPriorityTest {
    @Test fun importsObsidianPrioritiesAndIgnoresUnrelatedSettings() {
        val priorities = decodePriorities("""{"otherSetting":true,"customPriorities":[{"value":"waiting","label":"Waiting on someone","color":"#36c","weight":7},{"label":"invalid"}]}""")
        assertEquals(listOf(TaskPriority("waiting", "Waiting on someone", "#3366CC", 7)), priorities)
        assertTrue(decodePriorities("{ broken").isEmpty())
    }

    @Test fun localDefinitionsRoundTripWithoutLosingNamesOrColors() {
        val priority = createCustomPriority("Waiting on Robyn", "#e91e63", DefaultTaskPriorities)
        assertEquals("waiting-on-robyn", priority.value)
        assertEquals(listOf(priority), decodePriorities(encodePriorities(listOf(priority))))
        assertEquals("#E91E63", priority.color)
    }

    @Test fun generatesUniqueValuesAndKeepsUnknownTaskPrioritiesSelectable() {
        val existing = DefaultTaskPriorities + TaskPriority("urgent", "Older urgent", "#FF0000")
        assertEquals("urgent-2", createCustomPriority("Urgent!", "#0000ff", existing).value)
        assertEquals("legacy-value", priorityForValue(existing, "legacy-value").value)
        assertEquals("Medium", priorityForValue(existing, "medium").label)
    }

    @Test fun rejectsBlankOrDuplicateNamesAndInvalidColors() {
        for ((name, color) in listOf(" " to "#123456", "HIGH" to "#123456", "Waiting" to "not-a-color")) {
            assertThrows(IllegalArgumentException::class.java) { createCustomPriority(name, color, DefaultTaskPriorities) }
        }
        assertNull(normalizePriorityColor("#12345"))
        assertEquals("#AABBCC", normalizePriorityColor(" abc "))
    }

    @Test fun mergesSavedCustomChoicesWithVaultChoicesAndAlwaysOffersNone() {
        val imported = listOf(TaskPriority("urgent", "Urgent", "#FF0000", 9))
        val custom = listOf(TaskPriority("waiting", "Waiting", "#0000FF", 10))
        assertEquals(listOf("waiting", "urgent", "none"), mergePriorities(imported, custom).map { it.value })
        assertTrue(mergePriorities(emptyList(), emptyList()).any { it.value == "normal" })
    }

    @Test fun savesCustomPriorityAsNormalTaskNotesFrontmatter() {
        val root = Files.createTempDirectory("priority-test").toFile()
        try {
            val file = root.resolve("Example.md")
            file.writeText("---\ntitle: Example\ntags: [task]\npriority: waiting-on-robyn\ncustom: keep\n---\nBody\n")
            val parser = TaskNoteParser()
            val task = parser.parse(file, root, TaskMappingConfig())!!
            assertEquals("waiting-on-robyn", task.priority)
            val rendered = parser.render(task.copy(priority = "urgent-2"), TaskMappingConfig())
            file.writeText(rendered)
            val saved = parser.parse(file, root, TaskMappingConfig())!!
            assertEquals("urgent-2", saved.priority)
            assertEquals("keep", saved.frontmatter.raw["custom"])
        } finally { root.deleteRecursively() }
    }
}
