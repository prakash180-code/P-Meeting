package com.prakash.pmeeting.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.prakash.pmeeting.data.local.ActionItemDao
import com.prakash.pmeeting.data.local.ActionItemEntity
import com.prakash.pmeeting.data.local.MinutesDao
import com.prakash.pmeeting.data.local.MinutesEntity
import com.prakash.pmeeting.data.local.PMeetingDatabase
import com.prakash.pmeeting.data.local.RecordingDao
import com.prakash.pmeeting.data.local.TranscriptionDao
import com.prakash.pmeeting.data.local.TranscriptionSegmentDao
import com.prakash.pmeeting.data.local.TranscriptionSegmentEntity
import com.prakash.pmeeting.data.local.toEntity
import com.prakash.pmeeting.data.local.toModel
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionSegment
import com.prakash.pmeeting.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for recording persistence in Phase 1.
 *
 * Media bytes live in app-private storage (or remain at their original
 * location for imported URIs); this repository only owns metadata plus the
 * bookkeeping around creating/renaming/deleting entries.
 */
class RecordingRepository(
    private val database: PMeetingDatabase,
    private val dao: RecordingDao,
    private val transcriptionDao: TranscriptionDao,
    private val transcriptionSegmentDao: TranscriptionSegmentDao,
    private val minutesDao: MinutesDao,
    private val actionItemDao: ActionItemDao,
    private val context: Context
) {

    val recordings: Flow<List<Recording>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    /** Archived recordings, newest first. */
    val archivedRecordings: Flow<List<Recording>> =
        dao.observeArchived().map { entities -> entities.map { it.toModel() } }

    fun observeRecording(id: Long): Flow<Recording?> =
        dao.observeById(id).map { it?.toModel() }

    /** Suspends and returns the recording by id, or null if not found. */
    suspend fun getRecording(id: Long): Recording? =
        withContext(Dispatchers.IO) {
            dao.getById(id)?.toModel()
        }

    /** Returns the active recording by id, or null if it is archived or missing. */
    suspend fun getActiveRecording(id: Long): Recording? =
        withContext(Dispatchers.IO) {
            dao.getActiveById(id)?.toModel()
        }

    /** Observes the transcription (including timestamped segments) for a recording. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTranscription(recordingId: Long): Flow<Transcription?> =
        transcriptionDao.observeByRecordingId(recordingId).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                transcriptionSegmentDao.observeByTranscriptionId(entity.id).map { segments ->
                    entity.toModel().copy(segments = segments.map { it.toModel() })
                }
            }
        }

    /** Observes the minutes for a recording, if any. */
    fun observeMinutes(recordingId: Long): Flow<Minutes?> =
        minutesDao.observeByRecordingId(recordingId).map { it?.toModel() }

    /** Observes the action items for a recording. */
    fun observeActionItems(recordingId: Long): Flow<List<ActionItem>> =
        actionItemDao.observeByRecordingId(recordingId).map { entities ->
            entities.map { it.toModel() }
        }

    /**
     * Observes the persisted transcription status of every recording.
     *
     * The meeting library uses this to draw a status badge that survives app
     * restarts and updates automatically when a background job starts or ends.
     */
    fun observeTranscriptionStatuses(): Flow<Map<Long, com.prakash.pmeeting.data.model.TranscriptionStatus>> =
        transcriptionDao.observeStatuses().map { rows ->
            rows.associate { row ->
                row.recordingId to com.prakash.pmeeting.data.local.TranscriptionEntity
                    .statusFromString(row.status)
            }
        }

    suspend fun save(recording: Recording): Long = withContext(Dispatchers.IO) {
        dao.insert(recording.toEntity())
    }

    suspend fun rename(id: Long, newTitle: String) = withContext(Dispatchers.IO) {
        val existing = dao.getById(id) ?: return@withContext
        dao.update(existing.copy(title = newTitle))
    }

    /**
     * Deletes a single recording. The database rows are removed first (in a
     * transaction) and only then the internal media file, so a failed database
     * deletion never leaves a library row pointing at a deleted file. Returns
     * true if every file that needed deletion was deleted successfully.
     */
    suspend fun delete(recording: Recording): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            transcriptionDao.deleteByRecordingId(recording.id)
            dao.deleteById(recording.id)
        }
        deleteFileFor(recording)
    }

    /**
     * Archives or restores the given recordings. Recordings keep their files and
     * all database information; only the [archived] flag flips. Runs in a single
     * transaction so the change is persistent and atomic.
     */
    suspend fun setArchived(ids: List<Long>, archived: Boolean) = withContext(Dispatchers.IO) {
        database.withTransaction {
            for (id in ids) {
                val existing = dao.getById(id) ?: continue
                dao.update(existing.copy(archived = archived))
            }
        }
    }

    /** Archives the given recording ids. */
    suspend fun archive(ids: List<Long>) = setArchived(ids, archived = true)

    /** Restores the given archived recording ids to the active library. */
    suspend fun restore(ids: List<Long>) = setArchived(ids, archived = false)

    /**
     * Deletes multiple recordings. For each recording the media file (if stored
     * internally) and all database records (transcription, segments, minutes,
     * action items) are removed. Database updates run in a transaction per
     * recording; a file deletion failure does not roll back the database removal
     * but is reported so the caller can surface it.
     *
     * Returns true if every file that needed deletion was deleted successfully.
     */
    suspend fun deleteSelected(ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        var allFilesDeleted = true
        for (id in ids) {
            val recording = dao.getById(id)?.toModel() ?: continue
            // Remove the database rows first; only delete the media file once
            // the rows are gone, so no row can point at a deleted file.
            database.withTransaction {
                // TranscriptionSegmentEntity cascades from transcriptions;
                // minutes and action items cascade from recordings. Transcription
                // has no FK, so delete it explicitly before the recording row.
                transcriptionDao.deleteByRecordingId(id)
                dao.deleteById(id)
            }
            allFilesDeleted = deleteFileFor(recording) && allFilesDeleted
        }
        allFilesDeleted
    }

    /** Deletes the media file for an internal recording; linked URIs are untouched. */
    private fun deleteFileFor(recording: Recording): Boolean {
        return if (recording.storageKind == StorageKind.INTERNAL_FILE) {
            runCatching {
                val file = File(recording.fileUri)
                !file.exists() || file.delete()
            }.getOrDefault(false)
        } else {
            // EXTERNAL_URI: the original file stays where it is.
            true
        }
    }

    /** Back-fills sizeBytes once a freshly recorded file is finalized. */
    suspend fun updateSize(id: Long) = withContext(Dispatchers.IO) {
        val existing = dao.getById(id) ?: return@withContext
        val file = File(existing.fileUri)
        if (existing.storageKind == StorageKind.INTERNAL_FILE && file.exists()) {
            dao.update(existing.copy(sizeBytes = file.length()))
        }
    }

    /** Deletes an internal recording file directly; never touches URIs. */
    suspend fun deleteInternalFile(fileUri: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(fileUri)
            !file.exists() || file.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Transcription (Phase 2)

    /** Upserts a transcription row for a recording. */
    suspend fun upsertTranscription(
        recordingId: Long,
        status: com.prakash.pmeeting.data.model.TranscriptionStatus,
        text: String? = null,
        errorMessage: String? = null
    ): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = transcriptionDao.getByRecordingId(recordingId)
            // Never overwrite an existing successful transcript with a failure:
            // keep the previous text and only record the new status / error.
            val finalText = if (existing != null && status != com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED) {
                existing.text
            } else {
                text
            }
            // WAITING_FOR_MODEL also carries an explanation, so keep the
            // message for every non-completed state.
            val storedError = if (status == com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED) {
                null
            } else {
                errorMessage
            }
            val entity = if (existing != null) {
                existing.copy(
                    status = com.prakash.pmeeting.data.local.TranscriptionEntity.statusString(status),
                    text = finalText,
                    errorMessage = storedError,
                    updatedAt = now
                )
            } else {
                com.prakash.pmeeting.data.local.TranscriptionEntity(
                    recordingId = recordingId,
                    status = com.prakash.pmeeting.data.local.TranscriptionEntity.statusString(status),
                    text = if (status == com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED) text else null,
                    errorMessage = storedError,
                    createdAt = now,
                    updatedAt = now
                )
            }
            transcriptionDao.insert(entity)
        }
    }

    /** Gets the transcription (including timestamped segments) for a recording, if any. */
    suspend fun getTranscription(recordingId: Long): com.prakash.pmeeting.data.model.Transcription? =
        withContext(Dispatchers.IO) {
            val entity = transcriptionDao.getByRecordingId(recordingId) ?: return@withContext null
            val segments = transcriptionSegmentDao.getByTranscriptionId(entity.id).map { it.toModel() }
            entity.toModel().copy(segments = segments)
        }

    /** Deletes the transcription for a recording. */
    suspend fun deleteTranscription(recordingId: Long) = withContext(Dispatchers.IO) {
        transcriptionDao.deleteByRecordingId(recordingId)
    }

    /** Cheap status-only read, used by the background worker without loading segments. */
    suspend fun getTranscriptionStatus(
        recordingId: Long
    ): com.prakash.pmeeting.data.model.TranscriptionStatus? = withContext(Dispatchers.IO) {
        transcriptionDao.getByRecordingId(recordingId)?.let {
            com.prakash.pmeeting.data.local.TranscriptionEntity.statusFromString(it.status)
        }
    }

    /**
     * Recording ids whose transcription status is one of [statuses]. Used on
     * app start and after a model download to resume interrupted or
     * waiting-for-model jobs.
     */
    suspend fun getRecordingIdsWithTranscriptionStatuses(
        statuses: List<com.prakash.pmeeting.data.model.TranscriptionStatus>
    ): List<Long> = withContext(Dispatchers.IO) {
        transcriptionDao.getRecordingIdsWithStatuses(statuses.map { it.name })
    }

    /** Upserts a transcription with timestamped segments for a recording. */
    suspend fun upsertTranscriptionWithSegments(
        recordingId: Long,
        status: com.prakash.pmeeting.data.model.TranscriptionStatus,
        text: String? = null,
        segments: List<TranscriptionSegment> = emptyList(),
        errorMessage: String? = null
    ): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = transcriptionDao.getByRecordingId(recordingId)
            // Only a successful run replaces the transcript text and segments. A
            // failed or pending run preserves the previous successful transcript
            // so a retry can never erase existing data.
            val preserveExisting = existing != null && status != com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED
            val finalText = if (preserveExisting) existing.text else text
            // WAITING_FOR_MODEL also carries an explanation, so keep the message
            // for every non-completed state.
            val storedError = if (status == com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED) {
                null
            } else {
                errorMessage
            }
            val entity = if (existing != null) {
                existing.copy(
                    status = com.prakash.pmeeting.data.local.TranscriptionEntity.statusString(status),
                    text = finalText,
                    errorMessage = storedError,
                    updatedAt = now
                )
            } else {
                com.prakash.pmeeting.data.local.TranscriptionEntity(
                    recordingId = recordingId,
                    status = com.prakash.pmeeting.data.local.TranscriptionEntity.statusString(status),
                    text = if (status == com.prakash.pmeeting.data.model.TranscriptionStatus.COMPLETED) text else null,
                    errorMessage = storedError,
                    createdAt = now,
                    updatedAt = now
                )
            }
            val transcriptionId = transcriptionDao.insert(entity)

            // Replace all segments for this transcription, but only when we have
            // new segments to store (a completed run). Failed/pending runs keep
            // the previously stored segments intact.
            if (!preserveExisting) {
                transcriptionSegmentDao.deleteByTranscriptionId(transcriptionId)
                if (segments.isNotEmpty()) {
                    transcriptionSegmentDao.insertAll(
                        segments.map { segment ->
                            TranscriptionSegmentEntity(
                                transcriptionId = transcriptionId,
                                startMs = segment.startMs,
                                endMs = segment.endMs,
                                text = segment.text
                            )
                        }
                    )
                }
            }

            transcriptionId
        }
    }

    /** Gets the transcription segments for a recording, if any. */
    suspend fun getTranscriptionSegments(recordingId: Long): List<TranscriptionSegment> =
        withContext(Dispatchers.IO) {
            val transcription = transcriptionDao.getByRecordingId(recordingId) ?: return@withContext emptyList()
            transcriptionSegmentDao.observeByTranscriptionId(transcription.id).first().map { it.toModel() }
        }

    // ------------------------------------------------------------------ //
    // Minutes (Phase 2)

    /** Upserts a minutes row for a recording. */
    suspend fun upsertMinutes(
        recordingId: Long,
        status: MinutesStatus,
        text: String? = null,
        errorMessage: String? = null
    ): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = minutesDao.getByRecordingId(recordingId)
            // A failed regeneration must not erase previously generated minutes
            // or hide them from editing/deletion. When completed minutes already
            // exist, keep them as COMPLETED and surface the failure via
            // errorMessage so the user can still edit or delete them.
            val merged = com.prakash.pmeeting.minutes.MinutesMergePolicy.merge(
                existingStatus = existing?.status,
                existingText = existing?.text,
                requestedStatus = status,
                requestedText = text,
                requestedError = errorMessage
            )
            val entity = if (existing != null) {
                existing.copy(
                    status = MinutesEntity.statusString(merged.status),
                    text = merged.text,
                    errorMessage = merged.errorMessage,
                    updatedAt = now
                )
            } else {
                MinutesEntity(
                    recordingId = recordingId,
                    status = MinutesEntity.statusString(merged.status),
                    text = if (merged.status == MinutesStatus.COMPLETED) merged.text else null,
                    errorMessage = merged.errorMessage,
                    createdAt = now,
                    updatedAt = now
                )
            }
            minutesDao.insert(entity)
        }
    }

    /** Gets the minutes for a recording, if any. */
    suspend fun getMinutes(recordingId: Long): Minutes? =
        withContext(Dispatchers.IO) {
            minutesDao.getByRecordingId(recordingId)?.toModel()
        }

    /** Deletes the minutes for a recording. */
    suspend fun deleteMinutes(recordingId: Long) = withContext(Dispatchers.IO) {
        minutesDao.deleteByRecordingId(recordingId)
    }

    // ------------------------------------------------------------------ //
    // Action Items (Phase 2)

    /** Upserts an action item for a recording. */
    suspend fun upsertActionItem(
        recordingId: Long,
        text: String,
        status: ActionItemStatus = ActionItemStatus.OPEN
    ): Long = withContext(Dispatchers.IO) {
        actionItemDao.upsert(recordingId, text, status)
    }

    /** Gets all action items for a recording. */
    suspend fun getActionItems(recordingId: Long): List<ActionItem> =
        withContext(Dispatchers.IO) {
            actionItemDao.getAllByRecordingId(recordingId).map { it.toModel() }
        }

    /** Toggles an action item's completion status. */
    suspend fun toggleActionItem(id: Long) = withContext(Dispatchers.IO) {
        val existing = actionItemDao.getById(id) ?: return@withContext
        val newStatus = if (existing.status == ActionItemEntity.statusString(ActionItemStatus.OPEN)) {
            ActionItemStatus.COMPLETED
        } else {
            ActionItemStatus.OPEN
        }
        val now = System.currentTimeMillis()
        actionItemDao.update(
            existing.copy(
                status = ActionItemEntity.statusString(newStatus),
                completedAt = if (newStatus == ActionItemStatus.COMPLETED) now else null
            )
        )
    }

    /** Deletes an action item. */
    suspend fun deleteActionItem(id: Long) = withContext(Dispatchers.IO) {
        actionItemDao.deleteById(id)
    }

    fun mediaDir(type: MediaType): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "recordings/${if (type == MediaType.AUDIO) "audio" else "video"}")
            .apply { mkdirs() }
    }

    // ------------------------------------------------------------------ //
    // Metadata helpers (all performed off the main thread, exceptions
    // contained so that damaged/inaccessible files never crash the UI).

    data class PickedFileMeta(
        val displayName: String?,
        val sizeBytes: Long?
    )

    suspend fun queryPickedFileMeta(uri: Uri): PickedFileMeta? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    PickedFileMeta(
                        displayName = nameIndex.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { cursor.getLong(it) }
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    suspend fun queryMimeType(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }

    /** Duration in milliseconds of a picked content URI, or null if unknown. */
    suspend fun probeDuration(uri: Uri): Long? = withContext(Dispatchers.IO) {
        probeDurationFrom { retriever -> retriever.setDataSource(context, uri) }
    }

    /** Duration in milliseconds of a local media file, or null if unknown. */
    suspend fun probeDuration(file: File): Long? = withContext(Dispatchers.IO) {
        probeDurationFrom { retriever -> retriever.setDataSource(file.absolutePath) }
    }

    private fun probeDurationFrom(setup: (MediaMetadataRetriever) -> Unit): Long? = runCatching {
        val retriever2 = MediaMetadataRetriever()
        try {
            setup(retriever2)
            retriever2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } finally {
            runCatching { retriever2.release() }
        }
    }.getOrNull()

    /**
     * Persists an imported recording. When [copyIntoAppStorage] is false only a
     * reference (content URI) plus metadata is stored and the original file is
     * left untouched. When true, the bytes are copied into the app's private
     * directory; the original still remains wherever the user picked it from.
     */
    suspend fun importRecording(
        uri: Uri,
        title: String,
        copyIntoAppStorage: Boolean,
        originalDisplayName: String?,
        originalSizeBytes: Long?,
        originalMimeType: String?,
        originalDurationMs: Long?
    ): Recording = withContext(Dispatchers.IO) {
        if (copyIntoAppStorage) {
            // If the copy or a later DB insert fails, the partial/temporary file
            // must be removed so the app does not keep an orphaned copy. The
            // original picked file is never touched.
            var copied: File? = null
            try {
                copied = copyIntoAppStorage(uri, originalMimeType)
                val duration = probeDuration(copied)
                val entity = com.prakash.pmeeting.data.local.RecordingEntity(
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    durationMs = originalDurationMs ?: duration ?: 0L,
                    mediaType = detectMediaType(copied, originalMimeType),
                    source = RecordingSource.IMPORTED,
                    storageKind = StorageKind.INTERNAL_FILE,
                    fileUri = copied.absolutePath,
                    mimeType = originalMimeType ?: guessMimeType(copied.name),
                    sizeBytes = copied.length()
                )
                val id = dao.insert(entity)
                entity.copy(id = id).toModel()
            } catch (t: Throwable) {
                copied?.let { runCatching { it.delete() } }
                throw t
            }
        } else {
            val duration = originalDurationMs ?: probeDuration(uri)
            val entity = com.prakash.pmeeting.data.local.RecordingEntity(
                title = title,
                createdAt = System.currentTimeMillis(),
                durationMs = duration ?: 0L,
                mediaType = if (originalMimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.AUDIO,
                source = RecordingSource.IMPORTED,
                storageKind = StorageKind.EXTERNAL_URI,
                fileUri = uri.toString(),
                mimeType = originalMimeType ?: "",
                sizeBytes = originalSizeBytes ?: 0L
            )
            val id = dao.insert(entity)
            entity.copy(id = id).toModel()
        }
    }

    /** Copies a picked URI into app storage; the original is preserved. */
    private fun copyIntoAppStorage(uri: Uri, knownMime: String?): File {
        val mime = knownMime ?: context.contentResolver.getType(uri)
        val mediaType = if (mime?.startsWith("video/") == true) MediaType.VIDEO else MediaType.AUDIO
        val directory = mediaDir(mediaType)
        val target = uniqueFileIn(directory, suggestedName(null, mime))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("The selected file could not be opened.")
        return target
    }

    private fun suggestedName(displayName: String?, mime: String?): String {
        val base = displayName?.takeIf { it.isNotBlank() } ?: "PM_IMPORT_${Formats.fileNameTimestamp()}"
        return if (base.contains('.')) base else {
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) "$base.$ext" else base
        }
    }

    private fun uniqueFileIn(directory: File, suggested: String): File {
        var candidate = File(directory, suggested)
        if (!candidate.exists()) return candidate
        val dot = suggested.lastIndexOf('.')
        val stem = if (dot > 0) suggested.substring(0, dot) else suggested
        val ext = if (dot > 0) suggested.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($counter)$ext")
            counter++
        }
        return candidate
    }

    private fun detectMediaType(file: File, knownMime: String?): MediaType {
        if (knownMime?.startsWith("video/") == true) return MediaType.VIDEO
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                if (hasVideo) MediaType.VIDEO else MediaType.AUDIO
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(MediaType.AUDIO)
    }

    private fun guessMimeType(fileName: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: ""
}
