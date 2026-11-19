package com.prakash.pmeeting.ui.transcript

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.TranscriptionSegment
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.ui.components.EmptyState
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    recordingId: Long,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: TranscriptViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return TranscriptViewModel(app, recordingId) as T
            }
        }
    )
    val context = LocalContext.current
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()

    val searchQuery = rememberSaveable { mutableStateOf("") }
    val showEdit = rememberSaveable { mutableStateOf(false) }
    val editText = rememberSaveable { mutableStateOf("") }
    val editingSegmentIndex = remember { mutableStateOf(-1) }

    val filteredSegments: List<TranscriptionSegment> = if (searchQuery.value.isNotBlank()) {
        segments.filter { it.text.contains(searchQuery.value, ignoreCase = true) }
    } else {
        segments
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TranscriptEvent.ShareTranscript -> {
                    val intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share transcript"))
                }
                is TranscriptEvent.CopyTranscript -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("transcript", event.text))
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recording?.title ?: "Transcript",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (transcription != null) {
                        IconButton(
                            onClick = { viewModel.copyTranscript() }
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript")
                        }
                        IconButton(
                            onClick = { viewModel.shareTranscript() }
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share transcript")
                        }
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        val currentRecording = recording
        val currentTranscription = transcription
        if (currentRecording == null || currentTranscription == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No transcript yet",
                    message = "Transcription is queued automatically after a recording is " +
                        "saved or imported. You can also start it from the recording screen.",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // One scrolling container only: a LazyColumn whose header rows are
            // items. Nesting a LazyColumn inside Column(verticalScroll) crashes
            // with "measured with infinity maximum height constraints".
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "recording-info") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (currentRecording.mediaType == MediaType.VIDEO) Icons.Filled.Videocam else Icons.Filled.Mic,
                            contentDescription = currentRecording.mediaType.name,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "${Formats.formatDate(currentRecording.createdAt)} · ${Formats.formatDuration(currentRecording.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                item(key = "transcription-status") {
                    when (currentTranscription.status) {
                        TranscriptionStatus.PENDING,
                        TranscriptionStatus.PROCESSING -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (currentTranscription.status ==
                                    TranscriptionStatus.PENDING
                                ) {
                                    "Queued for background transcription…"
                                } else {
                                    "Transcribing on device…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        TranscriptionStatus.WAITING_FOR_MODEL,
                        TranscriptionStatus.FAILED -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (currentTranscription.status ==
                                        TranscriptionStatus.WAITING_FOR_MODEL
                                    ) {
                                        "Waiting for a Whisper model"
                                    } else {
                                        "Transcription failed"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            currentTranscription.errorMessage?.let { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            TextButton(onClick = { viewModel.retryTranscription() }) {
                                Text("Retry transcription")
                            }
                        }

                        TranscriptionStatus.COMPLETED -> Unit
                    }
                }

                item(key = "search") {
                    OutlinedTextField(
                        value = searchQuery.value,
                        onValueChange = { searchQuery.value = it },
                        label = { Text("Search transcript") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.value.isNotEmpty()) {
                                IconButton(onClick = { searchQuery.value = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (filteredSegments.isEmpty()) {
                    item(key = "no-segments") {
                        Text(
                            text = if (searchQuery.value.isBlank()) "No transcript segments." else "No matches found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    // Keys must be unique: duplicate segment texts are common
                    // (e.g. repeated filler words), so include the position.
                    itemsIndexed(
                        items = filteredSegments,
                        key = { index, segment -> "${segment.startMs}:$index" }
                    ) { _, segment ->
                        val isPlaying = playerState.isPlaying &&
                            playerState.positionMs >= segment.startMs &&
                            playerState.positionMs <= segment.endMs

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { viewModel.jumpToSegment(segment) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Timestamp
                            Text(
                                text = Formats.formatDuration(segment.startMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(48.dp)
                            )

                            // Segment text
                            Text(
                                text = segment.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )

                            // Edit button. Resolve the position in the *full*
                            // list so editing still works while search filters.
                            IconButton(
                                onClick = {
                                    val identityIndex = segments.indexOfFirst { it === segment }
                                    // Object identity is not guaranteed to survive
                                    // recomposition/state emission, so fall back to
                                    // structural equality.
                                    val originalIndex =
                                        if (identityIndex >= 0) identityIndex else segments.indexOf(segment)
                                    if (originalIndex >= 0) {
                                        editingSegmentIndex.value = originalIndex
                                        viewModel.startEdit(originalIndex, segment.text)
                                        editText.value = segment.text
                                        showEdit.value = true
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit segment")
                            }
                        }
                    }
                }

                // Playback controls
                item(key = "playback") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.playPause() },
                            enabled = recording != null
                        ) {
                            Icon(
                                if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                            )
                        }
                        Text(
                            text = Formats.formatDuration(playerState.positionMs),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                        )
                    }
                }

                // Error message
                playerState.error?.let { error ->
                    item(key = "player-error") {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // Edit dialog
    if (showEdit.value && editingSegmentIndex.value >= 0 && editingSegmentIndex.value < segments.size) {
        AlertDialog(
            onDismissRequest = { showEdit.value = false },
            title = { Text("Edit transcript segment") },
            text = {
                OutlinedTextField(
                    value = editText.value,
                    onValueChange = { editText.value = it },
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idx = editingSegmentIndex.value
                        viewModel.saveEdit(idx, editText.value)
                        showEdit.value = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val idx = editingSegmentIndex.value
                            if (idx >= 0) viewModel.deleteSegment(idx)
                            showEdit.value = false
                        }
                    ) { Text("Delete") }
                    TextButton(
                        onClick = { viewModel.cancelEdit(); showEdit.value = false }
                    ) { Text("Cancel") }
                }
            }
        )
    }
}
