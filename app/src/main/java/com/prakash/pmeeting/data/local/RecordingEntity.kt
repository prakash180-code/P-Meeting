package com.prakash.pmeeting.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind

/**
 * Room row for one recording entry.
 *
 * Only metadata lives here; media bytes always stay outside the database.
 * Phase 2 (transcripts, minutes, action items) can extend this schema with a
 * migration and add their own tables without touching the recording module.
 *
 * [archived] marks the recording as moved to the archive view. Archived
 * recordings keep their files, transcripts, minutes and action items and are
 * hidden from the active library until restored.
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val mediaType: MediaType,
    val source: RecordingSource,
    val storageKind: StorageKind,
    val fileUri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val archived: Boolean = false
)
