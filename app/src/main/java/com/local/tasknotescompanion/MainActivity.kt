package com.local.tasknotescompanion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.tasknotescompanion.data.toRecord
import com.local.tasknotescompanion.domain.DetectionMode
import com.local.tasknotescompanion.domain.NotificationPreferences
import com.local.tasknotescompanion.domain.ReminderAlert
import com.local.tasknotescompanion.domain.ReminderAnchor
import com.local.tasknotescompanion.domain.ReminderSpec
import com.local.tasknotescompanion.domain.TaskMappingConfig
import com.local.tasknotescompanion.domain.TaskRecord
import com.local.tasknotescompanion.notifications.ReminderScheduler
import com.local.tasknotescompanion.quickadd.QuickAddDateTarget
import com.local.tasknotescompanion.quickadd.QuickAddDraft
import com.local.tasknotescompanion.quickadd.QuickAddParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.material3.rememberTimePickerState

class MainActivity : ComponentActivity() {
    private val requestedTask = mutableStateOf<RequestedTask?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTask.value = intent.taskToOpen()
        setContent {
            MaterialTheme(colorScheme = lightTaskScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    TaskNotesAppUi(
                        requestedTask = requestedTask.value,
                        onTaskRequestConsumed = { requestedTask.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTask.value = intent.taskToOpen()
    }
}

private data class RequestedTask(val id: String?, val path: String?)

private fun Intent.taskToOpen(): RequestedTask? {
    return if (action == ReminderScheduler.ACTION_OPEN_TASK) {
        RequestedTask(
            id = getStringExtra(ReminderScheduler.EXTRA_TASK_ID),
            path = getStringExtra(ReminderScheduler.EXTRA_TASK_PATH),
        )
    } else {
        null
    }
}

class MainViewModel(private val app: TaskNotesApp) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(TaskFilter.TODAY)
    private val editing = MutableStateFlow<TaskRecord?>(null)
    private val settingsOpen = MutableStateFlow(false)
    private val config = MutableStateFlow(app.mappingStore.load())
    private val notificationPreferences = MutableStateFlow(app.notificationPreferencesStore.load())
    private val vaultPath = MutableStateFlow(app.mappingStore.vaultPath())
    private val message = MutableStateFlow("")

    val state: StateFlow<MainState> = combine(
        app.repository.observeTasks(),
        query,
        filter,
        editing,
        settingsOpen,
        config,
        notificationPreferences,
        vaultPath,
        message,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tasks = values[0] as List<TaskRecord>
        val q = values[1] as String
        val activeFilter = values[2] as TaskFilter
        MainState(
            tasks = tasks.filterBy(activeFilter, q),
            allCount = tasks.size,
            query = q,
            filter = activeFilter,
            editing = values[3] as TaskRecord?,
            settingsOpen = values[4] as Boolean,
            config = values[5] as TaskMappingConfig,
            notificationPreferences = values[6] as NotificationPreferences,
            vaultPath = values[7] as String,
            message = values[8] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: TaskFilter) { filter.value = value }
    fun edit(task: TaskRecord?) { editing.value = task }
    fun openSettings(open: Boolean) { settingsOpen.value = open }

    fun openTask(taskId: String?, taskPath: String?) {
        viewModelScope.launch {
            val task = taskId?.let { app.database.taskDao().findById(it) }?.toRecord()
                ?: taskPath?.let { app.database.taskDao().findByPath(it) }?.toRecord()
            if (task != null) {
                settingsOpen.value = false
                editing.value = task
            } else {
                message.value = "Task not found"
            }
        }
    }

    fun saveVault(path: String) {
        app.mappingStore.saveVaultPath(path)
        vaultPath.value = path
        scan()
    }

    fun saveConfig(value: TaskMappingConfig) {
        app.mappingStore.save(value)
        config.value = value
        scan()
    }

    fun saveNotificationPreferences(value: NotificationPreferences) {
        app.notificationPreferencesStore.save(value)
        notificationPreferences.value = value
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            val count = app.repository.scanVault()
            app.reminderScheduler.rescheduleAll(app.database.taskDao().snapshot().map { it.toRecord() })
            message.value = "Indexed $count task notes"
        }
    }

    fun createTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val task = app.repository.create(title.trim(), notificationPreferences.value.defaultReminders)
            app.reminderScheduler.rescheduleTask(task)
            editing.value = task
            message.value = "Created ${task.title}"
        }
    }

    fun createQuickTask(draft: QuickAddDraft, openEditor: Boolean = false) {
        if (draft.title.isBlank()) return
        viewModelScope.launch {
            val task = app.repository.create(draft, notificationPreferences.value.defaultReminders)
            app.reminderScheduler.rescheduleTask(task)
            editing.value = if (openEditor) task else null
            message.value = "Created ${task.title}"
        }
    }

    fun saveTask(task: TaskRecord) {
        viewModelScope.launch {
            val saved = app.repository.save(task)
            app.reminderScheduler.cancelTask(task.id)
            app.reminderScheduler.rescheduleTask(saved)
            editing.value = null
            message.value = "Saved ${saved.title}"
        }
    }

    fun complete(task: TaskRecord) {
        viewModelScope.launch {
            app.repository.complete(task)
            app.reminderScheduler.cancelTask(task.id)
            editing.value = null
            message.value = "Completed ${task.title}"
        }
    }

    fun delete(task: TaskRecord) {
        viewModelScope.launch {
            app.repository.delete(task)
            app.reminderScheduler.cancelTask(task.id)
            editing.value = null
            message.value = "Deleted ${task.title}"
        }
    }
}

data class MainState(
    val tasks: List<TaskRecord> = emptyList(),
    val allCount: Int = 0,
    val query: String = "",
    val filter: TaskFilter = TaskFilter.TODAY,
    val editing: TaskRecord? = null,
    val settingsOpen: Boolean = false,
    val config: TaskMappingConfig = TaskMappingConfig(),
    val notificationPreferences: NotificationPreferences = NotificationPreferences(),
    val vaultPath: String = "",
    val message: String = "",
)

enum class TaskFilter { TODAY, OVERDUE, UPCOMING, ALL }

private fun List<TaskRecord>.filterBy(filter: TaskFilter, query: String): List<TaskRecord> {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    return asSequence()
        .filter { task ->
            val anchors = task.dateTimeAnchors()
            val isOverdue = anchors.any { it.isBefore(now) }
            when (filter) {
                TaskFilter.TODAY -> !task.isDone && !isOverdue && anchors.any { it.toLocalDate() == today }
                TaskFilter.OVERDUE -> !task.isDone && isOverdue
                TaskFilter.UPCOMING -> !task.isDone && !isOverdue && anchors.any { it.toLocalDate().isAfter(today) }
                TaskFilter.ALL -> true
            }
        }
        .filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        .toList()
}

private fun TaskRecord.dateTimeAnchors(): List<LocalDateTime> =
    listOfNotNull(
        due?.atTime(dueTime ?: DefaultTaskAnchorTime),
        scheduled?.atTime(scheduledTime ?: DefaultTaskAnchorTime),
    )

private val DefaultTaskAnchorTime: LocalTime = LocalTime.of(9, 0)

@Suppress("UNCHECKED_CAST")
class MainViewModelFactory(private val app: TaskNotesApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
}

@Composable
private fun TaskNotesAppUi(requestedTask: RequestedTask?, onTaskRequestConsumed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TaskNotesApp
    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(app))
    val state by viewModel.state.collectAsState()
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.saveVault(resolveTreePath(context = context, uri = it) ?: it.toString()) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(requestedTask) {
        requestedTask?.let {
            viewModel.openTask(it.id, it.path)
            onTaskRequestConsumed()
        }
    }

    when {
        state.settingsOpen -> SettingsScreen(state, viewModel::saveConfig, viewModel::saveNotificationPreferences, { viewModel.openSettings(false) })
        state.editing != null -> EditorScreen(state.editing, state.config, state.vaultPath, viewModel::saveTask, viewModel::complete, viewModel::delete) { viewModel.edit(null) }
        state.vaultPath.isBlank() -> SetupScreen(
            path = state.vaultPath,
            onPick = { folderLauncher.launch(null) },
            onAllFiles = {
                ContextCompat.startActivity(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }, null)
            },
            onSave = viewModel::saveVault,
        )
        else -> TaskListScreen(state, viewModel)
    }
}

@Composable
fun lightTaskScheme() = androidx.compose.material3.lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2D6A4F),
    secondary = androidx.compose.ui.graphics.Color(0xFF6B5B95),
    tertiary = androidx.compose.ui.graphics.Color(0xFFC17C45),
    background = androidx.compose.ui.graphics.Color(0xFFFBFAF7),
    surface = androidx.compose.ui.graphics.Color(0xFFFBFAF7),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(path: String, onPick: () -> Unit, onAllFiles: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(path) }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("TaskNotes Companion") }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect your Obsidian vault", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value, { value = it }, label = { Text("Vault path") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose")
                }
                TextButton(onClick = onAllFiles) { Text("All files access") }
            }
            Button(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Use vault") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskListScreen(state: MainState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val needsAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    var quickAddOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Tasks (${state.allCount})") },
                actions = {
                    IconButton(onClick = viewModel::scan) { Icon(Icons.Default.Refresh, contentDescription = "Scan") }
                    IconButton(onClick = { viewModel.openSettings(true) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { quickAddOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        },
        bottomBar = {
            NavigationBar {
                TaskFilter.entries.forEach { filter ->
                    NavigationBarItem(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        icon = { Text(filter.name.first().toString()) },
                        label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (needsAllFilesAccess) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "All files access is needed to scan Obsidian notes and schedule their reminders.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            ContextCompat.startActivity(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }, null)
                        }) { Text("Grant") }
                    }
                }
            }
            OutlinedTextField(state.query, viewModel::setQuery, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
            if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.tasks, key = { it.id }) { task ->
                    Surface(
                        onClick = { viewModel.edit(task) },
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Row {
                                    IconButton(onClick = { viewModel.delete(task) }) { Icon(Icons.Default.Delete, "Delete") }
                                    IconButton(onClick = { viewModel.complete(task) }) { Icon(Icons.Default.Check, "Complete") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                task.scheduled?.let { AssistChip(onClick = {}, label = { Text("Scheduled ${formatDateWithTime(it, task.scheduledTime)}") }) }
                                task.due?.let { AssistChip(onClick = {}, label = { Text("Due ${formatDateWithTime(it, task.dueTime)}") }) }
                                task.priority?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (quickAddOpen) {
        QuickAddDialog(
            vaultPath = state.vaultPath,
            onDismiss = { quickAddOpen = false },
            onCreate = { draft, openEditor ->
                viewModel.createQuickTask(draft, openEditor)
                quickAddOpen = false
            },
        )
    }
}

@Composable
private fun QuickAddDialog(
    vaultPath: String,
    onDismiss: () -> Unit,
    onCreate: (QuickAddDraft, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val parser = remember { QuickAddParser() }
    var rawText by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(QuickAddDateTarget.SCHEDULED) }
    val parsed = remember(rawText, target) { parser.parse(rawText, target) }
    var title by remember { mutableStateOf("") }
    var removedTags by remember { mutableStateOf(setOf<String>()) }
    var removedProjects by remember { mutableStateOf(setOf<String>()) }
    var removedContexts by remember { mutableStateOf(setOf<String>()) }
    var dateRemoved by remember { mutableStateOf(false) }
    var timeRemoved by remember { mutableStateOf(false) }
    var priorityRemoved by remember { mutableStateOf(false) }
    var alertNote by rememberSaveable { mutableStateOf("") }
    var alertImage by rememberSaveable { mutableStateOf<String?>(null) }
    var alertAudio by rememberSaveable { mutableStateOf<String?>(null) }
    var alertAudioLoop by rememberSaveable { mutableStateOf(true) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val relative = copyPickedMedia(context, vaultPath, uri, "image")
            if (relative != null) {
                alertImage = relative
                Toast.makeText(context, "Photo added to default reminders", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not copy photo into vault", Toast.LENGTH_LONG).show()
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val relative = copyPickedMedia(context, vaultPath, uri, "audio")
            if (relative != null) {
                alertAudio = relative
                Toast.makeText(context, "Audio added to default reminders", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not copy audio into vault", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(rawText, target) {
        title = parsed.title
        removedTags = emptySet()
        removedProjects = emptySet()
        removedContexts = emptySet()
        dateRemoved = false
        timeRemoved = false
        priorityRemoved = false
    }

    val finalDraft = parsed.copy(
        title = title.trim(),
        scheduled = if (dateRemoved) null else parsed.scheduled,
        scheduledTime = if (dateRemoved || timeRemoved) null else parsed.scheduledTime,
        due = if (dateRemoved) null else parsed.due,
        dueTime = if (dateRemoved || timeRemoved) null else parsed.dueTime,
        tags = parsed.tags.filterNot { it in removedTags },
        projects = parsed.projects.filterNot { it in removedProjects },
        contexts = parsed.contexts.filterNot { it in removedContexts },
        priority = parsed.priority.takeUnless { priorityRemoved },
        alert = ReminderAlert(
            style = "fullscreen",
            note = alertNote.ifBlank { null },
            image = alertImage,
            audio = alertAudio,
            audioLoop = alertAudioLoop,
            audioUntil = "dismiss",
        ),
    )
    val hasSingleDate = parsed.scheduled != null && parsed.due == null || parsed.due != null && parsed.scheduled == null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Quick add") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasSingleDate && !dateRemoved) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = target == QuickAddDateTarget.SCHEDULED,
                            onClick = { target = QuickAddDateTarget.SCHEDULED },
                            label = { Text("Scheduled") },
                        )
                        FilterChip(
                            selected = target == QuickAddDateTarget.DUE,
                            onClick = { target = QuickAddDateTarget.DUE },
                            label = { Text("Due") },
                        )
                    }
                }
                Text("Detected", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    finalDraft.scheduled?.let {
                        AssistChip(
                            onClick = {
                                dateRemoved = true
                            },
                            label = { Text("Scheduled ${formatDateWithTime(it, finalDraft.scheduledTime)} x") },
                        )
                    }
                    finalDraft.due?.let {
                        AssistChip(
                            onClick = {
                                dateRemoved = true
                            },
                            label = { Text("Due ${formatDateWithTime(it, finalDraft.dueTime)} x") },
                        )
                    }
                    finalDraft.tags.forEach { tag ->
                        AssistChip(
                            onClick = {
                                removedTags = removedTags + tag
                            },
                            label = { Text("#$tag x") },
                        )
                    }
                    finalDraft.projects.forEach { project ->
                        AssistChip(
                            onClick = {
                                removedProjects = removedProjects + project
                            },
                            label = { Text("+$project x") },
                        )
                    }
                    finalDraft.contexts.forEach { context ->
                        AssistChip(
                            onClick = {
                                removedContexts = removedContexts + context
                            },
                            label = { Text("@$context x") },
                        )
                    }
                    finalDraft.priority?.let { priority ->
                        AssistChip(
                            onClick = {
                                priorityRemoved = true
                            },
                            label = { Text("$priority priority x") },
                        )
                    }
                    AssistChip(onClick = {}, label = { Text("Task File") })
                }
                Text("Reminder overlay", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = alertNote,
                    onValueChange = { alertNote = it },
                    label = { Text("Notification note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (alertImage.isNullOrBlank()) "Pick photo" else "Change photo") }
                    OutlinedButton(
                        onClick = { audioPicker.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (alertAudio.isNullOrBlank()) "Pick audio" else "Change audio") }
                }
                alertImage?.let {
                    Text("Photo: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                alertAudio?.let {
                    Text("Audio: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = alertAudioLoop,
                        onClick = { alertAudioLoop = !alertAudioLoop },
                        label = { Text("Loop audio") },
                    )
                    TextButton(onClick = { alertImage = null }) { Text("Remove photo") }
                    TextButton(onClick = { alertAudio = null }) { Text("Remove audio") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    OutlinedButton(
                        onClick = { onCreate(finalDraft, true) },
                        enabled = finalDraft.title.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(finalDraft, false) },
                        enabled = finalDraft.title.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    task: TaskRecord?,
    config: TaskMappingConfig,
    vaultPath: String,
    onSave: (TaskRecord) -> Unit,
    onComplete: (TaskRecord) -> Unit,
    onDelete: (TaskRecord) -> Unit,
    onBack: () -> Unit,
) {
    if (task == null) return
    var title by remember(task.id) { mutableStateOf(task.title) }
    var body by remember(task.id) { mutableStateOf(task.body) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var due by remember(task.id) { mutableStateOf(task.due) }
    var dueTime by remember(task.id) { mutableStateOf(task.dueTime) }
    var scheduled by remember(task.id) { mutableStateOf(task.scheduled) }
    var scheduledTime by remember(task.id) { mutableStateOf(task.scheduledTime) }
    var openDateField by remember(task.id) { mutableStateOf<DateEditorField?>(null) }
    var visibleMonth by remember(task.id, openDateField) {
        mutableStateOf(YearMonth.from(
            when (openDateField) {
                DateEditorField.SCHEDULED -> scheduled
                DateEditorField.DUE -> due
                null -> scheduled ?: due ?: LocalDate.now()
            } ?: LocalDate.now(),
        ))
    }
    var priority by remember(task.id) { mutableStateOf(task.priority.orEmpty()) }
    var tags by remember(task.id) { mutableStateOf(task.tags.joinToString(" ")) }
    var reminders by remember(task.id) { mutableStateOf(task.reminders) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edit task") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        onSave(task.copy(
                            title = title,
                            body = body,
                            status = status,
                            priority = priority.ifBlank { null },
                            due = due,
                            dueTime = dueTime,
                            scheduled = scheduled,
                            scheduledTime = scheduledTime,
                            tags = tags.split(" ", ",").map { it.trim() }.filter { it.isNotBlank() },
                            reminders = reminders,
                        ))
                    }) { Icon(Icons.Default.Save, "Save") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(title, { title = it }, label = { Text(config.titleField) }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(status, { status = it }, label = { Text(config.statusField) }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(priority, { priority = it }, label = { Text(config.priorityField) }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(
                        label = config.scheduledField,
                        value = scheduled,
                        onOpen = {
                            openDateField = if (openDateField == DateEditorField.SCHEDULED) null else DateEditorField.SCHEDULED
                        },
                        onClear = {
                            scheduled = null
                            scheduledTime = null
                            if (openDateField == DateEditorField.SCHEDULED) openDateField = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DateField(
                        label = config.dueField,
                        value = due,
                        onOpen = {
                            openDateField = if (openDateField == DateEditorField.DUE) null else DateEditorField.DUE
                        },
                        onClear = {
                            due = null
                            dueTime = null
                            if (openDateField == DateEditorField.DUE) openDateField = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (openDateField != null) {
                item {
                    InlineCalendar(
                        visibleMonth = visibleMonth,
                        selected = if (openDateField == DateEditorField.SCHEDULED) scheduled else due,
                        onPrevious = { visibleMonth = visibleMonth.minusMonths(1) },
                        onNext = { visibleMonth = visibleMonth.plusMonths(1) },
                        onSelect = {
                            if (openDateField == DateEditorField.SCHEDULED) scheduled = it else due = it
                            openDateField = null
                        },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(
                        label = "scheduled time",
                        date = scheduled,
                        value = scheduledTime,
                        onValue = { scheduledTime = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = "due time",
                        date = due,
                        value = dueTime,
                        onValue = { dueTime = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { OutlinedTextField(tags, { tags = it }, label = { Text(config.tagsField) }, modifier = Modifier.fillMaxWidth()) }
            item { ReminderEditor(reminders = reminders, vaultPath = vaultPath, onReminders = { reminders = it }) }
            item { OutlinedTextField(body, { body = it }, label = { Text("Markdown body") }, modifier = Modifier.fillMaxWidth(), minLines = 8) }
            item {
                Button(onClick = { onComplete(task) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Complete")
                }
            }
            item {
                TextButton(onClick = { onDelete(task) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete from vault")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReminderEditor(reminders: List<ReminderSpec>, vaultPath: String, onReminders: (List<ReminderSpec>) -> Unit) {
    val context = LocalContext.current
    var mediaTargetReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaTargetKind by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val targetId = mediaTargetReminderId
        if (uri != null && targetId != null) {
            val relative = copyPickedMedia(context, vaultPath, uri, "image")
            if (relative != null) {
                onReminders(reminders.map { reminder ->
                    if (reminder.id == targetId) reminder.withAlert(reminder.alertForEdit().copy(image = relative)) else reminder
                })
                Toast.makeText(context, "Photo added to reminder", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not copy photo into vault", Toast.LENGTH_LONG).show()
            }
        }
        mediaTargetReminderId = null
        mediaTargetKind = null
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val targetId = mediaTargetReminderId
        if (uri != null && targetId != null) {
            val relative = copyPickedMedia(context, vaultPath, uri, "audio")
            if (relative != null) {
                onReminders(reminders.map { reminder ->
                    if (reminder.id == targetId) reminder.withAlert(reminder.alertForEdit().copy(audio = relative)) else reminder
                })
                Toast.makeText(context, "Audio added to reminder", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not copy audio into vault", Toast.LENGTH_LONG).show()
            }
        }
        mediaTargetReminderId = null
        mediaTargetKind = null
    }
    var absoluteDate by remember { mutableStateOf(LocalDate.now()) }
    var absoluteTime by remember { mutableStateOf(LocalTime.now().plusHours(1).withSecond(0).withNano(0)) }
    var absoluteCalendarOpen by remember { mutableStateOf(false) }
    var absoluteVisibleMonth by remember { mutableStateOf(YearMonth.from(absoluteDate)) }
    var absoluteDescription by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reminders", style = MaterialTheme.typography.titleMedium)
        if (reminders.isEmpty()) {
            Text("No reminders", style = MaterialTheme.typography.bodySmall)
        }
        reminders.forEach { reminder ->
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(reminder.describe(), modifier = Modifier.weight(1f))
                        IconButton(onClick = { onReminders(reminders.filterNot { it.id == reminder.id }) }) {
                            Icon(Icons.Default.Delete, "Remove reminder")
                        }
                    }
                    val alert = reminder.alertForEdit()
                    OutlinedTextField(
                        value = alert.note.orEmpty(),
                        onValueChange = { note ->
                            onReminders(reminders.map { if (it.id == reminder.id) it.withAlert(alert.copy(note = note.ifBlank { null })) else it })
                        },
                        label = { Text("Fullscreen notification note") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                mediaTargetReminderId = reminder.id
                                mediaTargetKind = "image"
                                imagePicker.launch("image/*")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (alert.image.isNullOrBlank()) "Pick photo" else "Change photo") }
                        OutlinedButton(
                            onClick = {
                                mediaTargetReminderId = reminder.id
                                mediaTargetKind = "audio"
                                audioPicker.launch("audio/*")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (alert.audio.isNullOrBlank()) "Pick audio" else "Change audio") }
                    }
                    alert.image?.let {
                        Text("Photo: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    alert.audio?.let {
                        Text("Audio: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = alert.audioLoop,
                            onClick = { onReminders(reminders.map { if (it.id == reminder.id) it.withAlert(alert.copy(audioLoop = !alert.audioLoop)) else it }) },
                            label = { Text("Loop audio") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onReminders(reminders.map { if (it.id == reminder.id) it.withAlert(alert.copy(image = null)) else it }) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove photo") }
                        TextButton(
                            onClick = { onReminders(reminders.map { if (it.id == reminder.id) it.withAlert(alert.copy(audio = null)) else it }) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove audio") }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofMinutes(-5), "5m before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("5 minutes before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofMinutes(-5), "5m before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("5 minutes before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofMinutes(-10), "10m before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("10 minutes before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofMinutes(-10), "10m before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("10 minutes before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofMinutes(-15), "15m before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("15 minutes before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofMinutes(-15), "15m before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("15 minutes before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofMinutes(-30), "30m before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("30 minutes before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofMinutes(-30), "30m before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("30 minutes before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofHours(-1), "1h before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("1 hour before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofHours(-1), "1h before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("1 hour before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofDays(-1), "1d before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("1 day before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofDays(-1), "1d before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("1 day before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.DUE, Duration.ofHours(-2), "2h before due")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("2 hours before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofHours(-2), "2h before scheduled")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("2 hours before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelativeSeries(ReminderAnchor.DUE, before = true)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every day for 14 days before due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelativeSeries(ReminderAnchor.DUE, before = false)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every day for 14 days after due time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelativeSeries(ReminderAnchor.SCHEDULED, before = true)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every day for 14 days before scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRelativeSeries(ReminderAnchor.SCHEDULED, before = false)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every day for 14 days after scheduled time")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRepeatingScheduled(Duration.ofMinutes(5))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every 5 minutes after scheduled time until complete")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRepeatingScheduled(Duration.ofMinutes(10))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every 10 minutes after scheduled time until complete")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRepeatingScheduled(Duration.ofMinutes(15))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every 15 minutes after scheduled time until complete")
            }
            TextButton(
                onClick = { onReminders(reminders + quickRepeatingScheduled(Duration.ofMinutes(30))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Every 30 minutes after scheduled time until complete")
            }
        }
        Text("Absolute reminder", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { absoluteCalendarOpen = !absoluteCalendarOpen }, modifier = Modifier.fillMaxWidth()) {
            Text(absoluteDate.toString())
        }
        if (absoluteCalendarOpen) {
            InlineCalendar(
                visibleMonth = absoluteVisibleMonth,
                selected = absoluteDate,
                onPrevious = { absoluteVisibleMonth = absoluteVisibleMonth.minusMonths(1) },
                onNext = { absoluteVisibleMonth = absoluteVisibleMonth.plusMonths(1) },
                onSelect = {
                    absoluteDate = it
                    absoluteCalendarOpen = false
                },
            )
        }
        InlineTimePicker(
            label = "Absolute reminder time",
            value = absoluteTime,
            onValue = { absoluteTime = it },
        )
        OutlinedTextField(
            absoluteDescription,
            { absoluteDescription = it },
            label = { Text("Reminder message") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onReminders(
                    reminders + ReminderSpec.Absolute(
                        id = "rem_${System.currentTimeMillis()}",
                        absoluteTime = absoluteDate.atTime(absoluteTime),
                        description = absoluteDescription.ifBlank { null },
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add absolute reminder")
        }
    }
}

private fun quickRelative(anchor: ReminderAnchor, offset: Duration, description: String): ReminderSpec.Relative {
    return ReminderSpec.Relative(
        id = "rem_${System.currentTimeMillis()}_${anchor.name.lowercase()}",
        relatedTo = anchor,
        offset = offset,
        description = description,
    )
}

private fun quickRelativeSeries(anchor: ReminderAnchor, before: Boolean): List<ReminderSpec.Relative> {
    val now = System.currentTimeMillis()
    val direction = if (before) "before" else "after"
    val sign = if (before) -1 else 1
    val anchorName = anchor.name.lowercase()
    return (1..14).map { day ->
        ReminderSpec.Relative(
            id = "rem_${now}_${anchorName}_${direction}_${day}d",
            relatedTo = anchor,
            offset = Duration.ofDays((day * sign).toLong()),
            description = "${day}d $direction $anchorName",
        )
    }
}

private fun quickRepeatingScheduled(interval: Duration): ReminderSpec.Relative {
    return ReminderSpec.Relative(
        id = "rem_${System.currentTimeMillis()}_scheduled_repeat_${interval.toMinutes()}m",
        relatedTo = ReminderAnchor.SCHEDULED,
        offset = Duration.ZERO,
        description = "Every ${interval.toMinutes()} minutes until complete",
        repeatEvery = interval,
        repeatUntilCompleted = true,
    )
}

private fun ReminderSpec.alertForEdit(): ReminderAlert {
    return alert ?: ReminderAlert(style = "fullscreen", audioLoop = true, audioUntil = "dismiss", allowOverlay = false)
}

private fun ReminderSpec.withAlert(alert: ReminderAlert): ReminderSpec {
    val normalized = alert.takeIf {
        it.style == "fullscreen" ||
            !it.note.isNullOrBlank() ||
            !it.image.isNullOrBlank() ||
            !it.audio.isNullOrBlank() ||
            it.allowOverlay ||
            it.audioLoop != true ||
            it.audioUntil != "dismiss"
    }
    return when (this) {
        is ReminderSpec.Absolute -> copy(alert = normalized)
        is ReminderSpec.Relative -> copy(alert = normalized)
    }
}

private fun copyPickedMedia(context: Context, vaultPath: String, uri: Uri, prefix: String): String? {
    val vault = File(vaultPath)
    if (!vault.isDirectory) return null
    val mediaDir = File(vault, "TaskNotes Companion/Media").apply { mkdirs() }
    val extension = context.contentResolver.getType(uri)
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: if (prefix == "image") "jpg" else "mp3"
    val target = uniqueMediaFile(mediaDir, "${prefix}_${System.currentTimeMillis()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    return target.relativeTo(vault).path.replace('\\', '/')
}

private fun uniqueMediaFile(folder: File, name: String): File {
    var candidate = File(folder, name)
    var index = 2
    while (candidate.exists()) {
        candidate = File(folder, "${name.substringBeforeLast('.')}_$index.${name.substringAfterLast('.', "")}")
        index++
    }
    return candidate
}

private fun ReminderSpec.describe(): String {
    return when (this) {
        is ReminderSpec.Absolute -> "At $absoluteTime${description?.let { " - $it" } ?: ""}"
        is ReminderSpec.Relative -> "${offset.toHumanOffset()} ${relatedTo.name.lowercase()}${repeatEvery?.let { ", repeats every ${it.toHumanValue()} until complete" } ?: ""}${description?.let { " - $it" } ?: ""}"
    }
}

private fun Duration.toHumanOffset(): String {
    val prefix = if (isNegative) "before" else "after"
    val abs = if (isNegative) negated() else this
    val value = when {
        abs.toDays() > 0 -> "${abs.toDays()}d"
        abs.toHours() > 0 -> "${abs.toHours()}h"
        else -> "${abs.toMinutes()}m"
    }
    return "$value $prefix"
}

private fun Duration.toHumanValue(): String {
    return when {
        toDays() > 0 -> "${toDays()}d"
        toHours() > 0 -> "${toHours()}h"
        else -> "${toMinutes()}m"
    }
}

private enum class DateEditorField { SCHEDULED, DUE }

private fun formatDateWithTime(date: LocalDate, time: LocalTime?): String {
    return if (time == null) date.toString() else "$date ${time.withSecond(0).withNano(0)}"
}

@Composable
private fun TimeField(
    label: String,
    date: LocalDate?,
    value: LocalTime?,
    onValue: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InlineTimePicker(
            label = label,
            value = value ?: LocalTime.of(9, 0),
            onValue = onValue,
            enabled = date != null,
        )
        TextButton(onClick = { onValue(null) }, enabled = date != null && value != null, modifier = Modifier.fillMaxWidth()) {
            Text("Clear time")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineTimePicker(
    label: String,
    value: LocalTime,
    onValue: (LocalTime) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(value.toString())
        }
    }
    if (open) {
        ClockTimePickerDialog(
            title = label,
            initialTime = value,
            onDismiss = { open = false },
            onConfirm = {
                onValue(it)
                open = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockTimePickerDialog(
    title: String,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true,
    )
    var textEntry by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { textEntry = !textEntry },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "%02d:%02d".format(state.hour, state.minute),
                        style = MaterialTheme.typography.displayMedium,
                    )
                }
                if (textEntry) {
                    TimeInput(state = state, modifier = Modifier.fillMaxWidth())
                } else {
                    TimePicker(state = state, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    value: LocalDate?,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(value?.toString() ?: "Pick date")
        }
        if (value != null) {
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun InlineCalendar(
    visibleMonth: YearMonth,
    selected: LocalDate?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPrevious) { Text("<") }
                Text(
                    "${visibleMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${visibleMonth.year}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onNext) { Text(">") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
            }

            val first = visibleMonth.atDay(1)
            val leadingBlanks = first.dayOfWeek.value - 1
            val days = (1..visibleMonth.lengthOfMonth()).map { visibleMonth.atDay(it) }
            val cells = List(leadingBlanks) { null } + days
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (week + List(7 - week.size) { null }).forEach { day ->
                        if (day == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val isSelected = day == selected
                            if (isSelected) {
                                Button(onClick = { onSelect(day) }, modifier = Modifier.weight(1f)) {
                                    Text(day.dayOfMonth.toString())
                                }
                            } else {
                                TextButton(onClick = { onSelect(day) }, modifier = Modifier.weight(1f)) {
                                    Text(day.dayOfMonth.toString())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: MainState,
    onSave: (TaskMappingConfig) -> Unit,
    onNotificationSave: (NotificationPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(state.config) }
    var notificationPrefs by remember { mutableStateOf(state.notificationPreferences) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { onSave(config); onNotificationSave(notificationPrefs); onBack() }) { Icon(Icons.Default.Save, "Save") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetectionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = config.detectionMode == mode,
                            onClick = { config = config.copy(detectionMode = mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            item { SettingsField("Detection tag", config.detectionTag) { config = config.copy(detectionTag = it) } }
            item { SettingsField("Detection property", config.detectionProperty) { config = config.copy(detectionProperty = it) } }
            item { SettingsField("Detection value", config.detectionPropertyValue) { config = config.copy(detectionPropertyValue = it) } }
            item { SettingsField("Task folder", config.taskFolder) { config = config.copy(taskFolder = it) } }
            item { SettingsField("Status field", config.statusField) { config = config.copy(statusField = it) } }
            item { SettingsField("Done status", config.doneStatus) { config = config.copy(doneStatus = it) } }
            item { SettingsField("Due field", config.dueField) { config = config.copy(dueField = it) } }
            item { SettingsField("Reminder field", config.remindersField) { config = config.copy(remindersField = it) } }
            item { Text("Notifications", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedButton(
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:${context.packageName}"))
                        } else {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        ContextCompat.startActivity(context, intent, null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Full-screen reminder permission")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        ContextCompat.startActivity(
                            context,
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(Uri.parse("package:${context.packageName}")),
                            null,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Overlay reminder permission")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = notificationPrefs.remindersEnabled,
                        onClick = { notificationPrefs = notificationPrefs.copy(remindersEnabled = !notificationPrefs.remindersEnabled) },
                        label = { Text("Reminders") },
                    )
                    FilterChip(
                        selected = notificationPrefs.dailySummaryEnabled,
                        onClick = { notificationPrefs = notificationPrefs.copy(dailySummaryEnabled = !notificationPrefs.dailySummaryEnabled) },
                        label = { Text("Daily summary") },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = notificationPrefs.overdueReminderEnabled,
                        onClick = { notificationPrefs = notificationPrefs.copy(overdueReminderEnabled = !notificationPrefs.overdueReminderEnabled) },
                        label = { Text("Due alerts") },
                    )
                    FilterChip(
                        selected = notificationPrefs.badgeEnabled,
                        onClick = { notificationPrefs = notificationPrefs.copy(badgeEnabled = !notificationPrefs.badgeEnabled) },
                        label = { Text("Badge") },
                    )
                }
            }
            item {
                SettingsField("Date-only reminder time", notificationPrefs.dateOnlyAnchorTime.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time ->
                        notificationPrefs = notificationPrefs.copy(dateOnlyAnchorTime = time)
                    }
                }
            }
            item {
                SettingsField("Daily summary time", notificationPrefs.dailySummaryTime.toString()) {
                    runCatching { LocalTime.parse(it) }.getOrNull()?.let { time ->
                        notificationPrefs = notificationPrefs.copy(dailySummaryTime = time)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Default reminders", style = MaterialTheme.typography.titleSmall)
                    Text(
                        notificationPrefs.defaultReminders.joinToString { it.describe() }.ifBlank { "None" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            notificationPrefs = notificationPrefs.copy(
                                defaultReminders = notificationPrefs.defaultReminders + quickRelative(ReminderAnchor.DUE, Duration.ofMinutes(-15), "15m before due"),
                            )
                        }) { Text("Add due -15m") }
                        TextButton(onClick = {
                            notificationPrefs = notificationPrefs.copy(
                                defaultReminders = notificationPrefs.defaultReminders + quickRelative(ReminderAnchor.SCHEDULED, Duration.ofMinutes(-15), "15m before scheduled"),
                            )
                        }) { Text("Add scheduled -15m") }
                    }
                    TextButton(onClick = { notificationPrefs = notificationPrefs.copy(defaultReminders = emptyList()) }) {
                        Text("Clear defaults")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

private fun resolveTreePath(context: android.content.Context, uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val type = parts.firstOrNull() ?: return null
    val relative = parts.getOrNull(1).orEmpty()
    return when (type.lowercase()) {
        "primary" -> File(Environment.getExternalStorageDirectory(), relative).absolutePath
        else -> null
    }
}
