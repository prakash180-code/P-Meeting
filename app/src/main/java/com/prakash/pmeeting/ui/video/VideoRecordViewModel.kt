package com.prakash.pmeeting.ui.video

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.recording.RecorderStatus
import com.prakash.pmeeting.recording.VideoEvent
import com.prakash.pmeeting.transcription.AutoTranscription
import com.prakash.pmeeting.util.Formats
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CAMERA_PERMISSION_ERROR = "Camera permission is required to record video."

data class VideoRecordUiState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0L,
    val cameraGranted: Boolean = false,
    val micGranted: Boolean = false,
    val error: String? = null,
    /** Set after a finalize; the UI then shows the save dialog (or autosaves). */
    val draft: Draft? = null
) {
    data class Draft(
        val fileUri: String,
        val durationMs: Long,
        val suggestedTitle: String
    )
}

sealed interface VideoRecordEvent {
    data object RecordingSaved : VideoRecordEvent
    data object DraftDiscarded : VideoRecordEvent
}

class VideoRecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository

    private val _uiState = MutableStateFlow(VideoRecordUiState())
    val uiState: StateFlow<VideoRecordUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VideoRecordEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VideoRecordEvent> = _events.asSharedFlow()

    /** When set, the next finalize is saved automatically (app backgrounded). */
    private var autoSaveOnFinalize = false

    private var tickerJob: Job? = null

    /** Media directory for video files, resolved by the repository. */
    fun mediaDir() = repository.mediaDir(MediaType.VIDEO)

    fun onPermissionsResult(cameraGranted: Boolean, micGranted: Boolean) {
        _uiState.update {
            it.copy(
                cameraGranted = cameraGranted,
                micGranted = micGranted,
                error = when {
                    !cameraGranted && it.error == null -> CAMERA_PERMISSION_ERROR
                    // Clear the permission hint once the camera is available so
                    // it does not linger over a working preview.
                    cameraGranted && it.error == CAMERA_PERMISSION_ERROR -> null
                    else -> it.error
                }
            )
        }
    }

    fun bindFailed(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    /** Bridges CameraX recorder events into the UI state. */
    fun onVideoEvent(event: VideoEvent) {
        when (event) {
            is VideoEvent.RecordingStarted -> {
                _uiState.update {
                    it.copy(status = RecorderStatus.RECORDING, elapsedMs = 0L, error = null)
                }
                startTicker()
            }
            is VideoEvent.RecordingPaused -> {
                _uiState.update { it.copy(status = RecorderStatus.PAUSED) }
            }
            is VideoEvent.RecordingResumed -> {
                _uiState.update { it.copy(status = RecorderStatus.RECORDING) }
            }
            is VideoEvent.RecordingFinalized -> {
                stopTicker()
                handleFinalized(event.result)
            }
        }
    }

    fun discardDraft() {
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            if (draft.fileUri.startsWith("content://")) {
                _uiState.update { it.copy(draft = null) }
            } else {
                repository.deleteInternalFile(draft.fileUri)
                _uiState.update { it.copy(draft = null) }
            }
            _events.tryEmit(VideoRecordEvent.DraftDiscarded)
        }
    }

    fun saveDraft(title: String) {
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            runCatching {
                repository.save(
                    Recording(
                        id = 0L,
                        title = title,
                        createdAt = System.currentTimeMillis(),
                        durationMs = draft.durationMs,
                        mediaType = MediaType.VIDEO,
                        source = RecordingSource.RECORDED,
                        storageKind = StorageKind.INTERNAL_FILE,
                        fileUri = draft.fileUri,
                        mimeType = "video/mp4",
                        sizeBytes = 0L
                    )
                )
            }.onSuccess { id ->
                repository.updateSize(id)
                _uiState.update { it.copy(draft = null) }
                // The video is finalized and saved; queue background transcription.
                AutoTranscription.enqueue(getApplication<Application>(), repository, id)
                _events.tryEmit(VideoRecordEvent.RecordingSaved)
            }.onFailure { t ->
                _uiState.update {
                    it.copy(error = "Could not save recording: ${t.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Called when the app is backgrounded or the screen leaves composition:
     * the finalize that follows is saved with a default title automatically.
     */
    fun markAutoSavePending() {
        if (_uiState.value.draft == null && _uiState.value.status != RecorderStatus.IDLE) {
            autoSaveOnFinalize = true
        }
    }

    fun clearAutoSavePending() {
        autoSaveOnFinalize = false
    }

    private fun handleFinalized(result: com.prakash.pmeeting.recording.FinalizedVideo) {
        val autoSave = autoSaveOnFinalize
        autoSaveOnFinalize = false
        val file = result.file
        if (result.errorMessage != null) {
            _uiState.update {
                it.copy(
                    status = RecorderStatus.IDLE,
                    error = result.errorMessage
                )
            }
            return
        }
        if (file == null) {
            _uiState.update {
                it.copy(
                    status = RecorderStatus.IDLE,
                    error = "Recording failed: the video file was not produced."
                )
            }
            return
        }
        val draft = VideoRecordUiState.Draft(
            fileUri = file.absolutePath,
            durationMs = result.durationMs,
            suggestedTitle = "Meeting ${Formats.formatDate(System.currentTimeMillis())}"
        )
        if (_uiState.value.draft != null) {
            // A second finalize while a draft is pending: keep the newest file,
            // discard the older draft to avoid orphan files.
            val old = _uiState.value.draft
            viewModelScope.launch { repository.deleteInternalFile(old!!.fileUri) }
        }
        _uiState.update { it.copy(status = RecorderStatus.IDLE, draft = draft) }
        if (autoSave) {
            // App was left while recording; keep the take with a default title.
            saveDraft(draft.suggestedTitle)
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.status == RecorderStatus.RECORDING) {
                    if (_uiState.value.elapsedMs >= 0L) {
                        _uiState.update { it.copy(elapsedMs = it.elapsedMs + 250L) }
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
