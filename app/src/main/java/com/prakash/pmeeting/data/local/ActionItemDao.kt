package com.prakash.pmeeting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.prakash.pmeeting.data.model.ActionItemStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionItemDao {

    @Query("SELECT * FROM action_items WHERE recordingId = :recordingId ORDER BY createdAt DESC")
    suspend fun getAllByRecordingId(recordingId: Long): List<ActionItemEntity>

    @Query("SELECT * FROM action_items WHERE recordingId = :recordingId ORDER BY createdAt DESC")
    fun observeByRecordingId(recordingId: Long): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items WHERE id = :id")
    suspend fun getById(id: Long): ActionItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ActionItemEntity): Long

    @Update
    suspend fun update(item: ActionItemEntity)

    @Query("DELETE FROM action_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM action_items WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: Long)

    /** Upsert an action item row, storing status as its enum name string. */
    suspend fun upsert(
        recordingId: Long,
        text: String,
        status: ActionItemStatus = ActionItemStatus.OPEN
    ): Long {
        val now = System.currentTimeMillis()
        val entity = ActionItemEntity(
            recordingId = recordingId,
            text = text,
            status = ActionItemEntity.statusString(status),
            createdAt = now
        )
        return insert(entity)
    }
}
