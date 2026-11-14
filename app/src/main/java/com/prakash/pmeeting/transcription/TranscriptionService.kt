package com.prakash.pmeeting.transcription

import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow

interface TranscriptionService {

    suspend fun transcribe(recordingId: Long, language: TranscriptionLanguage): Long

    fun observeTranscription(recordingId: Long): Flow<Transcription?>

    /**
     * Invalidates an in-flight run for this recording. Non-suspending because
     * it only flips in-memory state; native inference cannot be aborted, so the
     * run's eventual result is discarded instead.
     */
    fun cancel(recordingId: Long)
}

/**
 * Phase 1 stub: no real transcription backend is wired in yet. Instead of
 * throwing, it persists a FAILED transcription row so the UI shows a clear
 * message instead of crashing.
 */
class TranscriptionServiceStub(
    private val repository: RecordingRepository
) : TranscriptionService {

    override suspend fun transcribe(recordingId: Long, language: TranscriptionLanguage): Long {
        return repository.upsertTranscription(
            recordingId = recordingId,
            status = TranscriptionStatus.FAILED,
            errorMessage = "Transcription backend not implemented in Phase 1."
        )
    }

    override fun observeTranscription(recordingId: Long): Flow<Transcription?> =
        repository.observeTranscription(recordingId)

    override fun cancel(recordingId: Long) = Unit
}
