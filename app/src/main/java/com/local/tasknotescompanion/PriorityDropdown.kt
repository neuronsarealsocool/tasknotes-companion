package com.local.tasknotescompanion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.local.tasknotescompanion.domain.TaskPriority
import com.local.tasknotescompanion.domain.normalizePriorityColor
import com.local.tasknotescompanion.domain.priorityForValue

@Composable
internal fun PriorityDot(color: String) {
    Box(Modifier.size(16.dp).background(priorityColor(color), CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

private fun priorityColor(value: String): Color = Color((normalizePriorityColor(value) ?: "#6366F1").toColorInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriorityDropdown(
    value: String?,
    priorities: List<TaskPriority>,
    onSelect: (String) -> Unit,
    onCreate: (String, String) -> TaskPriority,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val selected = priorityForValue(priorities, value)
    val choices = if (priorities.any { it.value == selected.value }) priorities else priorities + selected
    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Priority") },
            leadingIcon = { PriorityDot(selected.color) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            choices.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.label) },
                    leadingIcon = { PriorityDot(priority.color) },
                    onClick = { onSelect(priority.value); expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Add custom priority…") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { expanded = false; adding = true },
            )
        }
    }
    if (adding) CustomPriorityDialog(
        onDismiss = { adding = false },
        onCreate = { name, color -> onCreate(name, color).also { onSelect(it.value); adding = false } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPriorityDialog(onDismiss: () -> Unit, onCreate: (String, String) -> TaskPriority) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("#6366F1") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val validColor = normalizePriorityColor(color)
    val palette = listOf("Red" to "#FF0000", "Orange" to "#F97316", "Amber" to "#FFAA00", "Green" to "#00AA00",
        "Teal" to "#0D9488", "Blue" to "#2563EB", "Indigo" to "#6366F1", "Purple" to "#9333EA", "Pink" to "#DB2777", "Gray" to "#6B7280")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom priority") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    palette.forEach { (label, hex) ->
                        Box(Modifier.size(44.dp).selectable(validColor == hex, role = Role.RadioButton, onClick = { color = hex; error = null })
                            .semantics { contentDescription = "$label $hex" }.padding(8.dp)
                            .background(priorityColor(hex), CircleShape)
                            .border(if (validColor == hex) 3.dp else 1.dp, if (validColor == hex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                OutlinedTextField(color, { color = it; error = null }, label = { Text("Hex color") }, singleLine = true,
                    isError = validColor == null, supportingText = { Text("Use #RRGGBB, for example #6366F1") },
                    leadingIcon = { PriorityDot(color) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && validColor != null, onClick = {
                runCatching { onCreate(name, color) }.onFailure { error = it.message ?: "Could not add priority." }
            }) { Text("Add priority") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
