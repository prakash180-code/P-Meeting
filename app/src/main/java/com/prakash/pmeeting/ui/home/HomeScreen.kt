package com.prakash.pmeeting.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.ui.components.ConfirmDialog
import com.prakash.pmeeting.ui.components.EmptyState
import com.prakash.pmeeting.ui.components.SearchField
import com.prakash.pmeeting.ui.components.TitleEditorDialog
import com.prakash.pmeeting.ui.components.recordingSubtitle
import com.prakash.pmeeting.ui.theme.SuccessGreen
import com.prakash.pmeeting.ui.components.RecordingTypeIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenAudioRecorder: () -> Unit,
    onOpenVideoRecorder: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTranscript: (Long) -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val transcriptionStatuses by viewModel.transcriptionStatuses.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()
    val selectedCount by viewModel.selectedCount.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val searchQueryText by viewModel.searchQueryText.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.onImportPicked(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is HomeEvent.ImportSaved -> snackbarHostState.showSnackbar("Recording imported.")
                is HomeEvent.BulkAction -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    var renameTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var bulkDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    // Never leave the bulk-delete confirmation hanging around when the
    // selection is cleared or the user leaves selection mode.
    LaunchedEffect(selectionMode, selectedIds) {
        if (!selectionMode || selectedIds.isEmpty()) bulkDeleteConfirm = false
    }

    val pageTitle = if (showArchived) "Archived Meetings" else "P-Meeting"

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            if (selectionMode) {
                SelectionTopAppBar(
                    selectedCount = selectedCount,
                    totalCount = recordings.size,
                    showArchived = showArchived,
                    onCancel = { viewModel.setSelectionMode(false) },
                    onSelectAll = { viewModel.selectAll() },
                    onClear = { viewModel.clearSelection() },
                    onInvert = { viewModel.invertSelection() },
                    onArchiveOrRestore = {
                        if (showArchived) viewModel.restoreSelected() else viewModel.archiveSelected()
                    },
                    onDelete = { bulkDeleteConfirm = true }
                )
            } else {
                TopAppBar(
                    title = { Text(pageTitle) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        TextButton(
                            onClick = { viewModel.toggleArchivedView() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (showArchived) "Show active" else "Show archived")
                        }
                    },
                    modifier = Modifier.statusBarsPadding()
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!selectionMode) {
                ActionRow(
                    onRecordAudio = onOpenAudioRecorder,
                    onRecordVideo = onOpenVideoRecorder,
                    onImport = { importLauncher.launch(arrayOf("audio/*", "video/*")) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!selectionMode) {
                SearchField(
                    value = searchQueryText,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            val list = recordings
            when {
                list.isEmpty() && searchQueryText.isBlank() && !selectionMode -> {
                    EmptyState(
                        title = if (showArchived) "No archived meetings" else "No recordings yet",
                        message = if (showArchived) {
                            "Archive meetings from the library to see them here."
                        } else {
                            "Record a meeting, or import an audio or video file. Everything stays on this device."
                        },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
                    )
                }
                list.isEmpty() -> {
                    EmptyState(
                        title = "No results",
                        message = if (searchQueryText.isNotBlank()) "Nothing matches \"${searchQueryText.trim()}\"." else "No meetings.",
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(list, key = { it.id }) { recording ->
                            val isSelected = recording.id in selectedIds
                            RecordingCard(
                                recording = recording,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                transcriptionStatus = transcriptionStatuses[recording.id],
                                onOpen = { if (selectionMode) viewModel.toggleSelection(recording.id) else onOpenRecording(recording.id) },
                                onToggleSelection = { viewModel.toggleSelection(recording.id) },
                                onOpenTranscription = { onOpenTranscript(recording.id) },
                                onRename = { if (!selectionMode) renameTarget = recording.id },
                                onDelete = { if (!selectionMode) deleteTarget = recording.id }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs driven by the recordings list snapshot -----------------------
    val listForDialogs = recordings

    pendingImport?.let { pending ->
        var copyChecked by rememberSaveable { mutableStateOf(false) }
        TitleEditorDialog(
            dialogTitle = "Import recording",
            initialValue = pending.suggestedTitle,
            onDismiss = viewModel::dismissImportDialog,
            onConfirm = { title ->
                viewModel.confirmImport(title, copyChecked)
            },
            extraContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(checked = copyChecked, onCheckedChange = { copyChecked = it })
                    Text(
                        "Copy into app storage",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    if (copyChecked) {
                        "Checked: a copy is stored inside the app, so this entry keeps working " +
                            "even if the original file is moved or deleted."
                    } else {
                        "Unchecked: the file stays where it is; P-Meeting only keeps a link. " +
                            "If the source file is moved or removed later, this entry stops working."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        )
    }

    renameTarget?.let { id ->
        val target = listForDialogs.firstOrNull { it.id == id }
        if (target == null) {
            renameTarget = null
        } else {
            TitleEditorDialog(
                dialogTitle = "Rename",
                initialValue = target.title,
                onDismiss = { renameTarget = null },
                onConfirm = { title ->
                    viewModel.rename(target, title)
                    renameTarget = null
                }
            )
        }
    }

    deleteTarget?.let { id ->
        val target = listForDialogs.firstOrNull { it.id == id }
        if (target == null) {
            deleteTarget = null
        } else {
            val fileNote = when (target.storageKind) {
                StorageKind.INTERNAL_FILE -> "The recording file will be deleted from app storage."
                StorageKind.EXTERNAL_URI -> "Only the library entry is removed; the original file is not touched."
            }
            ConfirmDialog(
                title = "Delete recording?",
                message = "\"${target.title}\" will be removed from the library. Its recording, transcript, minutes and action items will also be deleted. $fileNote",
                confirmLabel = "Delete",
                onConfirm = {
                    viewModel.delete(target)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }

    if (bulkDeleteConfirm && selectedIds.isNotEmpty()) {
        val count = selectedIds.size
        val targets = listForDialogs.filter { it.id in selectedIds }
        ConfirmDialog(
            title = "Delete $count meeting(s)?",
            message = "This will permanently delete the recordings, transcripts, minutes and action items for the $count selected meeting(s). This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteSelected()
                bulkDeleteConfirm = false
            },
            onDismiss = { bulkDeleteConfirm = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopAppBar(
    selectedCount: Int,
    totalCount: Int,
    showArchived: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onInvert: () -> Unit,
    onArchiveOrRestore: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected", maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            if (selectedCount < totalCount) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                }
            }
            if (totalCount > 0) {
                IconButton(onClick = onInvert) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = "Invert selection")
                }
            }
            if (selectedCount > 0) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Deselect, contentDescription = "Clear selection")
                }
                IconButton(onClick = onArchiveOrRestore) {
                    Icon(
                        imageVector = if (showArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = if (showArchived) "Restore selected" else "Archive selected"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
            }
        },
        modifier = Modifier.statusBarsPadding()
    )
}

@Composable
private fun ActionRow(
    onRecordAudio: () -> Unit,
    onRecordVideo: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(
            onClick = onRecordAudio,
            modifier = Modifier.weight(1f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text("Audio")
            }
        }
        TextButton(
            onClick = onRecordVideo,
            modifier = Modifier.weight(1f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.VideoCall, contentDescription = null)
                Text("Video")
            }
        }
        TextButton(
            onClick = onImport,
            modifier = Modifier.weight(1f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FileUpload, contentDescription = null)
                Text("Import")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    recording: Recording,
    isSelected: Boolean,
    selectionMode: Boolean,
    transcriptionStatus: TranscriptionStatus?,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onOpenTranscription: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else onOpen()
                },
                // Long-press is the entry point into selection mode
                // (archive / bulk delete).
                onLongClick = onToggleSelection
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            RecordingTypeIcon(mediaType = recording.mediaType)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    text = recordingSubtitle(
                        createdAt = recording.createdAt,
                        durationMs = recording.durationMs,
                        source = recording.source,
                        storageKind = recording.storageKind
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!selectionMode) {
                TranscriptionStatusBadge(
                    status = transcriptionStatus,
                    onClick = onOpenTranscription
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact transcription status badge shown immediately left of the overflow
 * menu. It reflects the persisted status only: a recording that has merely
 * been saved is never shown as completed.
 */
@Composable
private fun TranscriptionStatusBadge(
    status: TranscriptionStatus?,
    onClick: () -> Unit
) {
    val description = when (status) {
        null -> "Not transcribed"
        TranscriptionStatus.PENDING -> "Transcription queued"
        TranscriptionStatus.PROCESSING -> "Transcription in progress"
        TranscriptionStatus.WAITING_FOR_MODEL -> "Waiting for Whisper model"
        TranscriptionStatus.COMPLETED -> "Transcription complete"
        TranscriptionStatus.FAILED -> "Transcription failed"
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = description }
    ) {
        when (status) {
            TranscriptionStatus.PROCESSING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            TranscriptionStatus.COMPLETED -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
            TranscriptionStatus.FAILED -> Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            TranscriptionStatus.PENDING -> Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            TranscriptionStatus.WAITING_FOR_MODEL -> Icon(
                Icons.Filled.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            null -> Icon(
                Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
