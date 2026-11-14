package com.prakash.pmeeting.data.model

/**
 * Status of a transcription job.
 *
 * The status is persisted as its enum name in a TEXT column, so new values do
 * not require a database migration:
 * - [PENDING]: queued (automatic or manual) but not started yet.
 * - [PROCESSING]: the background worker is running the engine right now.
 * - [WAITING_FOR_MODEL]: no Whisper model is installed/validated.
 * - [COMPLETED]: a transcript (text + segments) was produced successfully.
 * - [FAILED]: the latest run failed; a previous successful transcript, if any,
 *   is intentionally preserved.
 */
enum class TranscriptionStatus {
    PENDING,
    PROCESSING,
    WAITING_FOR_MODEL,
    COMPLETED,
    FAILED
}

/** One timestamped segment of a transcript. */
data class TranscriptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** A transcription result linked to a recording by id (Phase 2). */
data class Transcription(
    val id: Long = 0L,
    /** Foreign key to [Recording.id]. */
    val recordingId: Long,
    val status: TranscriptionStatus,
    /** The full transcribed text (only when status == COMPLETED). */
    val text: String? = null,
    /** Timestamped segments (only when status == COMPLETED). */
    val segments: List<TranscriptionSegment> = emptyList(),
    /** Error message (only when status == FAILED). */
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
