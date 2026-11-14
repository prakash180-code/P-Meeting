package com.prakash.pmeeting

import com.prakash.pmeeting.data.local.ActionItemEntity
import com.prakash.pmeeting.data.local.MinutesEntity
import com.prakash.pmeeting.data.local.TranscriptionEntity
import com.prakash.pmeeting.data.local.toEntity
import com.prakash.pmeeting.data.local.toModel
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.data.model.TranscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionTest {

    @Test
    fun transcriptionStatus_values() {
        assertEquals(5, TranscriptionStatus.values().size)
        assertEquals("PENDING", TranscriptionStatus.PENDING.name)
        assertEquals("PROCESSING", TranscriptionStatus.PROCESSING.name)
        assertEquals("WAITING_FOR_MODEL", TranscriptionStatus.WAITING_FOR_MODEL.name)
        assertEquals("COMPLETED", TranscriptionStatus.COMPLETED.name)
        assertEquals("FAILED", TranscriptionStatus.FAILED.name)
    }

    @Test
    fun transcriptionEntity_unknownStatusIsTreatedAsFailed() {
        // Older/corrupt rows must never crash the library.
        assertEquals(
            TranscriptionStatus.FAILED,
            TranscriptionEntity.statusFromString("SOME_FUTURE_STATUS")
        )
    }

    @Test
    fun transcriptionEntity_statusRoundTrip() {
        for (status in TranscriptionStatus.values()) {
            val str = TranscriptionEntity.statusString(status)
            assertEquals(status.name, str)
            assertEquals(status, TranscriptionEntity.statusFromString(str))
        }
    }

    @Test
    fun transcription_defaults() {
        val t = Transcription(recordingId = 42L, status = TranscriptionStatus.PENDING)
        assertEquals(42L, t.recordingId)
        assertEquals(TranscriptionStatus.PENDING, t.status)
        assertNull(t.text)
        assertNull(t.errorMessage)
        assertNotNull(t.createdAt)
        assertNotNull(t.updatedAt)
        assertEquals(0L, t.id)
    }

    @Test
    fun transcription_entityModelRoundTrip() {
        val model = Transcription(
            id = 7L,
            recordingId = 3L,
            status = TranscriptionStatus.COMPLETED,
            text = "Hello world",
            errorMessage = null,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val entity = model.toEntity()
        assertEquals(7L, entity.id)
        assertEquals(3L, entity.recordingId)
        assertEquals("COMPLETED", entity.status)
        assertEquals("Hello world", entity.text)
        assertNull(entity.errorMessage)
        assertEquals(1000L, entity.createdAt)
        assertEquals(2000L, entity.updatedAt)

        val back = entity.toModel()
        assertEquals(model, back)
    }

    @Test
    fun transcription_entityModelRoundTrip_failed() {
        val model = Transcription(
            id = 1L,
            recordingId = 2L,
            status = TranscriptionStatus.FAILED,
            text = null,
            errorMessage = "boom",
            createdAt = 5L,
            updatedAt = 6L
        )
        val entity = model.toEntity()
        assertEquals("FAILED", entity.status)
        assertNull(entity.text)
        assertEquals("boom", entity.errorMessage)

        assertEquals(model, entity.toModel())
    }

    @Test
    fun minutes_status_values() {
        assertEquals(3, MinutesStatus.values().size)
        assertEquals("PENDING", MinutesStatus.PENDING.name)
        assertEquals("COMPLETED", MinutesStatus.COMPLETED.name)
        assertEquals("FAILED", MinutesStatus.FAILED.name)
    }

    @Test
    fun minutes_status_roundTrip() {
        for (status in MinutesStatus.values()) {
            val str = MinutesEntity.statusString(status)
            assertEquals(status.name, str)
            assertEquals(status, MinutesEntity.statusFromString(str))
        }
    }

    @Test
    fun minutes_entityModelRoundTrip() {
        val model = Minutes(
            id = 7L,
            recordingId = 3L,
            status = MinutesStatus.COMPLETED,
            text = "Decided to ship v1 by Friday.",
            errorMessage = null,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val entity = model.toEntity()
        assertEquals(7L, entity.id)
        assertEquals(3L, entity.recordingId)
        assertEquals("COMPLETED", entity.status)
        assertEquals("Decided to ship v1 by Friday.", entity.text)
        assertNull(entity.errorMessage)
        assertEquals(1000L, entity.createdAt)
        assertEquals(2000L, entity.updatedAt)
        assertEquals(model, entity.toModel())
    }

    @Test
    fun actionItem_status_values() {
        assertEquals(2, ActionItemStatus.values().size)
        assertEquals("OPEN", ActionItemStatus.OPEN.name)
        assertEquals("COMPLETED", ActionItemStatus.COMPLETED.name)
    }

    @Test
    fun actionItem_status_roundTrip() {
        for (status in ActionItemStatus.values()) {
            val str = ActionItemEntity.statusString(status)
            assertEquals(status.name, str)
            assertEquals(status, ActionItemEntity.statusFromString(str))
        }
    }

    @Test
    fun actionItem_entityModelRoundTrip_completed() {
        val model = ActionItem(
            id = 9L,
            recordingId = 3L,
            text = "Send follow-up email",
            status = ActionItemStatus.COMPLETED,
            createdAt = 1000L,
            completedAt = 2000L
        )
        val entity = model.toEntity()
        assertEquals(9L, entity.id)
        assertEquals(3L, entity.recordingId)
        assertEquals("Send follow-up email", entity.text)
        assertEquals("COMPLETED", entity.status)
        assertEquals(1000L, entity.createdAt)
        assertEquals(2000L, entity.completedAt)
        assertEquals(model, entity.toModel())
    }

    @Test
    fun actionItem_entityModelRoundTrip_open() {
        val model = ActionItem(
            id = 10L,
            recordingId = 3L,
            text = "Draft agenda",
            status = ActionItemStatus.OPEN,
            createdAt = 1000L,
            completedAt = null
        )
        val entity = model.toEntity()
        assertEquals("OPEN", entity.status)
        assertNull(entity.completedAt)
        assertEquals(model, entity.toModel())
    }
}
