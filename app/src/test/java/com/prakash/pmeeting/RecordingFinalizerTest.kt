package com.prakash.pmeeting

import com.prakash.pmeeting.recording.RecordingFinalizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finalized take may only be persisted when CameraX reported success and the
 * file holds actual bytes. These rules guard the recording-loss bugs where an
 * empty or failed take could overwrite or orphan a real recording.
 */
class RecordingFinalizerTest {

    @Test
    fun usable_whenNoErrorAndFileHasBytes() {
        assertTrue(RecordingFinalizer.isUsableTake(fileExists = true, lengthBytes = 1_024, errorMessage = null))
    }

    @Test
    fun notUsable_whenFileMissing() {
        assertFalse(RecordingFinalizer.isUsableTake(fileExists = false, lengthBytes = 1_024, errorMessage = null))
    }

    @Test
    fun notUsable_whenFileEmpty() {
        assertFalse(RecordingFinalizer.isUsableTake(fileExists = true, lengthBytes = 0, errorMessage = null))
    }

    @Test
    fun notUsable_whenCameraXReportedError() {
        // A non-empty file can still accompany an error; it must not be saved.
        assertFalse(
            RecordingFinalizer.isUsableTake(
                fileExists = true,
                lengthBytes = 2_048,
                errorMessage = "Recording failed (CameraX error 1)."
            )
        )
    }

    @Test
    fun notUsable_whenErrorEvenIfFileLooksPresent() {
        assertFalse(RecordingFinalizer.isUsableTake(fileExists = true, lengthBytes = 10, errorMessage = "boom"))
    }
}