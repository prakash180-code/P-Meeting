package com.prakash.pmeeting.ui.transcript

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionSegment
import com.prakash.pmeeting.data.repository.RecordingRepository
import com.prakash.pmeeting.playback.MediaPlayback
import com.prakash.pmeeting.playback.PlayerState
import com.prakash.pmeeting.transcription.AutoTranscription
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranscriptViewModel(
    application: Application,
    val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val app = application as PMeetingApplication
    private val playback = MediaPlayback(application)

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

    val segments: StateFlow<List<TranscriptionSegment>> = transcription
        .map { it?.segments ?: emptyList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val playerState: StateFlow<PlayerState> = playback.state

    private val _events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var editSegmentIndex: Int = -1
    private var editedText: String = ""

    fun playPause() {
        val recording = recording.value ?: return
        playback.togglePlayPause(recording)
        startPolling()
    }

    fun seekTo(positionMs: Long) {
        playback.seekTo(positionMs)
    }

    fun attachVideoSurface(surface: android.view.Surface) {
        playback.setVideoSurface(surface)
    }

    fun detachVideoSurface(surface: android.view.Surface) {
        playback.clearVideoSurface(surface)
    }

    /** Jump playback to the start of a transcript segment. */
    fun jumpToSegment(segment: TranscriptionSegment) {
        playback.seekTo(segment.startMs)
    }

    /** Start editing a segment's text. */
    fun startEdit(index: Int, text: String) {
        editSegmentIndex = index
        editedText = text
    }

    /** Cancel editing. */
    fun cancelEdit() {
        editSegmentIndex = -1
        editedText = ""
    }

    /** Save the edited segment text. */
    fun saveEdit(index: Int, text: String) {
        if (index < 0 || text.isBlank()) {
            cancelEdit()
            return
        }
        val transcription = transcription.value ?: return
        val segments = transcription.segments.toMutableList()
        if (index >= segments.size) {
            cancelEdit()
            return
        }
        segments[index] = segments[index].copy(text = text)
        val updatedText = segments.joinToString(" ") { it.text.trim() }.trim()

        viewModelScope.launch {
            repository.upsertTranscriptionWithSegments(
                recordingId = recordingId,
                status = transcription.status,
                text = updatedText,
                segments = segments
            )
        }
        cancelEdit()
    }

    /** Delete a segment. */
    fun deleteSegment(index: Int) {
        val transcription = transcription.value ?: return
        val segments = transcription.segments.toMutableList()
        segments.removeAt(index)
        val updatedText = segments.joinToString(" ") { it.text.trim() }.trim()

        viewModelScope.launch {
            repository.upsertTranscriptionWithSegments(
                recordingId = recordingId,
                status = transcription.status,
                text = updatedText,
                segments = segments
            )
        }
    }

    /** Share the full transcript text. */
    fun shareTranscript() {
        val transcription = transcription.value ?: return
        val text = transcription.text ?: ""
        _events.tryEmit(TranscriptEvent.ShareTranscript(text))
    }

    /** Copy the full transcript text to clipboard. */
    fun copyTranscript() {
        val transcription = transcription.value ?: return
        val text = transcription.text ?: ""
        _events.tryEmit(TranscriptEvent.CopyTranscript(text))
    }

    /**
     * Explicit user retry/re-transcribe. Uses the same unique background job as
     * automatic transcription (force replaces a finished/failed run), so the
     * screen can be left immediately and the work continues in the background.
     */
    fun retryTranscription() {
        val status = transcription.value?.status
        if (status == com.prakash.pmeeting.data.model.TranscriptionStatus.PENDING ||
            status == com.prakash.pmeeting.data.model.TranscriptionStatus.PROCESSING
        ) {
            return
        }
        viewModelScope.launch {
            AutoTranscription.enqueue(
                context = app,
                repository = repository,
                recordingId = recordingId,
                force = true
            )
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
        stopPolling()
        super.onCleared()
    }
}

sealed interface TranscriptEvent {
    data class ShareTranscript(val text: String) : TranscriptEvent
    data class CopyTranscript(val text: String) : TranscriptEvent
}
