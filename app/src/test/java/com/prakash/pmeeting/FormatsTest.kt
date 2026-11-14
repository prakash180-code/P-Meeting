package com.prakash.pmeeting

import com.prakash.pmeeting.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun formatDuration_partialMinute() {
        assertEquals("00:05", Formats.formatDuration(5_000))
        assertEquals("00:59", Formats.formatDuration(59_999))
    }

    @Test
    fun formatDuration_minutes() {
        assertEquals("01:00", Formats.formatDuration(60_000))
        assertEquals("16:40", Formats.formatDuration(1_000_000))
    }

    @Test
    fun formatDuration_hours() {
        assertEquals("01:01:15", Formats.formatDuration(3_675_000))
        assertEquals("02:00:00", Formats.formatDuration(7_200_000))
    }

    @Test
    fun formatDuration_clampsNegativeToZero() {
        assertEquals("00:00", Formats.formatDuration(-5_000))
    }

    @Test
    fun formatFileSize_units() {
        assertEquals("512 B", Formats.formatFileSize(512))
        assertEquals("1.0 KB", Formats.formatFileSize(1024))
        assertEquals("1.5 MB", Formats.formatFileSize((1024 * 1024 * 1.5).toLong()))
        assertEquals("2.00 GB", Formats.formatFileSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun fileNameTimestamp_shape() {
        assert(Formats.fileNameTimestamp().matches(Regex("\\d{8}_\\d{6}"))) {
            "Expected yyyyMMdd_HHmmss, got ${Formats.fileNameTimestamp()}"
        }
    }
}
