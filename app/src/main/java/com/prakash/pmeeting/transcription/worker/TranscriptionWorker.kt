package com.prakash.pmeeting.transcription.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.prakash.pmeeting.MainActivity
import com.prakash.pmeeting.PMeetingApplication
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import com.prakash.pmeeting.transcription.ModelManager
import com.prakash.pmeeting.transcription.TranscriptionLanguageStore
import com.prakash.pmeeting.transcription.TranscriptionNotifications
import com.prakash.pmeeting.transcription.TranscriptionService
import kotlinx.coroutines.CancellationException

const val KEY_RECORDING_ID = "recording_id"
const val KEY_TRANSCRIBE_ONLY = "transcribe_only"
const val KEY_FORCE = "force"

/**
 * Background worker that transcribes one completed recording.
 *
 * Runs as a foreground CoroutineWorker: transcriptions can take longer than the
 * ten-minute background limit, and the ongoing notification (plus the library
 * progress badge) makes the work visible. WorkManager persists the job, so it
 * survives navigation and app restarts; a unique work name per recording makes
 * duplicate jobs impossible. Foreground-service execution still respects
 * Android background restrictions (see README limitations).
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as PMeetingApplication

    private val repository: RecordingRepository by lazy { app.container.recordingRepository }
    private val transcriptionService: TranscriptionService by lazy { app.container.transcriptionService }
    private val languageStore: TranscriptionLanguageStore by lazy { app.container.languageStore }
    private val modelManager: ModelManager by lazy { app.container.modelManager }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (recordingId == -1L) {
            return Result.failure(workDataOf("error" to "Missing recording ID"))
        }
        val force = inputData.getBoolean(KEY_FORCE, false)

        // The recording may have been deleted while the job was queued.
        val recording = repository.getRecording(recordingId) ?: return Result.success()

        val existingStatus = repository.getTranscriptionStatus(recordingId)
        if (!force && existingStatus == TranscriptionStatus.COMPLETED) {
            return Result.success()
        }

        // Foreground execution avoids the ten-minute Worker limit. If the
        // system refuses the foreground promotion, continue anyway; the job is
        // then subject to normal background limits and may be retried.
        runCatching {
            setForeground(createForegroundInfo(recording.title))
        }

        // Without a model there is nothing to run. Persist a clear,
        // non-fabricated state instead of generating mock text.
        if (!modelManager.hasAnyModelFile()) {
            repository.upsertTranscriptionWithSegments(
                recordingId = recordingId,
                status = TranscriptionStatus.WAITING_FOR_MODEL,
                errorMessage = "No Whisper model is installed. Open Settings to download and " +
                    "validate a model; transcription will resume automatically afterwards."
            )
            return Result.success()
        }

        // Visible "processing" state for the library badge and detail screen.
        repository.upsertTranscriptionWithSegments(
            recordingId = recordingId,
            status = TranscriptionStatus.PROCESSING
        )

        return try {
            transcriptionService.transcribe(recordingId, languageStore.get())
            when (repository.getTranscriptionStatus(recordingId)) {
                TranscriptionStatus.COMPLETED -> Result.success()
                // Another (newer) run has taken over this recording.
                TranscriptionStatus.PENDING,
                TranscriptionStatus.PROCESSING -> Result.success()
                TranscriptionStatus.WAITING_FOR_MODEL -> Result.success()
                else -> {
                    val message = repository.getTranscription(recordingId)?.errorMessage
                        ?: "Transcription failed"
                    Result.failure(workDataOf("error" to message))
                }
            }
        } catch (cancelled: CancellationException) {
            // CoroutineWorker's onStopped() is final in this WorkManager
            // version, so cancellation is detected here. Native inference
            // cannot be interrupted; invalidating the token discards its
            // eventual result and keeps a newer run intact.
            runCatching { transcriptionService.cancel(recordingId) }
            throw cancelled
        } catch (t: Throwable) {
            repository.upsertTranscriptionWithSegments(
                recordingId = recordingId,
                status = TranscriptionStatus.FAILED,
                errorMessage = t.message ?: "Transcription failed"
            )
            Result.failure(workDataOf("error" to (t.message ?: "Transcription failed")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(
            runCatching { repository.getRecording(inputData.getLong(KEY_RECORDING_ID, -1L))?.title }
                .getOrNull() ?: "Recording"
        )

    private fun createForegroundInfo(title: String): ForegroundInfo {
        TranscriptionNotifications.ensureChannel(applicationContext)

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            TranscriptionNotifications.CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Transcribing recording")
            .setContentText(title)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                TranscriptionNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(TranscriptionNotifications.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Unique work name per recording: the duplicate-job guard. */
        fun workName(recordingId: Long): String = "transcribe_$recordingId"

        fun enqueue(context: Context, recordingId: Long, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_RECORDING_ID, recordingId)
                        .putBoolean(KEY_FORCE, force)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(recordingId),
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, recordingId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(recordingId))
        }
    }
}
