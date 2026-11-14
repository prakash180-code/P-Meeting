package com.prakash.pmeeting.ui.minutes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.minutes.MinutesService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MinutesViewModel(
    application: Application,
    val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val minutesService = (application as PMeetingApplication).container.minutesService

    val minutes: StateFlow<Minutes?> = repository.observeMinutes(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _events = MutableSharedFlow<MinutesEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun generateMinutes() {
        val m = minutes.value
        if (m?.status == MinutesStatus.PENDING) return
        viewModelScope.launch {
            try {
                minutesService.generateMinutes(recordingId)
            } catch (e: Exception) {
                _events.tryEmit(MinutesEvent.GenerationFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun saveMinutes(text: String) {
        viewModelScope.launch {
            try {
                repository.upsertMinutes(
                    recordingId = recordingId,
                    status = MinutesStatus.COMPLETED,
                    text = text.trim()
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

sealed interface MinutesEvent {
    data class GenerationFailed(val message: String) : MinutesEvent
    data class SaveFailed(val message: String) : MinutesEvent
    data class DeleteFailed(val message: String) : MinutesEvent
}
