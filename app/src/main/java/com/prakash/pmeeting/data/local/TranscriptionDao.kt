package com.prakash.pmeeting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.prakash.pmeeting.data.model.TranscriptionStatus
import kotlinx.coroutines.flow.Flow

/** Lightweight projection used by the library to draw status badges. */
data class RecordingTranscriptionStatus(
    val recordingId: Long,
    val status: String
)

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions WHERE recordingId = :recordingId")
    fun observeByRecordingId(recordingId: Long): Flow<TranscriptionEntity?>

    @Query("SELECT * FROM transcriptions WHERE recordingId = :recordingId")
    suspend fun getByRecordingId(recordingId: Long): TranscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcription: TranscriptionEntity): Long

    @Update
    suspend fun update(transcription: TranscriptionEntity)

    @Query("DELETE FROM transcriptions WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: Long)

    /** Recording ids and statuses, used by the meeting library status badges. */
    @Query("SELECT recordingId, status FROM transcriptions")
    fun observeStatuses(): Flow<List<RecordingTranscriptionStatus>>

    /** Recording ids whose transcription is in any of [statuses]. */
    @Query("SELECT recordingId FROM transcriptions WHERE status IN (:statuses)")
    suspend fun getRecordingIdsWithStatuses(statuses: List<String>): List<Long>
}
