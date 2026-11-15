package com.prakash.pmeeting.minutes

import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.data.model.TranscriptionStatus
import com.prakash.pmeeting.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MinutesServiceImpl(
    private val repository: RecordingRepository
) : MinutesService {

    /**
     * Serializes generation across all callers (the detail and minutes screens
     * can both trigger it). Without this, two concurrent runs could both read
     * an empty action-item set and insert duplicate items.
     */
    private val generateMutex = Mutex()

    private val actionKeywords = listOf(
        "will", "should", "need to", "action item", "follow up", "todo", "task",
        "assign", "responsible", "deadline", "by ", "next week", "next month",
        "please", "could you", "can you", "make sure", "don't forget"
    )

    private val decisionKeywords = listOf(
        "decided", "agreed", "concluded", "resolved", "determined", "settled",
        "we will", "let's", "let us", "the decision", "outcome", "agreement"
    )

    override suspend fun generateMinutes(recordingId: Long): Long = generateMutex.withLock {
        val transcription = repository.getTranscription(recordingId)
            ?: return@withLock repository.upsertMinutes(
                recordingId = recordingId,
                status = MinutesStatus.FAILED,
                errorMessage = "No transcription available for this recording."
            )

        if (transcription.status != TranscriptionStatus.COMPLETED || transcription.text.isNullOrBlank()) {
            return@withLock repository.upsertMinutes(
                recordingId = recordingId,
                status = MinutesStatus.FAILED,
                errorMessage = "Transcription is not complete."
            )
        }

        val text = transcription.text!!
        val sentences = splitIntoSentences(text)
        val actionItems = sentences.filter { it.isActionItem() }
        val decisions = sentences.filter { it.isDecision() }

        val minutesText = buildMinutes(text, sentences.size, actionItems, decisions)

        val minutesId = repository.upsertMinutes(
            recordingId = recordingId,
            status = MinutesStatus.COMPLETED,
            text = minutesText
        )

        // Persist extracted action items, skipping duplicates so that
        // regenerating minutes does not pile up repeated entries.
        val existingTexts = repository.getActionItems(recordingId).map { it.text }.toSet()
        for (itemText in actionItems.distinct()) {
            if (itemText !in existingTexts) {
                repository.upsertActionItem(recordingId, itemText, ActionItemStatus.OPEN)
            }
        }

        return@withLock minutesId
    }

    override fun observeMinutes(recordingId: Long): Flow<Minutes?> =
        repository.observeMinutes(recordingId)

    override suspend fun cancel(recordingId: Long) = Unit

    // ------------------------------------------------------------------ helpers

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("[.!?]+\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun String.isActionItem(): Boolean {
        val lower = lowercase()
        return actionKeywords.any { lower.contains(it) } && length > 5
    }

    private fun String.isDecision(): Boolean {
        val lower = lowercase()
        return decisionKeywords.any { lower.contains(it) } && length > 5
    }

    private fun buildMinutes(
        fullText: String,
        sentenceCount: Int,
        actionItems: List<String>,
        decisions: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("## Meeting Minutes\n\n")
        sb.append("### Summary\n")
        sb.append("The meeting contained $sentenceCount sentences of transcribed discussion.\n\n")

        if (decisions.isNotEmpty()) {
            sb.append("### Decisions\n")
            decisions.forEach { sb.append("- ").append(it).append("\n") }
            sb.append("\n")
        }

        if (actionItems.isNotEmpty()) {
            sb.append("### Action Items\n")
            actionItems.forEach { sb.append("- [ ] ").append(it).append("\n") }
            sb.append("\n")
        }

        sb.append("### Full Transcript\n")
        sb.append(fullText)

        return sb.toString()
    }
}
