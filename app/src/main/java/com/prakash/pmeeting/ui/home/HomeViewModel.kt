package com.prakash.pmeeting.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.transcription.AutoTranscription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A file picked through SAF, waiting for a title before it becomes an entry. */
data class PendingImport(
    val uri: Uri,
    val suggestedTitle: String,
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val durationMs: Long?
)

sealed interface HomeEvent {
    data object ImportSaved : HomeEvent
    data class Message(val text: String) : HomeEvent
    data class BulkAction(val text: String) : HomeEvent
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as PMeetingApplication).container.recordingRepository

    private val searchQuery = MutableStateFlow("")

    val searchQueryText: StateFlow<String> = searchQuery.asStateFlow()

    private val _selectedIds = MutableStateFlow(emptySet<Long>())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val selectedCount: StateFlow<Int> = selectedIds.map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val recordings: StateFlow<List<Recording>> = combine(
        repository.recordings,
        repository.archivedRecordings,
        showArchived,
        searchQuery
    ) { active, archived, showArchived, query ->
        val list = if (showArchived) archived else active
        val trimmed = query.trim()
        if (trimmed.isEmpty()) list
        else list.filter { it.title.contains(trimmed, ignoreCase = true) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * Persisted transcription status per recording id, used by the small
     * status badge in every library row. Room pushes updates as background
     * jobs start/finish, so no refresh is needed.
     */
    val transcriptionStatuses: StateFlow<Map<Long, TranscriptionStatus>> =
        repository.observeTranscriptionStatuses()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun toggleSelectionMode() {
        _selectionMode.value = !_selectionMode.value
        if (!_selectionMode.value) clearSelection()
    }

    fun setSelectionMode(mode: Boolean) {
        if (mode == _selectionMode.value) return
        _selectionMode.value = mode
        if (!mode) clearSelection()
    }

    fun toggleSelection(id: Long) {
        _selectionMode.value = true
        _selectedIds.value = if (id in _selectedIds.value) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
    }

    fun selectAll() {
        _selectionMode.value = true
        _selectedIds.value = recordings.value.map { it.id }.toSet()
    }

    /**
     * Selects every currently unselected visible meeting and deselects the
     * visible ones that were selected. Works with search filters and the
     * archived view; hidden meetings are never touched.
     */
    fun invertSelection() {
        val visible = recordings.value.map { it.id }.toSet()
        if (visible.isEmpty()) return
        _selectionMode.value = true
        _selectedIds.value = invertedSelection(visible, _selectedIds.value)
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun toggleArchivedView() {
        _showArchived.value = !_showArchived.value
        clearSelection()
    }

    /** Archives the currently selected meetings. They keep all files and data. */
    fun archiveSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.archive(ids.toList())
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            _events.tryEmit(HomeEvent.BulkAction("${ids.size} meeting(s) archived."))
        }
    }

    /** Restores the currently selected archived meetings to the active library. */
    fun restoreSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restore(ids.toList())
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            _events.tryEmit(HomeEvent.BulkAction("${ids.size} meeting(s) restored."))
        }
    }

    /** Deletes the currently selected meetings after confirmation. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val allDeleted = repository.deleteSelected(ids.toList())
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            if (allDeleted) {
                _events.tryEmit(HomeEvent.BulkAction("${ids.size} meeting(s) deleted."))
            } else {
                _events.tryEmit(
                    HomeEvent.Message("${ids.size} meeting(s) deleted, but some files could not be removed.")
                )
            }
        }
    }

    /** Called when the SAF picker returned a URI (OpenDocument contract). */
    fun onImportPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // Persist read access so the linked file stays playable later.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                if (_pendingImport.value != null) return@launch
                val meta = repository.queryPickedFileMeta(uri)
                val mime = repository.queryMimeType(uri)
                val duration = repository.probeDuration(uri)
                if (meta == null && duration == null) {
                    _events.tryEmit(HomeEvent.Message("The selected file could not be read."))
                    return@launch
                }
                _pendingImport.value = PendingImport(
                    uri = uri,
                    suggestedTitle = meta?.displayName?.substringBeforeLast('.')
                        ?: "Imported recording",
                    displayName = meta?.displayName,
                    sizeBytes = meta?.sizeBytes,
                    mimeType = mime,
                    durationMs = duration
                )
            } catch (t: Exception) {
                _pendingImport.value = null
                _events.tryEmit(HomeEvent.Message("The selected file could not be read."))
            }
        }
    }

    fun dismissImportDialog() {
        _pendingImport.value = null
    }

    fun confirmImport(title: String, copyIntoAppStorage: Boolean) {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch {
            try {
                repository.importRecording(
                    uri = pending.uri,
                    title = title,
                    copyIntoAppStorage = copyIntoAppStorage,
                    originalDisplayName = pending.displayName,
                    originalSizeBytes = pending.sizeBytes,
                    originalMimeType = pending.mimeType,
                    originalDurationMs = pending.durationMs
                ).also { imported ->
                    // The file is fully copied/linked by now; queue background
                    // transcription with the same rules as recorded files.
                    AutoTranscription.enqueue(
                        getApplication(),
                        repository,
                        imported.id
                    )
                }
                _pendingImport.value = null
                _events.tryEmit(HomeEvent.ImportSaved)
            } catch (t: Exception) {
                _events.tryEmit(HomeEvent.Message("Import failed: ${t.message ?: "unknown error"}"))
            }
        }
    }

    fun rename(recording: Recording, newTitle: String) {
        viewModelScope.launch {
            repository.rename(recording.id, newTitle)
        }
    }

    fun delete(recording: Recording) {
        viewModelScope.launch {
            val fileDeleted = repository.delete(recording)
            if (!fileDeleted) {
                _events.tryEmit(
                    HomeEvent.Message("Entry removed, but its file could not be deleted.")
                )
            }
        }
    }
}
