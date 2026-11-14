package com.prakash.pmeeting.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small formatting helpers shared by UI, tests and file naming. */
object Formats {

    /** Elapsed clock with zero-padded fields: "MM:SS" under an hour, "HH:MM:SS" for an hour or more. */
    fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    /** Human readable file size such as "512 B", "1.5 MB", "2.00 GB". */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /** "2 Nov 2026, 14:30" in the device's default locale. */
    fun formatDate(epochMillis: Long): String =
        SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT)
            .format(Date(epochMillis))

    /** Safe unique-ish fragment used to name media files, e.g "20261102_143005". */
    fun fileNameTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
