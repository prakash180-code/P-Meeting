package com.prakash.pmeeting.minutes

import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow

interface MinutesService {

    suspend fun generateMinutes(recordingId: Long): Long

    fun observeMinutes(recordingId: Long): Flow<Minutes?>

    suspend fun cancel(recordingId: Long)
}

/**
 * Phase 1 stub: no real minutes backend is wired in yet. Persists a FAILED
 * minutes row so the UI shows a clear message instead of crashing.
 */
class MinutesServiceStub(
    private val repository: RecordingRepository
) : MinutesService {

    override suspend fun generateMinutes(recordingId: Long): Long {
        return repository.upsertMinutes(
            recordingId = recordingId,
            status = MinutesStatus.FAILED,
            errorMessage = "Minutes backend not implemented in Phase 1."
        )
    }

    override fun observeMinutes(recordingId: Long): Flow<Minutes?> =
        repository.observeMinutes(recordingId)

    override suspend fun cancel(recordingId: Long) = Unit
}
