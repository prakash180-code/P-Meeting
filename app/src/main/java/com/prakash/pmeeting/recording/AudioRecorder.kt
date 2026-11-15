package com.prakash.pmeeting.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.prakash.pmeeting.util.Formats
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] for microphone recording.
 *
 * Firmware-backed pause/resume is available on the min supported level (26),
 * so start/pause/resume/stop are all supported. All failures are returned as
 * [Result.failure] instead of thrown, so callers surface them in the UI.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start(directory: File): kotlin.Result<File> {
        val recorder = newRecorder()
        var file: File? = null
        return try {
            directory.mkdirs()
            file = File(directory, "PM_AUDIO_${Formats.fileNameTimestamp()}.m4a")
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setAudioChannels(CHANNELS)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            this.recorder = recorder
            this.outputFile = file
            Result.success(file)
        } catch (t: Throwable) {
            runCatching { recorder.release() }
            // prepare()/start() may have created a partial/empty file; remove it
            // so a failed start never leaves an orphaned media file behind.
            file?.let { f -> runCatching { if (f.exists()) f.delete() } }
            Result.failure(t)
        }
    }

    fun pause() {
        runCatching { recorder?.pause() }
    }

    fun resume(): Boolean {
        val current = recorder ?: return false
        return runCatching { current.resume() }
            .map { }
            .isSuccess
    }

    fun stop(): kotlin.Result<File> {
        val current = recorder ?: return Result.failure(IllegalStateException("Not recording."))
        val file = outputFile
        recorder = null
        outputFile = null
        return try {
            current.stop()
            if (file == null) Result.failure(IllegalStateException("No output file."))
            else if (file.length() <= 0L) {
                runCatching { file.delete() }
                Result.failure(IllegalStateException("Recording was empty."))
            } else {
                Result.success(file)
            }
        } catch (t: Throwable) {
            file?.delete()
            Result.failure(t)
        } finally {
            runCatching { current.release() }
        }
    }

    /** Stops recording and discards the file; used for explicit cancel. */
    fun cancel() {
        val current = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
        runCatching { file?.delete() }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 128_000
        const val CHANNELS = 2
    }
}
