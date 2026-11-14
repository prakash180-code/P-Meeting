package com.prakash.pmeeting.ui.actionitem

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.actionitem.ActionItemService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActionItemViewModel(
    application: Application,
    val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository
    private val actionItemService = (application as PMeetingApplication).container.actionItemService

    val actionItems: StateFlow<List<ActionItem>> = repository.observeActionItems(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _events = MutableSharedFlow<ActionItemEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun addActionItem(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                repository.upsertActionItem(recordingId, text, ActionItemStatus.OPEN)
            } catch (e: Exception) {
                _events.tryEmit(ActionItemEvent.AddFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun toggleActionItem(id: Long) {
        viewModelScope.launch {
            try {
                actionItemService.toggleActionItem(id)
            } catch (e: Exception) {
                _events.tryEmit(ActionItemEvent.ToggleFailed(e.message ?: "unknown error"))
            }
        }
    }

    fun deleteActionItem(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteActionItem(id)
            } catch (e: Exception) {
                _events.tryEmit(ActionItemEvent.DeleteFailed(e.message ?: "unknown error"))
            }
        }
    }
}

sealed interface ActionItemEvent {
    data class AddFailed(val message: String) : ActionItemEvent
    data class ToggleFailed(val message: String) : ActionItemEvent
    data class DeleteFailed(val message: String) : ActionItemEvent
}
