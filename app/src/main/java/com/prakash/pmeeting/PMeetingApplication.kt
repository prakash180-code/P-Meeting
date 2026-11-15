package com.prakash.pmeeting

import android.app.Application
import com.prakash.pmeeting.transcription.AutoTranscription
import com.prakash.pmeeting.transcription.TranscriptionNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * P-Meeting is local-first: recordings, database and transcripts never leave
 * the device. The only network access is the optional Whisper model download in
 * Settings.
 */
class PMeetingApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Process-wide scope for work that must survive a screen or ViewModel being
     * torn down (for example finalizing and saving a recording while the user
     * navigates back). Cancellation never reaches this scope, so a pending save
     * is not lost when the recorder screen leaves composition.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TranscriptionNotifications.ensureChannel(this)

        // Resume jobs that were queued/interrupted when the process died, and
        // jobs that were waiting for a model that is now installed. Unique work
        // keeps this idempotent even if WorkManager already re-scheduled them.
        applicationScope.launch {
            runCatching {
                AutoTranscription.recover(
                    context = this@PMeetingApplication,
                    repository = container.recordingRepository,
                    modelReady = container.modelManager.hasAnyModelFile()
                )
            }
        }
    }
}
