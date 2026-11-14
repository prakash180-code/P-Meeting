package com.prakash.pmeeting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.prakash.pmeeting.data.model.MinutesStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MinutesDao {

    @Query("SELECT * FROM minutes WHERE recordingId = :recordingId")
    fun observeByRecordingId(recordingId: Long): Flow<MinutesEntity?>

    @Query("SELECT * FROM minutes WHERE recordingId = :recordingId")
    suspend fun getByRecordingId(recordingId: Long): MinutesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(minutes: MinutesEntity): Long

    @Update
    suspend fun update(minutes: MinutesEntity)

    @Query("DELETE FROM minutes WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: Long)

    /** Upsert a minutes row, storing status as its enum name string. */
    suspend fun upsert(
        recordingId: Long,
        status: MinutesStatus,
        text: String? = null,
        errorMessage: String? = null
    ): Long {
        val existing = getByRecordingId(recordingId)
        val now = System.currentTimeMillis()
        val entity = if (existing != null) {
            existing.copy(
                status = MinutesEntity.statusString(status),
                text = text,
                errorMessage = errorMessage,
                updatedAt = now
            )
        } else {
            MinutesEntity(
                recordingId = recordingId,
                status = MinutesEntity.statusString(status),
                text = text,
                errorMessage = errorMessage,
                createdAt = now,
                updatedAt = now
            )
        }
        return insert(entity)
    }
}
