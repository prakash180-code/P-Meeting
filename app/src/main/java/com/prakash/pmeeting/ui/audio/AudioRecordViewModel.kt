package com.prakash.pmeeting.ui.audio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.recording.AudioRecorder
import com.prakash.pmeeting.recording.RecorderStatus
import com.prakash.pmeeting.recording.RecordingFinalizer
import com.prakash.pmeeting.recording.RecordingForegroundService
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

data class AudioRecordUiState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0L,
    val micPermissionGranted: Boolean = false,
    val error: String? = null,
    /** Set after a successful stop; the UI then shows the save dialog. */
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

sealed interface AudioRecordEvent {
    data object RecordingSaved : AudioRecordEvent
    data object DraftDiscarded : AudioRecordEvent
    data object SaveFailed : AudioRecordEvent
}

class AudioRecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val transcriptionService = (application as PMeetingApplication).container.transcriptionService
    private val recorder = AudioRecorder(application)
    private val app = application
    private val appScope = (application as PMeetingApplication).applicationScope
    private val audioManager =
        application.getSystemService(Application.AUDIO_SERVICE) as AudioManager

    private val clock = AccrualClock()

    private val _uiState = MutableStateFlow(AudioRecordUiState())
    val uiState: StateFlow<AudioRecordUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AudioRecordEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AudioRecordEvent> = _events.asSharedFlow()

    private var tickerJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByInterruption = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByInterruption = false
                stopAsDraft()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_uiState.value.status == RecorderStatus.RECORDING) {
                    pausedByInterruption = true
                    pauseRecording()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByInterruption) resumeRecording()
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                micPermissionGranted = granted,
                error = if (granted) {
                    null
                } else {
                    "Microphone permission is required to record audio. " +
                        "It was denied; please enable it to continue."
                }
            )
        }
    }

    fun startRecording() {
        val current = _uiState.value
        if (current.status != RecorderStatus.IDLE || current.draft != null) return
        if (!current.micPermissionGranted) {
            _uiState.update {
                it.copy(error = "Microphone permission is required to record audio.")
            }
            return
        }
        _uiState.update { it.copy(error = null) }
        clock.start()
        recorder.start(repository.mediaDir(MediaType.AUDIO)).fold(
            onSuccess = {
                lastStoppedFile = null
                requestAudioFocus()
                // Keeps the microphone session valid and the CPU awake while
                // the screen is locked, so a meeting is not cut off by the
                // display timeout.
                RecordingForegroundService.start(app, video = false)
                _uiState.update { it.copy(status = RecorderStatus.RECORDING, elapsedMs = 0L) }
                startTicker()
            },
            onFailure = { error ->
                clock.reset()
                _uiState.update {
                    it.copy(
                        error = "Could not start recording: ${error.message ?: "unknown error"}"
                    )
                }
            }
        )
    }

    fun pauseRecording() {
        if (_uiState.value.status != RecorderStatus.RECORDING) return
        recorder.pause()
        clock.pause()
        _uiState.update { it.copy(status = RecorderStatus.PAUSED, elapsedMs = clock.elapsedMs()) }
    }

    fun resumeRecording() {
        if (_uiState.value.status != RecorderStatus.PAUSED) return
        if (recorder.resume()) {
            pausedByInterruption = false
            clock.resume()
            _uiState.update { it.copy(status = RecorderStatus.RECORDING) }
        } else {
            _uiState.update { it.copy(error = "Could not resume recording. Stopping instead.") }
            stopAsDraft()
        }
    }

    /** User pressed stop: finalize file and show the save dialog. */
    fun stopAndShowSave() {
        if (_uiState.value.status == RecorderStatus.IDLE) return
        if (_uiState.value.draft != null || _uiState.value.saving) return
        stopAsDraft()
    }

    fun discardDraft() {
        val draft = _uiState.value.draft ?: return
        if (_uiState.value.saving) return
        // Application scope so the delete is not cancelled by navigation.
        appScope.launch {
            repository.deleteInternalFile(draft.fileUri)
            _uiState.update { it.copy(draft = null, elapsedMs = 0L) }
            clock.reset()
            _events.tryEmit(AudioRecordEvent.DraftDiscarded)
        }
    }

    fun saveDraft(title: String) {
        val draft = _uiState.value.draft ?: return
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, error = null) }
        // Application scope: navigation can clear this ViewModel before the
        // Room insert completes, which previously lost the take.
        appScope.launch {
            val id = runCatching { repository.save(draft.toRecording(title)) }.getOrNull()
            if (id == null) {
                _uiState.update {
                    it.copy(
                        saving = false,
                        error = "Could not save recording. The file was kept; try again."
                    )
                }
                _events.tryEmit(AudioRecordEvent.SaveFailed)
                return@launch
            }
            repository.updateSize(id)
            _uiState.update { it.copy(draft = null, saving = false) }
            // Automatic background transcription: the file is finalized
            // and saved at this point, never while recording.
            AutoTranscription.enqueue(app, repository, id)
            _events.tryEmit(AudioRecordEvent.RecordingSaved)
        }
    }

    /**
     * Back navigation while recording/paused: stop, finalize and persist with
     * a default title, then report success so the screen can leave. The save
     * runs on the application scope so it survives ViewModel teardown.
     */
    fun stopAndSaveAndExit() {
        val state = _uiState.value
        if (state.saving) return
        if (state.status == RecorderStatus.IDLE) {
            // A draft means the user already stopped and is being asked for a
            // title; the dialog owns the exit decision (save or discard).
            if (state.draft == null) _events.tryEmit(AudioRecordEvent.RecordingSaved)
            return
        }
        if (!stopRecordingInternal()) return
        val elapsed = clock.elapsedMs()
        val file = lastStoppedFile
        clock.reset()
        if (file == null) {
            _uiState.update { it.copy(status = RecorderStatus.IDLE, elapsedMs = 0L) }
            _events.tryEmit(AudioRecordEvent.SaveFailed)
            return
        }
        _uiState.update { it.copy(status = RecorderStatus.IDLE, elapsedMs = 0L, saving = true) }
        appScope.launch {
            val id = runCatching {
                repository.save(
                    AudioRecordUiState.Draft(
                        fileUri = file.absolutePath,
                        durationMs = elapsed,
                        suggestedTitle = ""
                    ).toRecording(defaultTitle())
                )
            }.getOrNull()
            if (id == null) {
                // Keep the take as a draft so the user can retry the save or
                // discard it explicitly; never delete the file on failure.
                _uiState.update {
                    it.copy(
                        saving = false,
                        draft = AudioRecordUiState.Draft(
                            fileUri = file.absolutePath,
                            durationMs = elapsed,
                            suggestedTitle = defaultTitle()
                        ),
                        error = "Could not save recording. The file was kept; try again."
                    )
                }
                _events.tryEmit(AudioRecordEvent.SaveFailed)
                return@launch
            }
            repository.updateSize(id)
            _uiState.update { it.copy(saving = false) }
            AutoTranscription.enqueue(app, repository, id)
            _events.tryEmit(AudioRecordEvent.RecordingSaved)
        }
    }

    /**
     * Called when the screen leaves composition or the app goes to background
     * mid-recording: the file is kept and saved automatically with a default
     * title so nothing recorded is silently lost.
     */
    fun finishAndAutoSave() {
        val state = _uiState.value
        val draft = state.draft
        if (draft != null) {
            // A take already stopped and waiting for a title: persist it with
            // its suggested title rather than leaving an orphan file.
            if (state.saving) return
            _uiState.update { it.copy(saving = true) }
            appScope.launch {
                val id = runCatching { repository.save(draft.toRecording(draft.suggestedTitle)) }
                    .getOrNull()
                if (id == null) {
                    _uiState.update {
                        it.copy(
                            saving = false,
                            error = "Could not save recording. The file was kept; try again."
                        )
                    }
                    _events.tryEmit(AudioRecordEvent.SaveFailed)
                    return@launch
                }
                repository.updateSize(id)
                _uiState.update { it.copy(draft = null, saving = false, elapsedMs = 0L) }
                clock.reset()
                AutoTranscription.enqueue(app, repository, id)
            }
            return
        }
        if (state.status == RecorderStatus.IDLE) return

        if (!stopRecordingInternal()) return
        val elapsed = clock.elapsedMs()
        val file = lastStoppedFile
        clock.reset()
        if (file == null) {
            _uiState.update { it.copy(status = RecorderStatus.IDLE, elapsedMs = 0L) }
            return
        }
        _uiState.update { it.copy(status = RecorderStatus.IDLE, elapsedMs = 0L, saving = true) }
        appScope.launch {
            val id = runCatching {
                repository.save(
                    AudioRecordUiState.Draft(
                        fileUri = file.absolutePath,
                        durationMs = elapsed,
                        suggestedTitle = ""
                    ).toRecording(defaultTitle())
                )
            }.getOrNull()
            if (id == null) {
                // Keep the file and surface the failure; never delete it here.
                _uiState.update {
                    it.copy(
                        saving = false,
                        error = "Could not save recording. The file was kept; try again."
                    )
                }
                _events.tryEmit(AudioRecordEvent.SaveFailed)
                return@launch
            }
            repository.updateSize(id)
            _uiState.update { it.copy(saving = false) }
            AutoTranscription.enqueue(app, repository, id)
        }
    }

    /**
     * Safety net for teardown that skips [onDispose] (for example the task being
     * removed while a take runs): never leave an unfinalized file behind.
     */
    override fun onCleared() {
        if (_uiState.value.status != RecorderStatus.IDLE) finishAndAutoSave()
        RecordingForegroundService.stop(app)
        super.onCleared()
    }

    // ----------------------------------------------------------- internals

    private var lastStoppedFile: java.io.File? = null

    /** Stops the recorder once; returns false if there was nothing to stop. */
    private fun stopRecordingInternal(): Boolean {
        val current = _uiState.value
        if (current.status == RecorderStatus.IDLE) return false
        stopTicker()
        abandonAudioFocus()
        pausedByInterruption = false
        RecordingForegroundService.stop(app)
        return recorder.stop().fold(
            onSuccess = { file ->
                if (RecordingFinalizer.isUsableTake(
                        fileExists = file.exists(),
                        lengthBytes = file.length(),
                        errorMessage = null
                    )
                ) {
                    lastStoppedFile = file
                    true
                } else {
                    // Defensive: the recorder normally rejects empty files, but
                    // never let a zero-length take become a saveable draft.
                    runCatching { file.delete() }
                    lastStoppedFile = null
                    _uiState.update {
                        it.copy(
                            status = RecorderStatus.IDLE,
                            elapsedMs = 0L,
                            error = "Recording failed: the audio file was empty."
                        )
                    }
                    false
                }
            },
            onFailure = { t ->
                lastStoppedFile = null
                _uiState.update {
                    it.copy(
                        status = RecorderStatus.IDLE,
                        elapsedMs = 0L,
                        error = "Recording failed: ${t.message ?: "unknown error"}"
                    )
                }
                false
            }
        )
    }

    private fun stopAsDraft() {
        if (_uiState.value.status == RecorderStatus.IDLE) return
        val elapsed = clock.elapsedMs()
        if (!stopRecordingInternal()) return
        val file = lastStoppedFile
        clock.reset()
        if (file == null) return
        _uiState.update {
            it.copy(
                status = RecorderStatus.IDLE,
                draft = AudioRecordUiState.Draft(
                    fileUri = file.absolutePath,
                    durationMs = elapsed,
                    suggestedTitle = defaultTitle()
                )
            )
        }
    }

    private fun defaultTitle() = "Meeting ${Formats.formatDate(System.currentTimeMillis())}"

    private fun AudioRecordUiState.Draft.toRecording(title: String) =
        Recording(
            id = 0L,
            title = title,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            mediaType = MediaType.AUDIO,
            source = RecordingSource.RECORDED,
            storageKind = StorageKind.INTERNAL_FILE,
            fileUri = fileUri,
            mimeType = "audio/mp4",
            sizeBytes = 0L
        )

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.status == RecorderStatus.RECORDING) {
                    _uiState.update { it.copy(elapsedMs = clock.elapsedMs()) }
                }
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Elapsed-time clock that keeps counting through pauses by accrual, so the
     * displayed duration survives pause/resume and interruption.
     */
    private class AccrualClock {
        private var anchor = 0L
        private var accrued = 0L
        private var running = false

        fun start() {
            accrued = 0L
            anchor = SystemClock.elapsedRealtime()
            running = true
        }

        fun pause() {
            if (running) {
                accrued += SystemClock.elapsedRealtime() - anchor
                running = false
            }
        }

        fun resume() {
            if (!running) {
                anchor = SystemClock.elapsedRealtime()
                running = true
            }
        }

        fun reset() {
            accrued = 0L
            anchor = 0L
            running = false
        }

        fun elapsedMs(): Long =
            if (running) accrued + (SystemClock.elapsedRealtime() - anchor) else accrued
    }
}