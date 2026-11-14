package com.prakash.pmeeting.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for one timestamped transcript segment, linked to a [TranscriptionEntity].
 */
@Entity(
    tableName = "transcription_segments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("transcriptionId")]
)
data class TranscriptionSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transcriptionId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String
)
