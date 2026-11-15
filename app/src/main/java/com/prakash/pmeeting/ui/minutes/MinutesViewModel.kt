package com.prakash.pmeeting.ui.minutes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.minutes.MinutesService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class MinutesUiState(
    val minutes: Minutes? = null,
    /** True while a generation request is running; reflects in the UI. */
    val isGenerating: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

sealed interface MinutesEvent {
    data class GenerationFailed(val message: String) : MinutesEvent
    data class SaveFailed(val message: String) : MinutesEvent
    data class DeleteFailed(val message: String) : MinutesEvent
}

class MinutesViewModel(
    application: Application,
    private val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val minutesService = (application as PMeetingApplication).container.minutesService

    private val _uiState = MutableStateFlow(MinutesUiState())
    val uiState: StateFlow<MinutesUiState> = _uiState.asStateFlow()

    /** Convenience: the observed minutes row, or null. */
    val minutes: StateFlow<Minutes?> = _uiState
        .map { it.minutes }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _events = MutableSharedFlow<MinutesEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MinutesEvent> = _events.asSharedFlow()

    /**
     * Serializes generation for this screen. Rapid taps or a second call while
     * one is running are dropped, so action-item extraction cannot run twice
     * and duplicate items.
     */
    private val generateMutex = Mutex()

    init {
        viewModelScope.launch {
            repository.observeMinutes(recordingId).collect { row ->
                _uiState.update { it.copy(minutes = row, isLoading = false) }
            }
        }
    }

    fun generateMinutes() {
        val current = _uiState.value
        if (current.isGenerating) return
        if (current.minutes?.status == MinutesStatus.PENDING) return
        _uiState.update { it.copy(isGenerating = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                // A second call while one is running is dropped (tryLock), so
                // the extraction and action-item writes cannot overlap.
                if (!generateMutex.tryLock()) {
                    _uiState.update { it.copy(isGenerating = false) }
                    return@launch
                }
                try {
                    minutesService.generateMinutes(recordingId)
                } finally {
                    generateMutex.unlock()
                }
            } catch (e: Exception) {
                _events.tryEmit(MinutesEvent.GenerationFailed(e.message ?: "unknown error"))
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Could not generate minutes")
                }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun saveMinutes(text: String) {
        val minutes = _uiState.value.minutes ?: return
        viewModelScope.launch {
            try {
                repository.upsertMinutes(
                    recordingId = recordingId,
                    status = MinutesStatus.COMPLETED,
                    text = text
                )
            } catch (e: Exception) {
                _events.tryEmit(MinutesEvent.SaveFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun deleteMinutes() {
        viewModelScope.launch {
            try {
                repository.deleteMinutes(recordingId)
            } catch (e: Exception) {
                _events.tryEmit(MinutesEvent.DeleteFailed(e.message ?: "unknown error"))
            }
        }
    }
}