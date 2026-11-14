package com.prakash.pmeeting.data.model

/** A saved recording as used by the UI layer. */
data class Recording(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val mediaType: MediaType,
    val source: RecordingSource,
    val storageKind: StorageKind,
    /** Absolute file path (INTERNAL_FILE) or content URI string (EXTERNAL_URI). */
    val fileUri: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Whether the recording is archived (hidden from the active library). */
    val archived: Boolean = false
)
