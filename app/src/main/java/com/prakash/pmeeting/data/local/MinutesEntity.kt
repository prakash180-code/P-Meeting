package com.prakash.pmeeting.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prakash.pmeeting.data.model.MinutesStatus

/**
 * Room row for one set of meeting minutes, keyed by [recordingId] which
 * references [RecordingEntity.id]. Deleting a recording cascades to its minutes.
 */
@Entity(
    tableName = "minutes",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId", unique = true)]
)
data class MinutesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val status: String,
    val text: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun statusString(status: MinutesStatus): String = status.name
        fun statusFromString(value: String): MinutesStatus =
            MinutesStatus.valueOf(value)
    }
}
