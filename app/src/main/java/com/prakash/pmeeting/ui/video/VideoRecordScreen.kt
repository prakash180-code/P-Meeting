package com.prakash.pmeeting.ui.video

import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.permissions.Permissions
import com.prakash.pmeeting.recording.RecorderStatus
import com.prakash.pmeeting.recording.VideoEvent
import com.prakash.pmeeting.recording.VideoRecorder
import com.prakash.pmeeting.ui.components.ConsentDialog
import com.prakash.pmeeting.ui.components.TitleEditorDialog
import com.prakash.pmeeting.ui.theme.RecordingRed
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoRecordScreen(
    onDone: () -> Unit,
    viewModel: VideoRecordViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var consentAccepted by rememberSaveable { mutableStateOf(false) }
    var cameraGranted by rememberSaveable { mutableStateOf(Permissions.cameraGranted(context)) }
    var micGranted by rememberSaveable { mutableStateOf(Permissions.audioGranted(context)) }

    LaunchedEffect(cameraGranted, micGranted) {
        viewModel.onPermissionsResult(cameraGranted, micGranted)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        cameraGranted = grants[Permissions.CAMERA] == true
        micGranted = grants[Permissions.RECORD_AUDIO] == true
    }

    if (!consentAccepted) {
        ConsentDialog(
            onContinue = {
                consentAccepted = true
                val missing = mutableListOf<String>()
                if (!cameraGranted) missing.add(Permissions.CAMERA)
                if (!micGranted) missing.add(Permissions.RECORD_AUDIO)
                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            },
            onDismiss = onDone
        )
        return
    }

    val previewView = remember { PreviewView(context) }
    val recorder = remember { VideoRecorder(context, lifecycleOwner) }

    // Rebind key: incremented when the user flips the camera.
    var rebindKey by remember { mutableIntStateOf(0) }

    // Leaving mid-recording must never drop the take: stop it, save it with a
    // default title, then pop once the save completes. Without this, closing
    // the screen while recording orphaned the .mp4 and lost the recording.
    var finishing by remember { mutableStateOf(false) }
    // The recorder delivers finalize directly; the event flow has no replay, so
    // without this listener a teardown-time finalize would be silently lost.
    // It is intentionally not cleared on dispose: if the activity is destroyed
    // while a take is still finalizing, the callback must still reach the
    // ViewModel so the application-scoped save can persist it.
    DisposableEffect(recorder, viewModel) {
        recorder.setFinalizeListener { result -> viewModel.onFinalized(result) }
        onDispose { }
    }

    // Finalize is delivered through the direct listener above; forwarding it
    // from the event flow too would process the same take twice. This collector
    // only handles start/pause/resume.
    LaunchedEffect(recorder, viewModel) {
        recorder.eventFlow.collect { event ->
            if (event !is VideoEvent.RecordingFinalized) viewModel.onVideoEvent(event)
        }
    }
    val requestExit: () -> Unit = {
        when {
            uiState.saving -> Unit // a save is already in flight; onDone follows it
            uiState.draft != null -> Unit // the save/discard dialog owns exit
            uiState.status == RecorderStatus.IDLE -> onDone()
            finishing -> Unit
            else -> {
                finishing = true
                viewModel.markAutoSavePending()
                recorder.stopRecording()
            }
        }
    }

    // Back is ignored while recording (the take must be saved first); when the
    // save/discard dialog is open the dialog owns back (dismiss = discard).
    BackHandler(enabled = uiState.status != RecorderStatus.IDLE && uiState.draft == null) {
        requestExit()
    }

    // Re-arm the exit guard once the recorder is idle and no save is running.
    LaunchedEffect(uiState.status, uiState.saving) {
        if (uiState.status == RecorderStatus.IDLE && !uiState.saving) finishing = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Re-read permissions: the launcher result callback can be
                    // missed if the permission dialog is dismissed right as it
                    // returns, which left the screen stuck on "permission is
                    // required" until the user re-entered the screen.
                    cameraGranted = Permissions.cameraGranted(context)
                    micGranted = Permissions.audioGranted(context)
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.markAutoSavePending()
                    recorder.stopRecording()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // If the activity is destroyed while a take is still finalizing,
            // keep the auto-save intent so the finalize (delivered through the
            // direct listener) persists it with a default title.
            viewModel.markAutoSavePending()
            recorder.close()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // Only a successful persist ends the screen. On failure the draft
            // stays put (with the error shown) so the take can be retried.
            if (event is VideoRecordEvent.RecordingSaved) onDone()
        }
    }

    LaunchedEffect(recorder, rebindKey, cameraGranted) {
        if (!cameraGranted) return@LaunchedEffect
        runCatching {
            recorder.setPreviewSurface(previewView.surfaceProvider)
            recorder.bind()
        }.onFailure { t ->
            viewModel.bindFailed(t.message ?: "The camera could not be started.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record video") },
                navigationIcon = {
                    TextButton(onClick = requestExit) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!cameraGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Camera permission is required to record video. " +
                            "Audio is included only when the microphone permission is granted.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(Permissions.CAMERA, Permissions.RECORD_AUDIO)
                        )
                    }) { Text("Request permission") }
                }
            } else {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = Formats.formatDuration(uiState.elapsedMs),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = when (uiState.status) {
                            RecorderStatus.IDLE -> "Ready"
                            RecorderStatus.RECORDING -> "Recording"
                            RecorderStatus.PAUSED -> "Paused"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        IconButton(
                            onClick = {
                                recorder.setLensFacing(
                                    if (recorder.currentLensFacing ==
                                        CameraSelector.LENS_FACING_BACK
                                    ) {
                                        CameraSelector.LENS_FACING_FRONT
                                    } else {
                                        CameraSelector.LENS_FACING_BACK
                                    }
                                )
                                rebindKey++
                            },
                            enabled = uiState.status == RecorderStatus.IDLE
                        ) {
                            Icon(
                                Icons.Filled.FlipCameraAndroid,
                                contentDescription = "Flip camera",
                                tint = Color.White
                            )
                        }

                        FilledIconButton(
                            onClick = {
                                if (uiState.status == RecorderStatus.IDLE) {
                                    recorder.startRecording(
                                        directory = viewModel.mediaDir(),
                                        withAudio = micGranted,
                                        displayRotation =
                                            previewView.display?.rotation
                                                ?: Surface.ROTATION_0
                                    )
                                }
                            },
                            enabled = uiState.status == RecorderStatus.IDLE,
                            modifier = Modifier.size(84.dp),
                            colors = androidx.compose.material3.IconButtonDefaults
                                .filledIconButtonColors(
                                    containerColor = RecordingRed
                                )
                        ) {
                            Icon(
                                Icons.Filled.VideoCall,
                                contentDescription = "Start recording",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        when (uiState.status) {
                            RecorderStatus.RECORDING -> IconButton(
                                onClick = recorder::pauseRecording
                            ) {
                                Icon(
                                    Icons.Filled.Pause,
                                    contentDescription = "Pause recording",
                                    tint = Color.White
                                )
                            }
                            RecorderStatus.PAUSED -> IconButton(
                                onClick = recorder::resumeRecording
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Resume recording",
                                    tint = Color.White
                                )
                            }
                            RecorderStatus.IDLE -> IconButton(
                                onClick = {},
                                enabled = false
                            ) {
                                Icon(
                                    Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = Color.Transparent
                                )
                            }
                        }

                        if (uiState.status != RecorderStatus.IDLE) {
                            TextButton(onClick = recorder::stopRecording) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Text("Stop", color = Color.White)
                            }
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(24.dp)
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
