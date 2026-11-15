package com.prakash.pmeeting.recording

/**
 * Pure rules shared by the record screens for deciding whether a finalized
 * take is worth persisting. Kept free of Android/CameraX types so it can be
 * unit tested on the JVM.
 */
object RecordingFinalizer {

    /**
     * A take is usable only when CameraX reported no error and the produced
     * file actually contains bytes. Zero-length files (for example an
     * interrupted stop) are treated as failures so they are never saved or
     * enqueued for transcription.
     */
    fun isUsableTake(fileExists: Boolean, lengthBytes: Long, errorMessage: String?): Boolean =
        errorMessage == null && fileExists && lengthBytes > 0L
}