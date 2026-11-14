package com.prakash.pmeeting.transcription

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ModelManager"

/** Download progress event. */
sealed class DownloadEvent {
    data object Started : DownloadEvent()
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadEvent()
    data object Completed : DownloadEvent()
    data class Failed(val error: String) : DownloadEvent()
    data object Cancelled : DownloadEvent()
}

/** Validation result for a downloaded model file. */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
    data object NativeCrash : ValidationResult()
}

/** Current state of model management. */
data class ModelState(
    val config: ModelConfig,
    val status: ModelStatus = ModelStatus.IDLE,
    val progress: Float = 0f,
    val errorMessage: String? = null,
    val isReady: Boolean = false
)

enum class ModelStatus {
    IDLE,
    DOWNLOADING,
    COMPLETED,
    VALIDATED,
    FAILED,
    INTERRUPTED
}

/**
 * Manages whisper model download, storage, validation, and retry.
 *
 * Models are stored in app-private internal storage
 * (context.filesDir/whisper_models/). No model is bundled in the APK.
 *
 * Validation attempts to load the model via the native WhisperEngine.
 * If the native backend is unavailable or crashes, validation fails
 * with a clear error and transcription is disabled.
 */
class ModelManager(private val context: Context, private val whisperEngine: WhisperEngine) {

    private val _modelState = MutableStateFlow(initialModelState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private var downloadJob: Job? = null

    private fun initialModelState(): ModelState {
        val config = WhisperModels.defaultModel()
        val file = File(File(context.filesDir, WhisperEngine.MODEL_DIR_NAME), "${config.id}.bin")
        return if (file.exists() && file.isFile && file.length() > 0L) {
            // A file on disk is not proof that the native engine can load it.
            // Report it as "not validated yet" (the screen offers Validate /
            // Delete) instead of a terminal "validating..." state that would
            // leave the model unusable after every app restart.
            ModelState(config = config, status = ModelStatus.INTERRUPTED, progress = 1f)
        } else {
            ModelState(config)
        }
    }

    /** Directory where models are stored (app-private internal storage). */
    val modelDir: File
        get() = File(context.filesDir, WhisperEngine.MODEL_DIR_NAME).apply { mkdirs() }

    /** Returns the model file for the given config. */
    fun modelFileFor(config: ModelConfig): File = File(modelDir, "${config.id}.bin")

    /** Returns true if a model file exists on disk. */
    fun hasModelFile(config: ModelConfig): Boolean {
        val file = modelFileFor(config)
        return file.exists() && file.length() > 0
    }

    /**
     * Returns true if any usable model file is present on disk (of any known
     * model id, or the legacy default name). Validation is left to the
     * transcription engine; this is only used to distinguish "no model
     * installed" from "transcription failed".
     */
    fun hasAnyModelFile(): Boolean {
        val dir = modelDir
        val candidates = buildList {
            add(File(dir, WhisperEngine.DEFAULT_MODEL_NAME))
            WhisperModels.ALL.forEach { add(File(dir, "${it.id}.bin")) }
        }
        return candidates.any { it.exists() && it.isFile && it.length() > 0L }
    }

    /** Returns true if the model has been validated and is ready for transcription. */
    fun isModelReady(config: ModelConfig = WhisperModels.defaultModel()): Boolean {
        val state = _modelState.value
        return state.config.id == config.id && state.isReady
    }

    /** Returns the current download progress (0.0 to 1.0). */
    fun getProgress(): Float = _modelState.value.progress

    /** Returns the current error message, if any. */
    fun getError(): String? = _modelState.value.errorMessage

    /**
     * Starts downloading the model. Does nothing if a download is already in progress.
     */
    suspend fun downloadModel(config: ModelConfig = WhisperModels.defaultModel()) {
        if (downloadJob?.isActive == true) return

        if (_modelState.value.config.id == config.id && _modelState.value.isReady) return

        val existingFile = modelFileFor(config)
        if (existingFile.exists() && existingFile.length() > 0) {
            _modelState.value = _modelState.value.copy(
                config = config, status = ModelStatus.COMPLETED, progress = 0f, errorMessage = null
            )
            validateModel(config, existingFile)
            return
        }

        downloadJob = CoroutineScope(Dispatchers.IO + Job()).launch {
            try {
                downloadWithProgress(config)
            } catch (e: Exception) {
                _modelState.value = _modelState.value.copy(
                    config = config,
                    status = ModelStatus.FAILED,
                    errorMessage = "Download interrupted: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private suspend fun downloadWithProgress(config: ModelConfig) {
        _modelState.value = _modelState.value.copy(
            config = config, status = ModelStatus.DOWNLOADING, progress = 0f, errorMessage = null
        )

        val url = URL(config.downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 60000

        val targetFile = modelFileFor(config)
        val tempFile = File(modelDir, "${config.id}.bin.part")

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                _modelState.value = _modelState.value.copy(
                    config = config,
                    status = ModelStatus.FAILED,
                    errorMessage = "Server returned $responseCode: ${connection.responseMessage}"
                )
                return
            }

            val totalBytes = connection.contentLength.toLong()
            if (totalBytes > 0) {
                val updatedConfig = config.copy(approxSizeBytes = totalBytes)
                _modelState.value = _modelState.value.copy(config = updatedConfig)
            }

            val targetFile = modelFileFor(config)
            var bytesDownloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        currentCoroutineContext().ensureActive()
                        val progress = if (totalBytes > 0L) bytesDownloaded.toFloat() / totalBytes else 0f
                        _modelState.value = _modelState.value.copy(
                            config = config,
                            status = ModelStatus.DOWNLOADING,
                            progress = progress
                        )
                    }
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Failed to rename downloaded file")
            }

            _modelState.value = _modelState.value.copy(
                config = config,
                status = ModelStatus.COMPLETED,
                progress = 1f,
                errorMessage = null
            )

            validateModel(config, targetFile)

        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            _modelState.value = _modelState.value.copy(
                config = config,
                status = ModelStatus.INTERRUPTED,
                errorMessage = "Download interrupted: ${e.message ?: "unknown error"}"
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun validateModel(config: ModelConfig, file: File) {
        _modelState.value = _modelState.value.copy(
            config = config, status = ModelStatus.COMPLETED, errorMessage = null
        )

        if (!file.exists() || file.length() == 0L) {
            _modelState.value = _modelState.value.copy(
                config = config,
                status = ModelStatus.FAILED,
                errorMessage = "Model file is empty or missing."
            )
            return
        }

        val loadResult = withContext(Dispatchers.IO) {
            try {
                val success = whisperEngine.loadModel(file)
                if (success) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid("Native engine rejected the model file (invalid format or corrupted).")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Native model load crashed", e)
                ValidationResult.NativeCrash
            }
        }

        when {
            loadResult == ValidationResult.Valid -> {
                _modelState.value = _modelState.value.copy(
                    config = config,
                    status = ModelStatus.VALIDATED,
                    progress = 1f,
                    errorMessage = null,
                    isReady = true
                )
                Log.i(TAG, "Model validated and ready: ${config.id}")
            }
            loadResult is ValidationResult.Invalid -> {
                val reason = loadResult.reason
                file.delete()
                _modelState.value = _modelState.value.copy(
                    config = config,
                    status = ModelStatus.FAILED,
                    errorMessage = reason
                )
            }
            loadResult == ValidationResult.NativeCrash -> {
                file.delete()
                _modelState.value = _modelState.value.copy(
                    config = config,
                    status = ModelStatus.FAILED,
                    errorMessage = "Native Whisper backend crashed while loading model. " +
                        "The native whisper-jni library may not be compatible with this device. " +
                        "Transcription is unavailable until the backend is fixed."
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _modelState.value = _modelState.value.copy(
            status = ModelStatus.INTERRUPTED,
            errorMessage = "Download cancelled."
        )
    }

    suspend fun retry() {
        val current = _modelState.value
        if (current.status == ModelStatus.DOWNLOADING) return
        downloadModel(current.config)
    }

    suspend fun deleteModel() {
        cancelDownload()
        val file = modelFileFor(_modelState.value.config)
        file.delete()
        _modelState.value = _modelState.value.copy(
            status = ModelStatus.IDLE,
            progress = 0f,
            errorMessage = null,
            isReady = false
        )
    }

    fun observeDownloads(): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Started)
    }
}
