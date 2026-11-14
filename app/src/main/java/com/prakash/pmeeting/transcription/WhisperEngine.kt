package com.prakash.pmeeting.transcription

import android.content.Context
import com.prakash.pmeeting.data.model.TranscriptionSegment
import java.io.File

/**
 * On-device transcription engine backed by whisper.cpp via JNI.
 *
 * The native library (whisper-jni.so) must be built from the whisper.cpp
 * source (see app/src/main/cpp/CMakeLists.txt). Until the real whisper.cpp
 * source is linked, [transcribe] returns an error indicating the backend
 * is not available.
 */
class WhisperEngine(private val context: Context) {

    private var nativeCtx: Long = 0L
    private var modelPath: String? = null

    /**
     * Guards the native context. The unit test runner and the UI can trigger
     * overlapping operations (load, transcribe, release), and whisper.cpp's
     * context is not thread-safe: concurrent use corrupts ggml state and can
     * abort the process inside the native backend. All native calls are
     * serialized through this lock.
     */
    private val nativeLock = Any()

    companion object {
        /** Model directory within app-private external files dir. */
        const val MODEL_DIR_NAME = "whisper_models"

        /** Default model: tiny.en (fast, ~39 MB, English only). Suitable for Moto G32. */
        const val DEFAULT_MODEL_NAME = "ggml-tiny.en.bin"

        /** Model download URL (must be obtained by the user; not bundled). */
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
    }

    init {
        try {
            System.loadLibrary("whisper-jni")
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available; transcription will fail gracefully.
        }
    }

    /** Returns true if the native whisper library is loaded. */
    fun isAvailable(): Boolean = synchronized(nativeLock) {
        nativeCtx != 0L || isLibraryLoaded()
    }

    /** Returns true if a model has been successfully loaded into the native engine. */
    fun isModelLoaded(): Boolean = synchronized(nativeLock) { nativeCtx != 0L }

    private fun isLibraryLoaded(): Boolean {
        return try {
            System.loadLibrary("whisper-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Loads a whisper model from [modelFile]. Returns true on success. */
    fun loadModel(modelFile: File): Boolean = synchronized(nativeLock) {
        if (!modelFile.exists()) return false
        if (nativeCtx != 0L) {
            nativeFree(nativeCtx)
            nativeCtx = 0L
        }
        val ctx = nativeInit(modelFile.absolutePath)
        if (ctx == 0L) return false
        nativeCtx = ctx
        modelPath = modelFile.absolutePath
        true
    }

    /**
     * Transcribes audio samples (16kHz mono, 16-bit PCM) and returns timestamped
     * segments. Returns null if the engine is not available or transcription fails.
     */
    fun transcribe(
        samples: FloatArray,
        nThreads: Int = 4,
        language: String? = null
    ): List<TranscriptionSegment>? = synchronized(nativeLock) {
        if (nativeCtx == 0L) return null
        transcribeFloat(samples, nThreads, language)
    }

    fun release() {
        synchronized(nativeLock) {
            if (nativeCtx != 0L) {
                nativeFree(nativeCtx)
                nativeCtx = 0L
            }
            modelPath = null
        }
    }

    // ---------------------------------------------------------------------------
    // JNI declarations
    // ---------------------------------------------------------------------------

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ctxPtr: Long)

    /**
     * Native transcription returns a raw array of segments. The JNI layer fills
     * whisper's segment timestamps, which are expressed in centiseconds
     * (10 ms units); [transcribeFloat] converts them to milliseconds.
     */
    private external fun nativeTranscribe(
        ctxPtr: Long,
        pcm16: ByteArray,
        sampleRate: Int,
        nThreads: Int,
        language: String?
    ): Array<TranscriptionSegment>?

    /**
     * Convenience: converts FloatArray (already in -1.0..1.0 range) to int16 PCM
     * bytes for the JNI layer.
     */
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767.0f).coerceIn(-32768f, 32767f).toInt()
            bytes[2 * i] = (s and 0xFF).toByte()
            bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // Overload that accepts FloatArray directly
    internal fun transcribeFloat(
        samples: FloatArray,
        nThreads: Int = 4,
        language: String? = null
    ): List<TranscriptionSegment>? = synchronized(nativeLock) {
        if (nativeCtx == 0L) return null
        val pcm = floatToPcm16(samples)
        val segments = nativeTranscribe(nativeCtx, pcm, 16000, nThreads, language) ?: return null
        // whisper.cpp timestamps are centiseconds; expose milliseconds.
        segments.map { segment ->
            segment.copy(
                startMs = segment.startMs * 10L,
                endMs = segment.endMs * 10L
            )
        }
    }
}
