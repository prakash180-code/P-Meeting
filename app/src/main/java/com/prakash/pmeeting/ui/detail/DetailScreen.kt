package com.prakash.pmeeting.ui.detail

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.playback.PlayerState
import com.prakash.pmeeting.transcription.TranscriptionLanguage
import com.prakash.pmeeting.ui.components.ConfirmDialog
import com.prakash.pmeeting.ui.components.TitleEditorDialog
import com.prakash.pmeeting.ui.components.recordingSubtitle
import com.prakash.pmeeting.ui.components.RecordingTypeIcon
import com.prakash.pmeeting.util.Formats
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    recordingId: Long,
    onViewTranscript: () -> Unit = {},
    onViewMinutes: () -> Unit = {},
    onManageActionItems: () -> Unit = {},
    onViewEmailDraft: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: DetailViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return DetailViewModel(app, recordingId) as T
            }
        }
    )
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val transcribing by viewModel.isTranscribing.collectAsStateWithLifecycle()
    val isGeneratingMinutes by viewModel.isGeneratingMinutes.collectAsStateWithLifecycle()
    val minutes by viewModel.minutes.collectAsStateWithLifecycle()
    val actionItems by viewModel.actionItems.collectAsStateWithLifecycle()
    val transcriptionLanguage by viewModel.transcriptionLanguage.collectAsStateWithLifecycle()

    val showRename = rememberSaveable { mutableStateOf(false) }
    val showDelete = rememberSaveable { mutableStateOf(false) }
    val showOverflowMenu = remember { mutableStateOf(false) }
    val showLanguageMenu = remember { mutableStateOf(false) }
    val showAddActionItem = rememberSaveable { mutableStateOf(false) }
    val newActionItemText = rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    // Survives configuration changes, so rotating while full-screen keeps the
    // mode (and the ViewModel keeps the same MediaPlayer and position).
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    val isVideo = recording?.mediaType == MediaType.VIDEO
    val inFullScreen = isVideo && isFullScreen

    // Leaving a non-video recording must not leave the activity landscape or
    // the system bars hidden.
    LaunchedEffect(recording, isVideo) {
        if (recording != null && !isVideo) isFullScreen = false
    }

    DisposableEffect(inFullScreen, activity) {
        if (inFullScreen && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val controller = WindowCompat.getInsetsController(
                activity.window,
                activity.window.decorView
            )
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(
                    activity.window,
                    activity.window.decorView
                ).show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }

    // System back first exits full-screen instead of leaving the screen.
    BackHandler(enabled = inFullScreen) { isFullScreen = false }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Deleted -> onBack()
                DetailEvent.DeleteFileCleanupFailed -> {
                    snackbarHostState.showSnackbar(
                        "Entry removed, but its file could not be deleted."
                    )
                    onBack()
                }
                is DetailEvent.TranscriptionFailed ->
                    snackbarHostState.showSnackbar("Transcription failed: ${event.message}")
                is DetailEvent.MinutesFailed ->
                    snackbarHostState.showSnackbar("Could not generate minutes: ${event.message}")
                is DetailEvent.ActionItemFailed ->
                    snackbarHostState.showSnackbar("Action item change failed: ${event.message}")
            }
        }
    }

    val currentForDialogs = recording
    if (inFullScreen && currentForDialogs != null) {
        FullScreenVideo(
            recording = currentForDialogs,
            playerState = playerState,
            onSurfaceAvailable = viewModel::attachVideoSurface,
            onSurfaceDestroyed = viewModel::detachVideoSurface,
            onToggle = viewModel::playPause,
            onSeek = viewModel::seekTo,
            onSeekBy = viewModel::seekBy,
            onExit = { isFullScreen = false }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recording?.title ?: "Recording",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    IconButton(
                        onClick = { viewModel.transcribe(transcriptionLanguage) },
                        enabled = recording != null && !transcribing
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = if (transcribing) "Transcribing" else "Transcribe"
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showLanguageMenu.value = true },
                            enabled = recording != null
                        ) {
                            Icon(
                                Icons.Filled.Language,
                                contentDescription = "Transcription language"
                            )
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu.value,
                            onDismissRequest = { showLanguageMenu.value = false }
                        ) {
                            TranscriptionLanguage.values().forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.label) },
                                    onClick = {
                                        showLanguageMenu.value = false
                                        viewModel.setTranscriptionLanguage(lang)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { viewModel.generateMinutes() },
                        enabled = recording != null &&
                            minutes?.status != MinutesStatus.PENDING &&
                            !isGeneratingMinutes
                    ) {
                        Icon(
                            Icons.Filled.TextFields,
                            contentDescription = "Generate minutes"
                        )
                    }
                    IconButton(
                        onClick = { showAddActionItem.value = true },
                        enabled = recording != null
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add action item"
                        )
                    }
                    IconButton(
                        onClick = { onViewEmailDraft() },
                        enabled = recording != null
                    ) {
                        Icon(
                            Icons.Filled.Email,
                            contentDescription = "Email draft"
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu.value = true },
                            enabled = recording != null
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu.value,
                            onDismissRequest = { showOverflowMenu.value = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showOverflowMenu.value = false
                                    showRename.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showOverflowMenu.value = false
                                    showDelete.value = true
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        val current = recording
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            if (current.mediaType == MediaType.VIDEO) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    VideoSurface(
                        onSurfaceAvailable = viewModel::attachVideoSurface,
                        onSurfaceDestroyed = viewModel::detachVideoSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                playerState.videoAspectRatio.takeIf { it > 0f } ?: (16f / 9f)
                            )
                    )
                    IconButton(
                        onClick = { isFullScreen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "Full screen",
                            tint = Color.White
                        )
                    }
                }
            } else {
                AudioArtwork(current)
            }

            PlaybackControls(
                recording = current,
                playerState = playerState,
                onToggle = viewModel::playPause,
                onSeek = viewModel::seekTo,
                onSeekBy = viewModel::seekBy,
                onToggleFullScreen = if (current.mediaType == MediaType.VIDEO) {
                    { isFullScreen = true }
                } else {
                    null
                },
                modifier = Modifier.padding(16.dp)
            )

            MetadataList(recording = current, modifier = Modifier.padding(horizontal = 16.dp))

            if (transcribing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = if (transcription?.status == TranscriptionStatus.PROCESSING) {
                            "Transcribing on device…"
                        } else {
                            "Waiting for the background job to start…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            transcription?.let { tx ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Transcription",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val (label, color) = when (tx.status) {
                            TranscriptionStatus.PENDING ->
                                "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
                            TranscriptionStatus.PROCESSING ->
                                "Transcribing… (${transcriptionLanguage.label})" to
                                    MaterialTheme.colorScheme.primary
                            TranscriptionStatus.WAITING_FOR_MODEL ->
                                "Waiting for a Whisper model" to
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            TranscriptionStatus.COMPLETED ->
                                "Completed (${transcriptionLanguage.label})" to
                                    MaterialTheme.colorScheme.primary
                            TranscriptionStatus.FAILED ->
                                "Failed" to MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                    tx.text?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // A stale error from a previous run must not be shown while a
                    // new transcription is in progress.
                    if (tx.status != TranscriptionStatus.PENDING &&
                        tx.status != TranscriptionStatus.PROCESSING
                    ) {
                        tx.errorMessage?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (tx.status == TranscriptionStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (tx.status == TranscriptionStatus.COMPLETED) {
                            TextButton(onClick = onViewTranscript) {
                                Text("Open transcript")
                            }
                            TextButton(onClick = { viewModel.reTranscribe() }) {
                                Text("Re-transcribe")
                            }
                        } else if (tx.status == TranscriptionStatus.FAILED ||
                            tx.status == TranscriptionStatus.WAITING_FOR_MODEL
                        ) {
                            TextButton(
                                onClick = { viewModel.transcribe(transcriptionLanguage) },
                                enabled = !transcribing
                            ) {
                                Text("Retry transcription")
                            }
                        }
                    }
                }
            }

            minutes?.let { mn ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Minutes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (mn.status) {
                            MinutesStatus.PENDING -> Text(
                                text = "Pending",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MinutesStatus.COMPLETED -> Text(
                                text = "Completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            MinutesStatus.FAILED -> Text(
                                text = "Failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    mn.text?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    mn.errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (mn.status == MinutesStatus.COMPLETED) {
                        TextButton(onClick = onViewMinutes) {
                            Text("View full minutes")
                        }
                    }
                }
            }

            if (actionItems.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Action Items",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        actionItems.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.status == ActionItemStatus.COMPLETED,
                                    onCheckedChange = { viewModel.toggleActionItem(item.id) },
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                IconButton(
                                    onClick = { viewModel.deleteActionItem(item.id) }
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete action item"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (actionItems.isNotEmpty()) {
                TextButton(onClick = onManageActionItems) {
                    Text("Manage action items")
                }
            }

            playerState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }

    if (showRename.value && currentForDialogs != null) {
        TitleEditorDialog(
            dialogTitle = "Rename",
            initialValue = currentForDialogs.title,
            onDismiss = { showRename.value = false },
            onConfirm = { title ->
                viewModel.rename(title)
                showRename.value = false
            }
        )
    }
    if (showDelete.value && currentForDialogs != null) {
        val fileNote = when (currentForDialogs.storageKind) {
            StorageKind.INTERNAL_FILE -> "The recording file will be deleted from app storage."
            StorageKind.EXTERNAL_URI -> "Only the library entry is removed; the original file is not touched."
        }
        ConfirmDialog(
            title = "Delete recording?",
            message = "\"${currentForDialogs.title}\" will be removed from the library. $fileNote",
            confirmLabel = "Delete",
            onConfirm = {
                showDelete.value = false
                viewModel.delete()
            },
            onDismiss = { showDelete.value = false }
        )
    }
    if (showAddActionItem.value && currentForDialogs != null) {
        TitleEditorDialog(
            dialogTitle = "Add Action Item",
            initialValue = newActionItemText.value,
            onDismiss = {
                showAddActionItem.value = false
                newActionItemText.value = ""
            },
            onConfirm = { title ->
                viewModel.addActionItem(title)
                showAddActionItem.value = false
                newActionItemText.value = ""
            }
        )
    }
}

/**
 * The surface that renders video frames. It intentionally owns no player: the
 * same [com.prakash.pmeeting.playback.MediaPlayback] instance survives moving
 * this surface between the inline preview and the full-screen overlay.
 */
@Composable
private fun VideoSurface(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceAvailable(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        onSurfaceAvailable(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed(holder.surface)
                    }
                })
            }
        }
    )
}

/**
 * Full-screen video: landscape, system bars hidden, aspect-fitted video and
 * overlay controls that auto-hide while playing. An exit button stays visible
 * even when the other controls are hidden.
 */
@Composable
private fun FullScreenVideo(
    recording: Recording,
    playerState: PlayerState,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onExit: () -> Unit
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    val duration = playerState.durationMs.coerceAtLeast(recording.durationMs)
    val position = playerState.positionMs.coerceIn(0L, duration.coerceAtLeast(1L))

    LaunchedEffect(controlsVisible, playerState.isPlaying) {
        if (controlsVisible && playerState.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val ratio = playerState.videoAspectRatio.takeIf { it > 0f } ?: (16f / 9f)
        val containerRatio = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            ratio
        }
        // Fit inside the screen without stretching: limit width when the video
        // is wider than the screen, height when it is taller.
        val videoModifier = if (ratio >= containerRatio) {
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
        } else {
            Modifier
                .fillMaxHeight()
                .aspectRatio(ratio)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { controlsVisible = !controlsVisible }
        ) {
            VideoSurface(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = videoModifier.align(Alignment.Center)
            )

            if (controlsVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit full screen",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = recording.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.Filled.FullscreenExit,
                            contentDescription = "Exit full screen",
                            tint = Color.White
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onSeekBy(-10_000L) },
                        enabled = playerState.isPrepared
                    ) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "Back 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = onToggle,
                        enabled = playerState.error == null,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(
                        onClick = { onSeekBy(10_000L) },
                        enabled = playerState.isPrepared
                    ) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Slider(
                        value = position.toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..(if (duration > 0L) duration.toFloat() else 1f),
                        enabled = playerState.isPrepared && duration > 0L,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${Formats.formatDuration(position)} / " +
                                Formats.formatDuration(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Tap to hide controls",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                // Always-visible exit affordance, even while controls are hidden.
                IconButton(
                    onClick = onExit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = "Exit full screen",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            playerState.error?.let { error ->
                Text(
                    text = error,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioArtwork(recording: Recording) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        RecordingTypeIcon(
            mediaType = recording.mediaType,
            modifier = Modifier.size(96.dp)
        )
    }
}

@Composable
private fun PlaybackControls(
    recording: Recording,
    playerState: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggleFullScreen: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val duration = playerState.durationMs.coerceAtLeast(recording.durationMs)
        val position = playerState.positionMs.coerceIn(0L, duration.coerceAtLeast(1L))
        Slider(
            value = position.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..(if (duration > 0L) duration.toFloat() else 1f),
            enabled = playerState.isPrepared && duration > 0L
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = Formats.formatDuration(position),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = Formats.formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledIconButton(
                onClick = onToggle,
                enabled = playerState.error == null,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp)
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSeekBy(-10_000L) },
                    enabled = playerState.isPrepared
                ) {
                    Icon(
                        Icons.Filled.Replay10,
                        contentDescription = "Back 10 seconds"
                    )
                }
                IconButton(
                    onClick = { onSeekBy(10_000L) },
                    enabled = playerState.isPrepared
                ) {
                    Icon(
                        Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds"
                    )
                }
            }
            if (onToggleFullScreen != null) {
                IconButton(onClick = onToggleFullScreen) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = "Full screen"
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataList(recording: Recording, modifier: Modifier = Modifier) {
    val rows = listOf(
        "Recorded on" to Formats.formatDate(recording.createdAt),
        "Duration" to Formats.formatDuration(recording.durationMs),
        "Type" to when (recording.mediaType) {
            MediaType.AUDIO -> "Audio"
            MediaType.VIDEO -> "Video"
        },
        "Origin" to when (recording.source) {
            com.prakash.pmeeting.data.model.RecordingSource.RECORDED -> "Recorded in P-Meeting"
            com.prakash.pmeeting.data.model.RecordingSource.IMPORTED -> "Imported"
        },
        "Storage" to when (recording.storageKind) {
            StorageKind.INTERNAL_FILE -> "App storage (private to this app)"
            StorageKind.EXTERNAL_URI ->
                "Linked file from the device; original stays in place"
        },
        "File size" to if (recording.sizeBytes > 0L) Formats.formatFileSize(recording.sizeBytes) else "Unknown",
        "Format" to recording.mimeType.ifBlank { "Unknown" }
    )
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Finds the hosting Activity through ContextWrappers (Compose provides a wrapper). */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
