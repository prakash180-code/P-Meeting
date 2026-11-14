package com.prakash.pmeeting.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.prakash.pmeeting.data.model.TranscriptionStatus

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val status: String,
    val text: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun statusString(status: TranscriptionStatus): String = status.name

        /**
         * Parses a persisted status. Rows written by older builds only contain
         * PENDING/COMPLETED/FAILED; anything unknown is treated as FAILED so a
         * corrupt value can never crash the library or detail screen.
         */
        fun statusFromString(value: String): TranscriptionStatus =
            TranscriptionStatus.entries.firstOrNull { it.name == value }
                ?: TranscriptionStatus.FAILED
    }
}
