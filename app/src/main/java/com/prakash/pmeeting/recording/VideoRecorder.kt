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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private var pendingOutputFile: File? = null

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

    val isRecording: Boolean
        get() = activeRecording != null

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
        val capture = videoCapture
        if (activeRecording != null) return
        if (capture == null) {
            emitFinalize(null, 0L, 0L, "The camera is not ready yet.")
            return
        }
        directory.mkdirs()
        val file = File(directory, "PM_VIDEO_${Formats.fileNameTimestamp()}.mp4")
        pendingOutputFile = file
        try {
            capture.targetRotation = displayRotation
            val options = FileOutputOptions.Builder(file).build()
            val pending = capture.output.prepareRecording(context, options)
            if (withAudio) pending.withAudioEnabled()
            val recording = pending.start(eventExecutor(), eventConsumer())
            activeRecording = recording
        } catch (t: Throwable) {
            runCatching { file.delete() }
            pendingOutputFile = null
            activeRecording = null
            emitFinalize(null, 0L, 0L, t.message ?: "Recording could not be started.")
        }
    }

    fun pauseRecording() {
        runCatching { activeRecording?.pause() }
    }

    fun resumeRecording() {
        runCatching { activeRecording?.resume() }
    }

    /** Requests a stop; the finalize event arrives asynchronously. */
    fun stopRecording() {
        val recording = activeRecording ?: return
        runCatching { recording.stop() }
    }

    fun close() {
        stopRecording()
        runCatching { provider?.unbindAll() }
        activeRecording = null
        videoCapture = null
        pendingOutputFile = null
        executorService.shutdown()
    }

    fun updateTargetRotationOfActiveRecording(displayRotation: Int) {
        targetRotationDegrees = displayRotation
        if (activeRecording != null) {
            videoCapture?.targetRotation = displayRotation
        }
    }

    private fun emitFinalize(file: File?, durationMs: Long, sizeBytes: Long, error: String?) {
        events.tryEmit(
            VideoEvent.RecordingFinalized(
                FinalizedVideo(file, durationMs, sizeBytes, error)
            )
        )
    }

    private fun eventExecutor(): Executor = executorService

    private fun eventConsumer(): (VideoRecordEvent) -> Unit = { event ->
        when (event) {
            is VideoRecordEvent.Start -> events.tryEmit(VideoEvent.RecordingStarted)
            is VideoRecordEvent.Pause -> events.tryEmit(VideoEvent.RecordingPaused)
            is VideoRecordEvent.Resume -> events.tryEmit(VideoEvent.RecordingResumed)
            is VideoRecordEvent.Finalize -> {
                val file = pendingOutputFile
                pendingOutputFile = null
                activeRecording = null
                val durationMs = event.recordingStats.getRecordedDurationNanos() / 1_000_000L
                val sizeBytes = event.recordingStats.getNumBytesRecorded()
                if (event.hasError()) {
                    // Drop unusable files (for example zero-length stops).
                    if (file != null && file.length() <= 0L) {
                        runCatching { file.delete() }
                    }
                    emitFinalize(
                        file?.takeIf { it.exists() && it.length() > 0L },
                        durationMs,
                        sizeBytes,
                        "Recording failed (CameraX error ${event.error})."
                    )
                } else {
                    emitFinalize(file, durationMs, sizeBytes, null)
                }
            }
            else -> Unit
        }
    }
}
