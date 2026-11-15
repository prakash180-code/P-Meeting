package com.prakash.pmeeting.minutes

import com.prakash.pmeeting.data.model.MinutesStatus

/** Result of merging an upsert request with an existing minutes row. */
data class MinutesMerge(
    val status: MinutesStatus,
    val text: String?,
    val errorMessage: String?
)

/**
 * Pure rules for upserting a minutes row. Kept free of Room/Android types so the
 * "don't lose completed minutes on a failed regeneration" behaviour can be unit
 * tested on the JVM.
 */
object MinutesMergePolicy {

    /**
     * Decides the persisted minutes when [requestedStatus] is written.
     *
     * A failed or pending regeneration must never erase previously completed
     * minutes or hide them from editing/deletion. When completed minutes with
     * text already exist and the request is not COMPLETED, the completed row is
     * preserved and the failure is surfaced through [MinutesMerge.errorMessage].
     */
    fun merge(
        existingStatus: String?,
        existingText: String?,
        requestedStatus: MinutesStatus,
        requestedText: String?,
        requestedError: String?
    ): MinutesMerge {
        val existingCompleted = existingStatus == MinutesStatus.COMPLETED.name
        val keepExistingCompleted = existingCompleted &&
            !existingText.isNullOrBlank() &&
            requestedStatus != MinutesStatus.COMPLETED

        return if (keepExistingCompleted) {
            MinutesMerge(
                status = MinutesStatus.COMPLETED,
                text = existingText,
                errorMessage = requestedError
            )
        } else {
            val text = if (existingStatus != null && requestedStatus != MinutesStatus.COMPLETED) {
                existingText
            } else {
                requestedText
            }
            MinutesMerge(
                status = requestedStatus,
                text = text,
                errorMessage = if (requestedStatus == MinutesStatus.FAILED) requestedError else null
            )
        }
    }
}