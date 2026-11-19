package com.prakash.pmeeting.ui.video

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.recording.FinalizedVideo
import com.prakash.pmeeting.recording.RecorderStatus
import com.prakash.pmeeting.recording.RecordingFinalizer
import com.prakash.pmeeting.recording.RecordingForegroundService
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
    val draft: Draft? = null,
    /** True while a draft is being persisted; blocks another stop/save. */
    val saving: Boolean = false
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
    data object SaveFailed : VideoRecordEvent
}

class VideoRecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val app = application
    private val appScope = (application as PMeetingApplication).applicationScope

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

    /**
     * Bridges CameraX recorder events into the UI state. The finalize path is
     * idempotent: the recorder guarantees exactly one finalize, but a duplicate
     * from a late callback must not overwrite a draft already awaiting save.
     */
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

    /** Called by the recorder even when the event flow has no collector. */
    fun onFinalized(result: FinalizedVideo) {
        stopTicker()
        handleFinalized(result)
    }

    fun discardDraft() {
        val draft = _uiState.value.draft ?: return
        if (_uiState.value.saving) return
        // Use the application scope so the delete completes even if the screen
        // is torn down right after the user cancels.
        appScope.launch {
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
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, error = null) }
        // Application scope: navigation can clear the ViewModel before the
        // Room insert finishes, which previously lost the take.
        appScope.launch {
            val id = runCatching { repository.save(draft.toRecording(title, MediaType.VIDEO, "video/mp4")) }
                .getOrNull()
            if (id == null) {
                _uiState.update {
                    it.copy(
                        saving = false,
                        error = "Could not save recording. The file was kept; try again."
                    )
                }
                _events.tryEmit(VideoRecordEvent.SaveFailed)
                return@launch
            }
            repository.updateSize(id)
            _uiState.update { it.copy(draft = null, saving = false) }
            // The video is finalized and saved; queue background transcription.
            AutoTranscription.enqueue(app, repository, id)
            _events.tryEmit(VideoRecordEvent.RecordingSaved)
        }
    }

    /**
     * Called when the app is backgrounded or the screen leaves composition.
     * If a take already finalized into a draft, it is persisted immediately
     * with its suggested title (guarded so repeated callbacks cannot create a
     * duplicate). If a recording is still active, the next finalize is saved
     * automatically.
     */
    fun markAutoSavePending() {
        val state = _uiState.value
        if (state.saving) return
        val draft = state.draft
        if (draft != null) {
            // Finalized but not yet saved: persist now so a background/dispose
            // cannot orphan the file. saveDraft is idempotent while saving.
            saveDraft(draft.suggestedTitle)
            return
        }
        if (state.status != RecorderStatus.IDLE) {
            autoSaveOnFinalize = true
        }
    }

    fun clearAutoSavePending() {
        autoSaveOnFinalize = false
    }

    private fun handleFinalized(result: FinalizedVideo) {
        RecordingForegroundService.stop(app)
        val autoSave = autoSaveOnFinalize
        autoSaveOnFinalize = false
        val file = result.file
        val fileIsUsable = file != null && RecordingFinalizer.isUsableTake(
            fileExists = file.exists(),
            lengthBytes = file.length(),
            errorMessage = null
        )
        // A CameraX error can still leave a non-empty file. Keep it as a
        // retryable draft instead of dropping a take the user can save.
        val recoveredFromError = result.errorMessage != null && fileIsUsable
        if (result.errorMessage != null && !recoveredFromError) {
            _uiState.update {
                it.copy(
                    status = RecorderStatus.IDLE,
                    saving = false,
                    error = result.errorMessage
                )
            }
            return
        }
        if (!fileIsUsable) {
            _uiState.update {
                it.copy(
                    status = RecorderStatus.IDLE,
                    saving = false,
                    error = result.errorMessage
                        ?: "Recording failed: the video file was not produced."
                )
            }
            return
        }
        // A second finalize while a draft is pending: the recorder must never
        // produce one, but guard against an orphan file if it ever does.
        val previous = _uiState.value.draft
        if (previous != null) {
            appScope.launch { repository.deleteInternalFile(previous.fileUri) }
        }
        val draft = VideoRecordUiState.Draft(
            fileUri = file.absolutePath,
            durationMs = result.durationMs,
            suggestedTitle = "Meeting ${Formats.formatDate(System.currentTimeMillis())}"
        )
        _uiState.update {
            it.copy(
                status = RecorderStatus.IDLE,
                draft = draft,
                saving = false,
                error = if (recoveredFromError) {
                    "Recording reported an error but produced a usable file. " +
                        "Review it before saving."
                } else {
                    null
                }
            )
        }
        if (autoSave && !recoveredFromError) {
            // App was left while recording; keep the take with a default title.
            saveDraft(draft.suggestedTitle)
        }
    }

    private fun VideoRecordUiState.Draft.toRecording(title: String, mediaType: MediaType, mimeType: String) =
        Recording(
            id = 0L,
            title = title,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            mediaType = mediaType,
            source = RecordingSource.RECORDED,
            storageKind = StorageKind.INTERNAL_FILE,
            fileUri = fileUri,
            mimeType = mimeType,
            sizeBytes = 0L
        )

    /**
     * Safety net: the screen's dispose already stops and auto-saves an active
     * take, but the foreground session must never outlive the ViewModel.
     */
    override fun onCleared() {
        RecordingForegroundService.stop(app)
        super.onCleared()
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