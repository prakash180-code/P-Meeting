package com.prakash.pmeeting.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.transcription.AutoTranscription
import com.prakash.pmeeting.transcription.ModelManager
import com.prakash.pmeeting.transcription.ModelState
import com.prakash.pmeeting.transcription.ModelStatus
import com.prakash.pmeeting.transcription.TranscriptionLanguage
import com.prakash.pmeeting.transcription.TranscriptionLanguageStore
import com.prakash.pmeeting.transcription.WhisperModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PMeetingApplication).container
    private val modelManager: ModelManager
    private val languageStore: TranscriptionLanguageStore

    init {
        modelManager = container.modelManager
        languageStore = container.languageStore

        // When a Whisper model becomes usable, resume recordings whose
        // transcription was left waiting for it. Recovery is idempotent: the
        // unique work name prevents duplicate jobs for still-live work.
        viewModelScope.launch {
            modelManager.modelState
                .map { it.isReady }
                .distinctUntilChanged()
                .collect { ready ->
                    if (ready) {
                        runCatching {
                            AutoTranscription.recover(
                                context = getApplication<Application>(),
                                repository = container.recordingRepository,
                                modelReady = true
                            )
                        }
                    }
                }
        }
    }

    /** Persisted dark-theme override, or `null` when following the system setting. */
    val darkTheme: StateFlow<Boolean?> = container.themePreference.darkTheme

    fun setDarkTheme(enabled: Boolean) {
        container.themePreference.setDarkTheme(enabled)
    }

    // ------------------------------------------------------------------ //
    // Transcription language
    // ------------------------------------------------------------------ //

    /** Currently selected transcription language (persisted app-wide). */
    val transcriptionLanguage: StateFlow<TranscriptionLanguage> = languageStore.language

    /** Persists a new transcription language choice. */
    fun setTranscriptionLanguage(language: TranscriptionLanguage) {
        languageStore.set(language)
    }

    // ------------------------------------------------------------------ //
    // Model management
    // ------------------------------------------------------------------ //

    val modelState: StateFlow<ModelState> = modelManager.modelState

    /** Returns true if a download is currently in progress. */
    fun isDownloading(): Boolean {
        val status = modelState.value.status
        return status == ModelStatus.DOWNLOADING
    }

    /** Returns true if the model is ready for transcription. */
    fun isModelReady(): Boolean {
        return modelManager.isModelReady()
    }

    /** Returns the formatted model size string for display. */
    fun formatModelSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** Starts downloading the default model. */
    fun downloadModel() {
        viewModelScope.launch {
            modelManager.downloadModel(WhisperModels.defaultModel())
        }
    }

    /** Cancels any in-progress download. */
    fun cancelDownload() {
        modelManager.cancelDownload()
    }

    /** Retries a failed or interrupted download. */
    fun retryDownload() {
        viewModelScope.launch {
            modelManager.retry()
        }
    }

    /** Deletes the downloaded model. */
    fun deleteModel() {
        viewModelScope.launch {
            modelManager.deleteModel()
        }
    }

    /** Returns true if a model file exists on disk (even if not yet validated). */
    fun hasModelFile(): Boolean {
        return modelManager.hasModelFile(WhisperModels.defaultModel())
    }
}
