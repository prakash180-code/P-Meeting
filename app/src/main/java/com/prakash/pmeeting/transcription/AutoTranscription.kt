package com.prakash.pmeeting.transcription

import android.content.Context
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import com.prakash.pmeeting.transcription.worker.TranscriptionWorker

/**
 * Single entry point for queueing transcription work.
 *
 * Every recording has at most one `transcribe_<id>` unique WorkManager job, so
 * automatic and manual requests can never run duplicate work. Persisted status
 * (`PENDING` / `PROCESSING` / ...) is written to Room before enqueueing so the
 * library badge is correct even if the process dies immediately afterwards.
 */
object AutoTranscription {

    /**
     * Queues transcription for [recordingId].
     *
     * @param force replaces a queued/running job and re-runs a recording that
     *   already has a successful transcript (manual "re-transcribe"/retry).
     */
    suspend fun enqueue(
        context: Context,
        repository: RecordingRepository,
        recordingId: Long,
        force: Boolean = false
    ) {
        val existing = repository.getTranscriptionStatus(recordingId)
        if (!force) {
            when (existing) {
                // Already transcribed, nothing to do.
                TranscriptionStatus.COMPLETED -> return
                // Queued/running: keep the existing job and its status.
                TranscriptionStatus.PENDING,
                TranscriptionStatus.PROCESSING -> {
                    TranscriptionWorker.enqueue(context, recordingId, force = false)
                    return
                }
                else -> Unit
            }
        }

        // Show "queued" immediately; the worker switches this to PROCESSING
        // when it actually starts. Any previous transcript text/segments are
        // preserved by the repository until a new successful run replaces them.
        repository.upsertTranscriptionWithSegments(
            recordingId = recordingId,
            status = TranscriptionStatus.PENDING
        )
        TranscriptionWorker.enqueue(context, recordingId, force = force)
    }

    /**
     * Re-queues interrupted and waiting-for-model jobs. Called on app start and
     * after a Whisper model becomes usable. Enqueueing uses ExistingWorkPolicy
     * KEEP, so jobs that are still alive are not duplicated.
     */
    suspend fun recover(
        context: Context,
        repository: RecordingRepository,
        modelReady: Boolean
    ) {
        val statuses = buildList {
            add(TranscriptionStatus.PENDING)
            add(TranscriptionStatus.PROCESSING)
            if (modelReady) add(TranscriptionStatus.WAITING_FOR_MODEL)
        }
        repository.getRecordingIdsWithTranscriptionStatuses(statuses).forEach { id ->
            enqueue(context, repository, id)
        }
    }
}
