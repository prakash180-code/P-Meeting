package com.prakash.pmeeting.ui.email

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.Transcription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EmailDraftViewModel(
    application: Application,
    val recordingId: Long
) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository

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

    private val _events = MutableSharedFlow<EmailDraftEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun shareDraft(subject: String, body: String) {
        val title = recording.value?.title ?: "Meeting Summary"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(intent, "Share email draft via $title")
        _events.tryEmit(EmailDraftEvent.ShareDraft(chooser))
    }
}

sealed interface EmailDraftEvent {
    data class ShareDraft(val intent: Intent) : EmailDraftEvent
}
