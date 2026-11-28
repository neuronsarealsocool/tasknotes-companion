package com.local.tasknotescompanion

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.tasknotescompanion.domain.ReminderAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorReminderOverlay(
    alert: ReminderAlert,
    vaultPath: String,
    onChange: (ReminderAlert) -> Unit,
    shared: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentAlert by rememberUpdatedState(alert)
    val currentOnChange by rememberUpdatedState(onChange)
    var copying by remember { mutableStateOf(false) }
    fun importMedia(uri: Uri?, kind: String) {
        if (uri == null) return
        scope.launch {
            copying = true
            val relative = withContext(Dispatchers.IO) {
                runCatching { copyPickedMedia(context, vaultPath, uri, kind) }.getOrNull()
            }
            copying = false
            if (relative == null) {
                Toast.makeText(context, "Could not copy media into vault", Toast.LENGTH_LONG).show()
            } else {
                val updated = when (kind) {
                    "image" -> currentAlert.copy(image = relative)
                    "video" -> currentAlert.copy(video = relative)
                    else -> currentAlert.copy(audio = relative)
                }
                currentOnChange(updated)
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "image") }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "audio") }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "video") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shared) {
            Text("Reminder overlay", style = MaterialTheme.typography.titleMedium)
            Text("Changes apply to all reminders for this task.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = alert.note.orEmpty(),
            onValueChange = { onChange(alert.copy(note = it.ifBlank { null })) },
            label = { Text("Notification note") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !copying) {
                Text(if (alert.image.isNullOrBlank()) "Pick photo/GIF" else "Change photo/GIF", maxLines = 1)
            }
            OutlinedButton(onClick = { audioPicker.launch("audio/*") }, enabled = !copying) {
                Text(if (alert.audio.isNullOrBlank()) "Pick audio" else "Change audio", maxLines = 1)
            }
            OutlinedButton(onClick = { videoPicker.launch("video/*") }, enabled = !copying) {
                Text(if (alert.video.isNullOrBlank()) "Pick video" else "Change video", maxLines = 1)
            }
        }
        listOf("Photo/GIF" to alert.image, "Video" to alert.video, "Audio" to alert.audio).forEach { (label, path) ->
            path?.let { Text("$label: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (copying) Text("Adding media...", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = alert.audioLoop, onClick = { onChange(alert.copy(audioLoop = !alert.audioLoop)) }, label = { Text("Loop audio") })
            TextButton(onClick = { onChange(alert.copy(image = null)) }) { Text("Remove photo", maxLines = 1) }
            TextButton(onClick = { onChange(alert.copy(video = null)) }) { Text("Remove video", maxLines = 1) }
            TextButton(onClick = { onChange(alert.copy(audio = null)) }) { Text("Remove audio", maxLines = 1) }
        }
    }
}
