package com.prakash.pmeeting.data.model

/** Status of a minutes job. */
enum class MinutesStatus {
    PENDING,
    COMPLETED,
    FAILED
}

/** AI-generated meeting minutes linked to a recording by id (Phase 2). */
data class Minutes(
    val id: Long = 0L,
    /** Foreign key to [Recording.id]. */
    val recordingId: Long,
    val status: MinutesStatus,
    /** The generated minutes text (only when status == COMPLETED). */
    val text: String? = null,
    /** Error message (only when status == FAILED). */
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
