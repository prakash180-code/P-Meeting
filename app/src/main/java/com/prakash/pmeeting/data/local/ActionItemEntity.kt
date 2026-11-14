package com.prakash.pmeeting.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prakash.pmeeting.data.model.ActionItemStatus

/**
 * Room row for one action item, keyed by [recordingId] which references
 * [RecordingEntity.id]. Deleting a recording cascades to its action items.
 * Unlike minutes (one per recording), there can be many action items per
 * recording.
 */
@Entity(
    tableName = "action_items",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId")]
)
data class ActionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val text: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    companion object {
        fun statusString(status: ActionItemStatus): String = status.name
        fun statusFromString(value: String): ActionItemStatus =
            ActionItemStatus.valueOf(value)
    }
}
