package com.prakash.pmeeting.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.minutes.MinutesService
import com.prakash.pmeeting.playback.MediaPlayback
import com.prakash.pmeeting.playback.PlayerState
import com.prakash.pmeeting.transcription.AutoTranscription
import com.prakash.pmeeting.transcription.TranscriptionLanguage
import com.prakash.pmeeting.transcription.worker.TranscriptionWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DetailViewModel(
    application: Application,
    val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val playback = MediaPlayback(application)
    private val languageStore = (application as PMeetingApplication).container.languageStore
    private val minutesService = (application as PMeetingApplication).container.minutesService
    private val actionItemService = (application as PMeetingApplication).container.actionItemService

    val recording: StateFlow<Recording?> = repository.observeRecording(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val transcription: StateFlow<Transcription?> = repository.observeTranscription(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val minutes: StateFlow<Minutes?> = repository.observeMinutes(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val actionItems: StateFlow<List<ActionItem>> = repository.observeActionItems(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val playerState: StateFlow<PlayerState> = playback.state

    /** Currently selected transcription language (persisted app-wide). */
    private val _transcriptionLanguage = MutableStateFlow(languageStore.get())
    val transcriptionLanguage: StateFlow<TranscriptionLanguage> = _transcriptionLanguage.asStateFlow()

    /** The language code passed to Whisper (null = auto-detect). */
    val transcriptionLanguageCode: String?
        get() = _transcriptionLanguage.value.langCode

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var pollJob: Job? = null

    fun playPause() {
        val recording = recording.value ?: return
        playback.togglePlayPause(recording)
        startPolling()
    }

    fun seekTo(positionMs: Long) {
        playback.seekTo(positionMs)
    }

    /** Jumps [deltaMs] forwards (positive) or backwards (negative), clamped. */
    fun seekBy(deltaMs: Long) {
        val state = playback.state.value
        val duration = state.durationMs.coerceAtLeast(recording.value?.durationMs ?: 0L)
        val target = (state.positionMs + deltaMs).coerceAtLeast(0L)
        playback.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
    }

    fun attachVideoSurface(surface: android.view.Surface) {
        playback.setVideoSurface(surface)
    }

    fun detachVideoSurface(surface: android.view.Surface) {
        playback.clearVideoSurface(surface)
    }

    fun rename(newTitle: String) {
        viewModelScope.launch {
            repository.rename(recordingId, newTitle)
        }
    }

    fun delete() {
        viewModelScope.launch {
            val recording = recording.value ?: return@launch
            playback.release()
            stopPolling()
            TranscriptionWorker.cancel(getApplication(), recordingId)
            repository.delete(recording)
            _events.tryEmit(DetailEvent.Deleted)
        }
    }

    /** True while a transcription is queued or running for this recording. */
    val isTranscribing: StateFlow<Boolean> = transcription
        .map {
            it?.status == TranscriptionStatus.PENDING ||
                it?.status == TranscriptionStatus.PROCESSING
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /**
     * Queues transcription through [AutoTranscription], which persists the
     * PENDING status and uses unique work so a manual request never duplicates
     * an automatic background job. When [force] is true a completed
     * transcription is replaced (the user explicitly chose to re-transcribe);
     * a previous successful transcript is preserved if the new run fails.
     */
    fun transcribe(language: TranscriptionLanguage, force: Boolean = false) {
        val current = transcription.value
        if (!force) {
            if (current?.status == TranscriptionStatus.PENDING ||
                current?.status == TranscriptionStatus.PROCESSING
            ) {
                return
            }
            if (current?.status == TranscriptionStatus.COMPLETED) return
        }
        // Persist the choice so the worker uses the same language.
        languageStore.set(language)
        _transcriptionLanguage.value = language
        viewModelScope.launch {
            AutoTranscription.enqueue(
                context = getApplication<Application>(),
                repository = repository,
                recordingId = recordingId,
                force = force
            )
        }
    }

    /** Re-transcribes with the currently selected language, replacing a previous transcript. */
    fun reTranscribe() {
        transcribe(languageStore.get(), force = true)
    }

    /** Persists a new transcription language choice app-wide. */
    fun setTranscriptionLanguage(language: TranscriptionLanguage) {
        languageStore.set(language)
        _transcriptionLanguage.value = language
    }

    fun generateMinutes() {
        val m = minutes.value
        if (m?.status == MinutesStatus.PENDING) return
        viewModelScope.launch {
            try {
                minutesService.generateMinutes(recordingId)
            } catch (e: Exception) {
                _events.tryEmit(DetailEvent.MinutesFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun addActionItem(text: String) {
        val itemText = text.trim()
        if (itemText.isBlank()) return
        viewModelScope.launch {
            try {
                repository.upsertActionItem(recordingId, itemText, ActionItemStatus.OPEN)
            } catch (e: Exception) {
                _events.tryEmit(DetailEvent.ActionItemFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun toggleActionItem(id: Long) {
        viewModelScope.launch {
            try {
                actionItemService.toggleActionItem(id)
            } catch (e: Exception) {
                _events.tryEmit(DetailEvent.ActionItemFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun deleteActionItem(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteActionItem(id)
            } catch (e: Exception) {
                _events.tryEmit(DetailEvent.ActionItemFailed(e.message ?: "unknown error"))
            }
        }
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                playback.refreshPosition()
                delay(250)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }
}

sealed interface DetailEvent {
    data object Deleted : DetailEvent
    data class TranscriptionFailed(val message: String) : DetailEvent
    data class MinutesFailed(val message: String) : DetailEvent
    data class ActionItemFailed(val message: String) : DetailEvent
}
