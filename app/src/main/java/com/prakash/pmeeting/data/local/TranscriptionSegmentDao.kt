package com.prakash.pmeeting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionSegmentDao {

    @Query("SELECT * FROM transcription_segments WHERE transcriptionId = :transcriptionId ORDER BY startMs ASC")
    fun observeByTranscriptionId(transcriptionId: Long): Flow<List<TranscriptionSegmentEntity>>

    @Query("SELECT * FROM transcription_segments WHERE transcriptionId = :transcriptionId ORDER BY startMs ASC")
    suspend fun getByTranscriptionId(transcriptionId: Long): List<TranscriptionSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptionSegmentEntity>)

    @Query("DELETE FROM transcription_segments WHERE transcriptionId = :transcriptionId")
    suspend fun deleteByTranscriptionId(transcriptionId: Long)
}
