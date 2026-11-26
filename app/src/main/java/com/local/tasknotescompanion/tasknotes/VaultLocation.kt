package com.local.tasknotescompanion.tasknotes

import java.io.File

data class VaultLocation(val root: File, val taskFolder: String) {
    companion object {
        fun resolve(selected: File, taskFolder: String): VaultLocation {
            val selection = selected.canonicalFile
            val vault = generateSequence(selection) { it.parentFile }
                .firstOrNull { File(it, ".obsidian").isDirectory }
                ?: return VaultLocation(selection, taskFolder)
            val creationFolder = if (taskFolder.isBlank()) selection else File(selection, taskFolder).canonicalFile
            return VaultLocation(vault, creationFolder.relativeTo(vault).invariantSeparatorsPath.takeUnless { it == "." }.orEmpty())
        }
    }
}
