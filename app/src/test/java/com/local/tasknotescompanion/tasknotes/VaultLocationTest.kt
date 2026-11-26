package com.local.tasknotescompanion.tasknotes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultLocationTest {
    @Test fun selectingCalendarIndexesItsObsidianVaultAndPreservesCreationFolder() {
        val vault = Files.createTempDirectory("vault").toFile()
        try {
            File(vault, ".obsidian").mkdir()
            val calendar = File(vault, "(Calendar").apply { mkdir() }
            val location = VaultLocation.resolve(calendar, "")
            assertEquals(vault.canonicalFile, location.root)
            assertEquals("(Calendar", location.taskFolder)
            assertEquals(location, VaultLocation.resolve(location.root, location.taskFolder))
        } finally { vault.deleteRecursively() }
    }

    @Test fun preservesNestedCreationFolderAndStandaloneFolderSelections() {
        val root = Files.createTempDirectory("vault").toFile()
        try {
            val calendar = File(root, "Calendar").apply { mkdir() }
            assertEquals(VaultLocation(calendar.canonicalFile, "Tasks"), VaultLocation.resolve(calendar, "Tasks"))
            File(root, ".obsidian").mkdir()
            assertEquals("Calendar/Tasks", VaultLocation.resolve(calendar, "Tasks").taskFolder)
        } finally { root.deleteRecursively() }
    }
}
