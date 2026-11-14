package com.prakash.pmeeting

import android.app.Application
import androidx.room.Room
import com.prakash.pmeeting.actionitem.ActionItemService
import com.prakash.pmeeting.actionitem.ActionItemServiceStub
import com.prakash.pmeeting.data.local.ActionItemDao
import com.prakash.pmeeting.data.local.MIGRATION_1_4
import com.prakash.pmeeting.data.local.MIGRATION_4_5
import com.prakash.pmeeting.data.local.MinutesDao
import com.prakash.pmeeting.data.local.PMeetingDatabase
import com.prakash.pmeeting.data.local.RecordingDao
import com.prakash.pmeeting.data.local.TranscriptionDao
import com.prakash.pmeeting.data.local.TranscriptionSegmentDao
import com.prakash.pmeeting.data.repository.RecordingRepository
import com.prakash.pmeeting.minutes.MinutesService
import com.prakash.pmeeting.minutes.MinutesServiceImpl
import com.prakash.pmeeting.transcription.ModelManager
import com.prakash.pmeeting.transcription.TranscriptionLanguageStore
import com.prakash.pmeeting.transcription.TranscriptionService
import com.prakash.pmeeting.transcription.TranscriptionServiceImpl
import com.prakash.pmeeting.transcription.WhisperEngine
import com.prakash.pmeeting.ui.theme.ThemePreference

class AppContainer(application: Application) {

    private val database: PMeetingDatabase =
        Room.databaseBuilder(application, PMeetingDatabase::class.java, PMeetingDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_4, MIGRATION_4_5)
            .build()

    private val recordingDao: RecordingDao = database.recordingDao()
    private val transcriptionDao: TranscriptionDao = database.transcriptionDao()
    private val transcriptionSegmentDao: TranscriptionSegmentDao = database.transcriptionSegmentDao()
    private val minutesDao: MinutesDao = database.minutesDao()
    private val actionItemDao: ActionItemDao = database.actionItemDao()

    val recordingRepository: RecordingRepository =
        RecordingRepository(
            database,
            recordingDao,
            transcriptionDao,
            transcriptionSegmentDao,
            minutesDao,
            actionItemDao,
            application.applicationContext
        )

    /** Shared Whisper engine instance used by both model validation and transcription. */
    val whisperEngine: WhisperEngine = WhisperEngine(application.applicationContext)

    /** Persisted transcription language preference (Auto / US English). */
    val languageStore: TranscriptionLanguageStore = TranscriptionLanguageStore.create(application.applicationContext)

    /** Persisted light/dark theme override (null = follow system). */
    val themePreference: ThemePreference = ThemePreference(application.applicationContext)

    /** Model download, validation, and storage manager. */
    val modelManager: ModelManager =
        ModelManager(application.applicationContext, whisperEngine)

    /** File-based transcription service. Processes the recording file after save. */
    val transcriptionService: TranscriptionService =
        TranscriptionServiceImpl(recordingRepository, application.applicationContext, whisperEngine)

    /** Minutes service with rule-based extraction from transcription text. */
    val minutesService: MinutesService =
        MinutesServiceImpl(recordingRepository)

    /** Phase 2 hook: action-item extraction service. Stubbed in Phase 1. */
    val actionItemService: ActionItemService =
        ActionItemServiceStub(recordingRepository)
}
