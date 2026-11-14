package com.prakash.pmeeting.data.model

/** Status of an action item. */
enum class ActionItemStatus {
    OPEN,
    COMPLETED
}

/** An action item extracted from a meeting, linked to a recording by id (Phase 2). */
data class ActionItem(
    val id: Long = 0L,
    /** Foreign key to [Recording.id]. */
    val recordingId: Long,
    /** The action item text. */
    val text: String,
    val status: ActionItemStatus = ActionItemStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
