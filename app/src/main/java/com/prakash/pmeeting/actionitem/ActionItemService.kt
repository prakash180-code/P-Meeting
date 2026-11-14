package com.prakash.pmeeting.actionitem

import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow

interface ActionItemService {

    suspend fun extractActionItems(recordingId: Long): List<Long>

    fun observeActionItems(recordingId: Long): Flow<List<ActionItem>>

    suspend fun toggleActionItem(itemId: Long)

    suspend fun cancel(recordingId: Long)
}

/**
 * Phase 1 stub: no real action-item extraction backend is wired in yet.
 * Persists nothing; returns an empty list.
 */
class ActionItemServiceStub(
    private val repository: RecordingRepository
) : ActionItemService {

    override suspend fun extractActionItems(recordingId: Long): List<Long> =
        emptyList()

    override fun observeActionItems(recordingId: Long): Flow<List<ActionItem>> =
        repository.observeActionItems(recordingId)

    override suspend fun toggleActionItem(itemId: Long) =
        repository.toggleActionItem(itemId)

    override suspend fun cancel(recordingId: Long) = Unit
}
