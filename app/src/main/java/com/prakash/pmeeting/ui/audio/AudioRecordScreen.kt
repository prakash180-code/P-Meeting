package com.prakash.pmeeting.ui.audio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.permissions.Permissions
import com.prakash.pmeeting.recording.RecorderStatus
import com.prakash.pmeeting.ui.components.ConsentDialog
import com.prakash.pmeeting.ui.components.TitleEditorDialog
import com.prakash.pmeeting.ui.theme.PausedAmber
import com.prakash.pmeeting.ui.theme.RecordingRed
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecordScreen(
    onDone: () -> Unit,
    viewModel: AudioRecordViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val consentAccepted = rememberSaveable { mutableStateOf(false) }
    var micGranted by rememberSaveable { mutableStateOf(Permissions.audioGranted(context)) }

    LaunchedEffect(micGranted) { viewModel.onMicPermissionResult(micGranted) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        viewModel.onMicPermissionResult(granted)
    }

    // Consent reminder before the first take, matching the video screen.
    if (!consentAccepted.value) {
        ConsentDialog(
            onContinue = { consentAccepted.value = true },
            onDismiss = onDone
        )
        return
    }

    // Leaving the screen or backgrounding the app mid-recording keeps the
    // take: it is stopped and saved with a default title. Previously the
    // microphone kept recording in the background, which contradicted the
    // documented behaviour and could never be stopped from the UI.
    //
    // Back is routed through the same stop-and-save flow instead of calling
    // onDone() immediately: navigation can clear this screen-scoped ViewModel
    // before an in-flight save completes, which lost the take.
    val requestExit: () -> Unit = {
        if (!uiState.saving) viewModel.stopAndSaveAndExit()
    }

    // System back must not bypass the stop-and-save flow either. While a draft
    // dialog is open the dialog owns back (discard); while saving, back is
    // swallowed until the save finishes.
    BackHandler(enabled = !uiState.saving && uiState.draft == null) { requestExit() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Re-read the grant: the launcher callback can be missed if
                    // the dialog is dismissed as the activity resumes.
                    micGranted = Permissions.audioGranted(context)
                }
                Lifecycle.Event.ON_STOP -> viewModel.finishAndAutoSave()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.finishAndAutoSave()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AudioRecordEvent.RecordingSaved -> onDone()
                AudioRecordEvent.SaveFailed -> Unit
                AudioRecordEvent.DraftDiscarded -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record audio") },
                navigationIcon = {
                    TextButton(
                        onClick = requestExit,
                        enabled = !uiState.saving
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusChip(status = uiState.status)

            Text(
                text = Formats.formatDuration(uiState.elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(vertical = 24.dp)
            )

            when (uiState.status) {
                RecorderStatus.IDLE -> RecorderButton(
                    active = false,
                    enabled = uiState.draft == null,
                    startLabel = "Start recording",
                    onClick = {
                        // Keep Start tappable even without the permission, and
                        // ask for it there instead of leaving a dead button.
                        if (micGranted) viewModel.startRecording()
                        else permissionLauncher.launch(Permissions.RECORD_AUDIO)
                    }
                )
                RecorderStatus.RECORDING -> {
                    RecorderButton(
                        active = false,
                        enabled = true,
                        startLabel = "Pause recording",
                        icon = Icons.Filled.Pause,
                        onClick = viewModel::pauseRecording
                    )
                    TextButton(onClick = viewModel::stopAndShowSave) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("Stop")
                    }
                }
                RecorderStatus.PAUSED -> {
                    RecorderButton(
                        active = false,
                        enabled = true,
                        startLabel = "Resume recording",
                        icon = Icons.Filled.PlayArrow,
                        onClick = viewModel::resumeRecording
                    )
                    TextButton(onClick = viewModel::stopAndShowSave) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("Stop")
                    }
                }
            }

            if (!micGranted) {
                Text(
                    text = "Microphone permission is required to record audio. " +
                        "If the prompt no longer appears, enable it in the system " +
                        "settings for P-Meeting.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                TextButton(onClick = { permissionLauncher.launch(Permissions.RECORD_AUDIO) }) {
                    Text("Request permission")
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (uiState.saving) {
                Text(
                    text = "Saving recording…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }

    uiState.draft?.let { draft ->
        TitleEditorDialog(
            dialogTitle = "Save recording",
            initialValue = draft.suggestedTitle,
            onDismiss = viewModel::discardDraft,
            onConfirm = { title -> viewModel.saveDraft(title) }
        )
    }
}

@Composable
private fun StatusChip(status: RecorderStatus) {
    val (label, color) = when (status) {
        RecorderStatus.IDLE -> "Ready to record" to MaterialTheme.colorScheme.primary
        RecorderStatus.RECORDING -> "Recording" to RecordingRed
        RecorderStatus.PAUSED -> "Paused" to PausedAmber
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = color, shape = CircleShape)
                )
                Text(label, modifier = Modifier.padding(start = 6.dp))
            }
        }
    )
}

@Composable
private fun RecorderButton(
    active: Boolean,
    enabled: Boolean,
    startLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Mic,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(84.dp)
            .semantics { contentDescription = startLabel },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) RecordingRed else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
    }
}
