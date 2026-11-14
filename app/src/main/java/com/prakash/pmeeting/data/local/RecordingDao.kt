package com.prakash.pmeeting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    /** Active (non-archived) recordings, newest first. */
    @Query("SELECT * FROM recordings WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    /** Archived recordings, newest first. */
    @Query("SELECT * FROM recordings WHERE archived = 1 ORDER BY createdAt DESC")
    fun observeArchived(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id AND archived = 0")
    suspend fun getActiveById(id: Long): RecordingEntity?

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
