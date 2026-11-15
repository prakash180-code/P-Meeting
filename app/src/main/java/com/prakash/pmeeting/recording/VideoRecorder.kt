package com.prakash.pmeeting.recording

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.prakash.pmeeting.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Events produced by [VideoRecorder], delivered on an arbitrary thread. */
sealed interface VideoEvent {
    data object RecordingStarted : VideoEvent
    data object RecordingPaused : VideoEvent
    data object RecordingResumed : VideoEvent

    /**
     * Emitted once per recording, exactly once, after the recorder finished.
     * [FinalizedVideo.errorMessage] is null on success.
     */
    data class RecordingFinalized(val result: FinalizedVideo) : VideoEvent
}

data class FinalizedVideo(
    val file: File?,
    val durationMs: Long,
    val sizeBytes: Long,
    val errorMessage: String?
)

/**
 * CameraX-based video recording session bound to a [LifecycleOwner].
 *
 * One recorder instance is created per screen and must stay alive while the
 * screen is visible; [close] releases the camera and its executor.
 *
 * Shutdown is deliberately decoupled from finalization. CameraX finalizes a
 * recording asynchronously, so [close] only requests the stop and keeps the
 * output file (and the event executor) alive until the finalize event arrives.
 * This prevents the "stop then immediately clear the file" race that dropped
 * recordings when the screen was closed mid-take.
 *
 * Rotation is handled by updating the capture target rotation from the
 * preview display; the host activity keeps itself alive across configuration
 * changes so an active recording is never interrupted by a rebind.
 */
class VideoRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val events = MutableSharedFlow<VideoEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Lifetime-scoped owner for the finalize watchdog; never tied to a screen. */
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards all finalize state. The CameraX finalize callback runs on
     * [executorService]; the watchdog runs on [watchdogScope]; [close] is
     * called from the main thread. Exactly one of them may deliver a result.
     */
    private val finalizeLock = Any()

    private var pendingOutputFile: File? = null
    private var finalizeDelivered = false
    private var stopRequested = false
    private var closed = false
    private var watchdogJob: Job? = null

    /**
     * Direct finalize callback invoked synchronously from the CameraX finalize
     * event. The [eventFlow] is a hot SharedFlow with no replay, so a screen
     * that is being torn down would otherwise miss the only notification.
     */
    private var finalizeListener: ((FinalizedVideo) -> Unit)? = null

    val eventFlow: SharedFlow<VideoEvent> = events.asSharedFlow()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var targetRotationDegrees: Int = 0
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    /** The preview surface provider (typically a PreviewView) before [bind]. */
    fun setPreviewSurface(provider: Preview.SurfaceProvider) {
        this.previewSurfaceProvider = provider
    }

    fun setLensFacing(facing: Int) {
        lensFacing = facing
    }

    /**
     * Registers the one-shot finalize callback. Callers should set this before
     * starting a recording; it is invoked even if no one is collecting
     * [eventFlow] at the time.
     */
    fun setFinalizeListener(listener: ((FinalizedVideo) -> Unit)?) {
        this.finalizeListener = listener
    }

    val isRecording: Boolean
        get() = activeRecording != null && pendingOutputFile != null

    val currentLensFacing: Int
        get() = lensFacing

    /** Attaches the camera to the lifecycle and binds preview + capture. */
    suspend fun bind() {
        val cameraProvider = suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        provider = cameraProvider
        val surfaceProvider = previewSurfaceProvider
            ?: error("setPreviewSurface() must be called before bind().")
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        val outputRecorder = Recorder.Builder().build()
        val capture = VideoCapture.withOutput(outputRecorder)
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            preview,
            capture
        )
        videoCapture = capture
    }

    /**
     * Starts a recording; the file is created inside [directory].
     *
     * [withAudio] is only true when the caller has verified RECORD_AUDIO via
     * ContextCompat.checkSelfPermission, hence the lint suppression below.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun startRecording(directory: File, withAudio: Boolean, displayRotation: Int) {
        synchronized(finalizeLock) {
            if (closed) return
            if (pendingOutputFile != null || activeRecording != null) return
        }
        val capture = videoCapture
        if (capture == null) {
            emitFinalize(null, 0L, 0L, "The camera is not ready yet.")
            return
        }
        directory.mkdirs()
        val file = File(directory, "PM_VIDEO_${Formats.fileNameTimestamp()}.mp4")
        synchronized(finalizeLock) {
            pendingOutputFile = file
            finalizeDelivered = false
            stopRequested = false
        }
        try {
            capture.targetRotation = displayRotation
            val options = FileOutputOptions.Builder(file).build()
            val pending = capture.output.prepareRecording(context, options)
            if (withAudio) pending.withAudioEnabled()
            val recording = pending.start(eventExecutor(), eventConsumer())
            activeRecording = recording
        } catch (t: Throwable) {
            runCatching { file.delete() }
            synchronized(finalizeLock) {
                pendingOutputFile = null
                activeRecording = null
            }
            emitFinalize(null, 0L, 0L, t.message ?: "Recording could not be started.")
        }
    }

    fun pauseRecording() {
        runCatching { activeRecording?.pause() }
    }

    fun resumeRecording() {
        runCatching { activeRecording?.resume() }
    }

    /**
     * Requests a stop; the finalize event arrives asynchronously. Repeated
     * calls are ignored until the finalize is delivered, so callers cannot
     * trigger duplicate finalize handling.
     */
    fun stopRecording() {
        val pendingFile: File
        synchronized(finalizeLock) {
            if (pendingOutputFile == null || finalizeDelivered || stopRequested) return
            stopRequested = true
            pendingFile = pendingOutputFile!!
        }
        runCatching { activeRecording?.stop() }
        scheduleWatchdog(pendingFile)
    }

    /**
     * Releases the camera and executor. Safe to call repeatedly and safe to
     * call while a recording is still finalizing: the pending output file and
     * the event executor are retained until the finalize event (or the
     * watchdog timeout) delivers exactly one result.
     */
    fun close() {
        val hadPending: Boolean
        synchronized(finalizeLock) {
            if (closed) return
            closed = true
            hadPending = pendingOutputFile != null
        }
        stopRecording()
        runCatching { provider?.unbindAll() }
        videoCapture = null
        activeRecording = null
        if (!hadPending) {
            shutdownExecutor()
        }
    }

    fun updateTargetRotationOfActiveRecording(displayRotation: Int) {
        targetRotationDegrees = displayRotation
        if (activeRecording != null) {
            videoCapture?.targetRotation = displayRotation
        }
    }

    private fun scheduleWatchdog(file: File) {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(FINALIZE_TIMEOUT_MS)
            val delivered = deliverFinalize(
                file.takeIf { it.exists() && it.length() > 0L },
                0L,
                file.length(),
                "Recording stopped responding before it could be finalized."
            )
            if (delivered) {
                shutdownExecutor()
            }
        }
    }

    /**
     * Emits exactly one finalize result for the current recording. Returns true
     * when this call delivered the result, false when it was already delivered
     * (a late duplicate from CameraX).
     */
    private fun deliverFinalize(file: File?, durationMs: Long, sizeBytes: Long, error: String?): Boolean {
        val listener: ((FinalizedVideo) -> Unit)?
        synchronized(finalizeLock) {
            if (finalizeDelivered) return false
            finalizeDelivered = true
            pendingOutputFile = null
            stopRequested = false
            activeRecording = null
            listener = finalizeListener
        }
        watchdogJob?.cancel()
        watchdogJob = null
        val result = FinalizedVideo(file, durationMs, sizeBytes, error)
        // Deliver directly first: the event flow may have no collector during
        // teardown, but the registered listener must still receive the take.
        listener?.invoke(result)
        events.tryEmit(VideoEvent.RecordingFinalized(result))
        return true
    }

    private fun emitFinalize(file: File?, durationMs: Long, sizeBytes: Long, error: String?) {
        deliverFinalize(file, durationMs, sizeBytes, error)
    }

    private fun shutdownExecutor() {
        watchdogJob?.cancel()
        watchdogJob = null
        synchronized(finalizeLock) {
            finalizeListener = null
        }
        executorService.shutdown()
    }

    private fun eventExecutor(): Executor = executorService

    private fun eventConsumer(): (VideoRecordEvent) -> Unit = { event ->
        when (event) {
            is VideoRecordEvent.Start -> events.tryEmit(VideoEvent.RecordingStarted)
            is VideoRecordEvent.Pause -> events.tryEmit(VideoEvent.RecordingPaused)
            is VideoRecordEvent.Resume -> events.tryEmit(VideoEvent.RecordingResumed)
            is VideoRecordEvent.Finalize -> {
                val file = synchronized(finalizeLock) { pendingOutputFile }
                val durationMs = event.recordingStats.getRecordedDurationNanos() / 1_000_000L
                val sizeBytes = event.recordingStats.getNumBytesRecorded()
                if (event.hasError()) {
                    // Drop unusable files (for example zero-length stops).
                    if (file != null && file.length() <= 0L) {
                        runCatching { file.delete() }
                    }
                    deliverFinalize(
                        file?.takeIf { it.exists() && it.length() > 0L },
                        durationMs,
                        sizeBytes,
                        "Recording failed (CameraX error ${event.error})."
                    )
                } else {
                    deliverFinalize(file, durationMs, sizeBytes, null)
                }
                val shouldShutdown = synchronized(finalizeLock) { closed }
                if (shouldShutdown) shutdownExecutor()
            }

            else -> Unit
        }
    }

    private companion object {
        /** Upper bound for CameraX finalize before reporting a failure. */
        const val FINALIZE_TIMEOUT_MS = 15_000L
    }
}