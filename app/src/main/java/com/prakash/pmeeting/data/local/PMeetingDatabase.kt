package com.prakash.pmeeting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptionEntity::class,
        MinutesEntity::class,
        ActionItemEntity::class,
        TranscriptionSegmentEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PMeetingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun transcriptionSegmentDao(): TranscriptionSegmentDao
    abstract fun minutesDao(): MinutesDao
    abstract fun actionItemDao(): ActionItemDao

    companion object {
        const val DATABASE_NAME = "pmeeting.db"
    }
}
