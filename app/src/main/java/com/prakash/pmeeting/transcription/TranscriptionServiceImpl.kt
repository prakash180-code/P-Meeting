package com.prakash.pmeeting.transcription

import android.content.Context
import androidx.core.net.toUri
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * File-based transcription service using whisper.cpp via JNI.
 *
 * Workflow:
 * 1. Verify the recording file exists and is finalized (not being recorded).
 * 2. Decode the audio file to 16kHz mono float PCM.
 * 3. Run whisper.cpp transcription via JNI.
 * 4. Save the transcript with timestamped segments to the database.
 *
 * Runs are serialized app-wide because the native whisper context is shared.
 * Each run carries a token so a cancelled/replaced run can never write its
 * (stale) result over a newer run or erase a previous successful transcript.
 */
class TranscriptionServiceImpl(
    private val repository: RecordingRepository,
    private val context: Context,
    private val whisperEngine: WhisperEngine
) : TranscriptionService {

    private val audioDecoder = AudioDecoder(context)

    /**
     * The native whisper context is shared app-wide. Transcriptions started
     * from different screens (or from the worker) must not overlap: they would
     * race on the same ggml context and abort the process. Each transcription
     * runs to completion before the next one starts.
     */
    private val transcriptionMutex = Mutex()

    /** Global monotonic run counter: every run gets a unique token. */
    private val tokenCounter = AtomicLong(0L)

    /** Token of the latest started run per recording. */
    private val latestTokens = ConcurrentHashMap<Long, Long>()

    /** Token of the run currently executing per recording. */
    private val activeTokens = ConcurrentHashMap<Long, Long>()

    override suspend fun transcribe(recordingId: Long, language: TranscriptionLanguage): Long =
        transcriptionMutex.withLock { transcribeInternal(recordingId, language) }

    private suspend fun transcribeInternal(recordingId: Long, language: TranscriptionLanguage): Long {
        val token = tokenCounter.incrementAndGet()
        latestTokens[recordingId] = token
        activeTokens[recordingId] = token
        try {
            return runWithToken(recordingId, token, language)
        } finally {
            activeTokens.remove(recordingId, token)
        }
    }

    private suspend fun runWithToken(
        recordingId: Long,
        token: Long,
        language: TranscriptionLanguage
    ): Long {
        val recording = repository.getRecording(recordingId)
            ?: return writeIfCurrent(recordingId, token) {
                repository.upsertTranscription(
                    recordingId = recordingId,
                    status = TranscriptionStatus.FAILED,
                    errorMessage = "Recording not found."
                )
            }

        val prepared = prepareAudioFile(recording.fileUri)
        if (prepared == null) {
            return writeIfCurrent(recordingId, token) {
                repository.upsertTranscription(
                    recordingId = recordingId,
                    status = TranscriptionStatus.FAILED,
                    errorMessage = "Recording file not found on device."
                )
            }
        }

        try {
            if (!whisperEngine.isModelLoaded()) {
                val modelFile = findLoadableModelFile()
                if (modelFile == null) {
                    // Not an error the user can retry immediately: it needs a
                    // model download. Keep it distinct from a failed run.
                    return writeIfCurrent(recordingId, token) {
                        repository.upsertTranscriptionWithSegments(
                            recordingId = recordingId,
                            status = TranscriptionStatus.WAITING_FOR_MODEL,
                            errorMessage = "No Whisper model available. Download and validate a " +
                                "model in Settings; transcription resumes automatically afterwards."
                        )
                    }
                }

                if (!whisperEngine.loadModel(modelFile)) {
                    return writeIfCurrent(recordingId, token) {
                        repository.upsertTranscriptionWithSegments(
                            recordingId = recordingId,
                            status = TranscriptionStatus.FAILED,
                            errorMessage = "Failed to load Whisper model: ${modelFile.name}"
                        )
                    }
                }
            }

            // Decode audio to 16kHz mono float samples (off the main thread)
            val samples = withContext(Dispatchers.IO) {
                audioDecoder.decodeToFloatMono(prepared.file, targetSampleRate = 16000)
            }
            if (samples.isEmpty()) {
                whisperEngine.release()
                return writeIfCurrent(recordingId, token) {
                    repository.upsertTranscriptionWithSegments(
                        recordingId = recordingId,
                        status = TranscriptionStatus.FAILED,
                        errorMessage = "Could not decode audio from recording file."
                    )
                }
            }

            // Run transcription (off the main thread). The native call cannot
            // be interrupted; if the run was replaced meanwhile, the result is
            // discarded by writeIfCurrent below.
            val segments = withContext(Dispatchers.IO) {
                whisperEngine.transcribeFloat(samples, language = language.langCode)
            }
            whisperEngine.release()

            if (segments == null) {
                return writeIfCurrent(recordingId, token) {
                    repository.upsertTranscriptionWithSegments(
                        recordingId = recordingId,
                        status = TranscriptionStatus.FAILED,
                        errorMessage = "Transcription engine returned no results."
                    )
                }
            }

            val fullText = segments.joinToString(" ") { it.text.trim() }.trim()

            return writeIfCurrent(recordingId, token) {
                repository.upsertTranscriptionWithSegments(
                    recordingId = recordingId,
                    status = TranscriptionStatus.COMPLETED,
                    text = fullText,
                    segments = segments,
                    errorMessage = null
                )
            }
        } finally {
            if (prepared.temporary) prepared.file.delete()
        }
    }

    override fun observeTranscription(recordingId: Long): Flow<Transcription?> =
        repository.observeTranscription(recordingId)

    /**
     * Cancels the in-flight transcription for a recording.
     *
     * Native inference cannot be aborted mid-call, so cancellation invalidates
     * the token of the run that is executing right now: the run finishes but
     * its result is discarded. A newer run that replaced this job has its own
     * token and is never affected. No database write happens here, so a queued
     * replacement keeps its PENDING status.
     */
    override fun cancel(recordingId: Long) {
        val active = activeTokens[recordingId] ?: return
        latestTokens.remove(recordingId, active)
        activeTokens.remove(recordingId, active)
    }

    private suspend fun writeIfCurrent(
        recordingId: Long,
        token: Long,
        write: suspend () -> Long
    ): Long = if (latestTokens[recordingId] == token) write() else -1L

    /**
     * Resolves the stored recording location into a file the decoder can read.
     * Imported "linked" recordings are content:// URIs; those are streamed into
     * a cache file for the duration of transcription (the original is untouched).
     */
    private fun prepareAudioFile(fileUri: String): PreparedAudio? {
        if (fileUri.startsWith("content://")) {
            return runCatching {
                val temp = File.createTempFile("pmeeting-audio-", ".tmp", context.cacheDir)
                val input = context.contentResolver.openInputStream(fileUri.toUri())
                if (input == null) {
                    temp.delete()
                    null
                } else {
                    input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                    PreparedAudio(temp, temporary = true)
                }
            }.getOrNull()
        }

        val path = if (fileUri.startsWith("file://")) {
            runCatching { fileUri.toUri().path }.getOrNull()
        } else {
            fileUri
        } ?: return null

        val file = File(path)
        return if (file.exists()) PreparedAudio(file, temporary = false) else null
    }

    private data class PreparedAudio(val file: File, val temporary: Boolean)

    private fun findLoadableModelFile(): File? {
        val modelDir = File(context.filesDir, WhisperEngine.MODEL_DIR_NAME)
        val candidates = buildList {
            add(File(modelDir, "${WhisperModels.defaultModel().id}.bin"))
            add(File(modelDir, WhisperEngine.DEFAULT_MODEL_NAME))
            WhisperModels.ALL.forEach { add(File(modelDir, "${it.id}.bin")) }
        }
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
    }
}
