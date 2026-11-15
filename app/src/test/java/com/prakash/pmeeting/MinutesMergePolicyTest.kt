package com.prakash.pmeeting

import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.minutes.MinutesMergePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A failed minutes regeneration must not erase completed minutes. These tests
 * pin the merge rules used by RecordingRepository.upsertMinutes.
 */
class MinutesMergePolicyTest {

    @Test
    fun failedRegeneration_keepsCompletedMinutesAccessible() {
        val merged = MinutesMergePolicy.merge(
            existingStatus = MinutesStatus.COMPLETED.name,
            existingText = "Ship v1 by Friday.",
            requestedStatus = MinutesStatus.FAILED,
            requestedText = null,
            requestedError = "No transcription available."
        )
        assertEquals(MinutesStatus.COMPLETED, merged.status)
        assertEquals("Ship v1 by Friday.", merged.text)
        assertEquals("No transcription available.", merged.errorMessage)
    }

    @Test
    fun pendingRegeneration_keepsCompletedMinutes() {
        val merged = MinutesMergePolicy.merge(
            existingStatus = MinutesStatus.COMPLETED.name,
            existingText = "Decided to adopt Room.",
            requestedStatus = MinutesStatus.PENDING,
            requestedText = null,
            requestedError = null
        )
        assertEquals(MinutesStatus.COMPLETED, merged.status)
        assertEquals("Decided to adopt Room.", merged.text)
        assertNull(merged.errorMessage)
    }

    @Test
    fun newCompletedMinutes_areWritten() {
        val merged = MinutesMergePolicy.merge(
            existingStatus = null,
            existingText = null,
            requestedStatus = MinutesStatus.COMPLETED,
            requestedText = "Fresh minutes",
            requestedError = null
        )
        assertEquals(MinutesStatus.COMPLETED, merged.status)
        assertEquals("Fresh minutes", merged.text)
        assertNull(merged.errorMessage)
    }

    @Test
    fun failedWithNoExistingMinutes_reportsFailure() {
        val merged = MinutesMergePolicy.merge(
            existingStatus = null,
            existingText = null,
            requestedStatus = MinutesStatus.FAILED,
            requestedText = null,
            requestedError = "Transcription is not complete."
        )
        assertEquals(MinutesStatus.FAILED, merged.status)
        assertNull(merged.text)
        assertEquals("Transcription is not complete.", merged.errorMessage)
    }

    @Test
    fun failedWithBlankExistingText_doesNotKeepCompleted() {
        // A completed row with blank text is not usable; treat it as a failure.
        val merged = MinutesMergePolicy.merge(
            existingStatus = MinutesStatus.COMPLETED.name,
            existingText = "   ",
            requestedStatus = MinutesStatus.FAILED,
            requestedText = null,
            requestedError = "boom"
        )
        assertEquals(MinutesStatus.FAILED, merged.status)
    }
}