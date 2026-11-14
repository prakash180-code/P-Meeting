package com.prakash.pmeeting.data.local

import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionSegment
import com.prakash.pmeeting.data.model.TranscriptionStatus

internal fun RecordingEntity.toModel(): Recording = Recording(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    mediaType = mediaType,
    source = source,
    storageKind = storageKind,
    fileUri = fileUri,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    archived = archived
)

internal fun Recording.toEntity(): RecordingEntity = RecordingEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    mediaType = mediaType,
    source = source,
    storageKind = storageKind,
    fileUri = fileUri,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    archived = archived
)

internal fun TranscriptionEntity.toModel(): Transcription = Transcription(
    id = id,
    recordingId = recordingId,
    status = TranscriptionEntity.statusFromString(status),
    text = text,
    segments = emptyList(),
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun Transcription.toEntity(): TranscriptionEntity = TranscriptionEntity(
    id = id,
    recordingId = recordingId,
    status = TranscriptionEntity.statusString(status),
    text = text,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun MinutesEntity.toModel(): Minutes = Minutes(
    id = id,
    recordingId = recordingId,
    status = MinutesEntity.statusFromString(status),
    text = text,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun Minutes.toEntity(): MinutesEntity = MinutesEntity(
    id = id,
    recordingId = recordingId,
    status = MinutesEntity.statusString(status),
    text = text,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun ActionItemEntity.toModel(): ActionItem = ActionItem(
    id = id,
    recordingId = recordingId,
    text = text,
    status = ActionItemEntity.statusFromString(status),
    createdAt = createdAt,
    completedAt = completedAt
)

internal fun ActionItem.toEntity(): ActionItemEntity = ActionItemEntity(
    id = id,
    recordingId = recordingId,
    text = text,
    status = ActionItemEntity.statusString(status),
    createdAt = createdAt,
    completedAt = completedAt
)

internal fun TranscriptionSegmentEntity.toModel(): TranscriptionSegment = TranscriptionSegment(
    startMs = startMs,
    endMs = endMs,
    text = text
)

internal fun TranscriptionSegment.toEntity(transcriptionId: Long): TranscriptionSegmentEntity =
    TranscriptionSegmentEntity(
        transcriptionId = transcriptionId,
        startMs = startMs,
        endMs = endMs,
        text = text
    )
