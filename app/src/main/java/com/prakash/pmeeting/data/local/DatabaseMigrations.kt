package com.prakash.pmeeting.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Upgrades the Phase 1 schema (recordings only) to schema v4, which adds the
 * transcription, minutes, action-item and transcript-segment tables.
 *
 * Existing recording rows are preserved; only new tables are created.
 */
val MIGRATION_1_4 = object : Migration(1, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transcriptions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordingId` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`text` TEXT, " +
                "`errorMessage` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `minutes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordingId` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`text` TEXT, " +
                "`errorMessage` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_minutes_recordingId` " +
                "ON `minutes` (`recordingId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `action_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordingId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`completedAt` INTEGER, " +
                "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_action_items_recordingId` " +
                "ON `action_items` (`recordingId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transcription_segments` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`transcriptionId` INTEGER NOT NULL, " +
                "`startMs` INTEGER NOT NULL, " +
                "`endMs` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "FOREIGN KEY(`transcriptionId`) REFERENCES `transcriptions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transcription_segments_transcriptionId` " +
                "ON `transcription_segments` (`transcriptionId`)"
        )
    }
}

/**
 * Upgrades schema v4 to v5 by adding an [archived] flag to the [recordings]
 * table. Existing recording rows are preserved and default to not archived
 * (0), so they stay visible in the active library.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add the archived column only if it does not already exist, so the
        // migration is safe to re-run against a partially migrated database.
        val columns = db.query("PRAGMA table_info(recordings)").use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                names.add(cursor.getString(1))
            }
            names
        }
        if (!columns.contains("archived")) {
            db.execSQL(
                "ALTER TABLE `recordings` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
