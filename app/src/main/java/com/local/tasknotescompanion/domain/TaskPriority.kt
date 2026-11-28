package com.local.tasknotescompanion.domain

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.util.Locale

data class TaskPriority(val value: String, val label: String, val color: String, val weight: Int = 0)

val DefaultTaskPriorities = listOf(
    TaskPriority("none", "None", "#CCCCCC", 0),
    TaskPriority("low", "Low", "#00AA00", 1),
    TaskPriority("normal", "Normal", "#FFAA00", 2),
    TaskPriority("high", "High", "#FF0000", 3),
)

fun normalizePriorityColor(value: String): String? {
    val hex = value.trim().removePrefix("#")
    return when {
        hex.matches(Regex("[0-9a-fA-F]{6}")) -> "#${hex.uppercase(Locale.ROOT)}"
        hex.matches(Regex("[0-9a-fA-F]{3}")) -> "#${hex.flatMap { listOf(it, it) }.joinToString("").uppercase(Locale.ROOT)}"
        else -> null
    }
}

fun priorityForValue(priorities: List<TaskPriority>, value: String?): TaskPriority =
    priorities.firstOrNull { it.value == value } ?: when {
        value.isNullOrBlank() -> DefaultTaskPriorities.first()
        value == "medium" -> TaskPriority("medium", "Medium", "#FFAA00", 2)
        else -> TaskPriority(value, value, "#6366F1")
    }

fun createCustomPriority(name: String, color: String, existing: List<TaskPriority>): TaskPriority {
    val label = name.trim()
    require(label.isNotEmpty()) { "Enter a priority name." }
    require(label.length <= 80) { "Use a name of 80 characters or fewer." }
    require(existing.none { it.label.equals(label, true) }) { "A priority with this name already exists." }
    val normalizedColor = requireNotNull(normalizePriorityColor(color)) { "Enter a valid hex color, such as #6366F1." }
    val base = label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom-priority" }
    var value = base
    var suffix = 2
    while (existing.any { it.value.equals(value, true) }) value = "$base-${suffix++}"
    return TaskPriority(value, label, normalizedColor, (existing.maxOfOrNull { it.weight } ?: 0) + 1)
}

/** Reads both Obsidian's JSON settings and this app's stored YAML list. */
fun decodePriorities(text: String): List<TaskPriority> = runCatching {
    val parsed = Load(LoadSettings.builder().build()).loadFromString(text)
    val rows = if (parsed is Map<*, *>) parsed["customPriorities"] else parsed
    (rows as? List<*>)?.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val value = (map["value"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val label = (map["label"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: value
        val color = normalizePriorityColor(map["color"]?.toString().orEmpty())
            ?: priorityForValue(DefaultTaskPriorities, value).color
        TaskPriority(value, label, color, (map["weight"] as? Number)?.toInt() ?: 0)
    }?.distinctBy { it.value } ?: emptyList()
}.getOrDefault(emptyList())

fun encodePriorities(priorities: List<TaskPriority>): String =
    Dump(DumpSettings.builder().build()).dumpToString(priorities.map {
        mapOf("value" to it.value, "label" to it.label, "color" to it.color, "weight" to it.weight)
    })

fun mergePriorities(imported: List<TaskPriority>, custom: List<TaskPriority>): List<TaskPriority> {
    val combined = linkedMapOf<String, TaskPriority>()
    (imported.ifEmpty { DefaultTaskPriorities }).forEach { combined[it.value] = it }
    custom.forEach { combined[it.value] = it }
    if ("none" !in combined) combined["none"] = DefaultTaskPriorities.first()
    return combined.values.sortedByDescending { it.weight }
}
