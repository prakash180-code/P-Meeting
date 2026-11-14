package com.prakash.pmeeting.ui.email

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.data.model.ActionItem
import com.prakash.pmeeting.data.model.Minutes
import com.prakash.pmeeting.data.model.Transcription
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailDraftScreen(
    onBack: () -> Unit,
    recordingId: Long,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: EmailDraftViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return EmailDraftViewModel(app, recordingId) as T
            }
        }
    )
    val context: Context = LocalContext.current
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val minutes by viewModel.minutes.collectAsStateWithLifecycle()
    val actionItems by viewModel.actionItems.collectAsStateWithLifecycle()

    val recordingTitle = recording?.title ?: "Meeting"
    val recordingDate = recording?.createdAt?.let { Formats.formatDate(it) } ?: ""

    val subject = remember { mutableStateOf("") }
    val body = remember { mutableStateOf("") }
    val includeMinutes = remember { mutableStateOf(false) }
    val includeActionItems = remember { mutableStateOf(false) }
    val userEditedBody = remember { mutableStateOf(false) }
    val initialized = remember { mutableStateOf(false) }

    // Populate the draft only after the transcript (and related data) have
    // loaded, so we never ship a stale body captured while the flows were
    // still empty.
    LaunchedEffect(transcription, minutes, actionItems, recording) {
        if (transcription != null && !initialized.value) {
            includeMinutes.value = minutes != null
            includeActionItems.value = actionItems.isNotEmpty()
            subject.value = "Meeting Transcript - $recordingTitle"
            body.value = buildEmailBody(
                title = recordingTitle,
                date = recordingDate,
                transcription = transcription,
                minutes = minutes,
                actionItems = actionItems,
                includeMinutes = includeMinutes.value,
                includeActionItems = includeActionItems.value
            )
            initialized.value = true
        }
    }

    // Rebuild the body when the inclusion toggles change, unless the user has
    // manually edited the body (in which case their edits take precedence).
    LaunchedEffect(includeMinutes, includeActionItems) {
        if (initialized.value && !userEditedBody.value && transcription != null) {
            body.value = buildEmailBody(
                title = recordingTitle,
                date = recordingDate,
                transcription = transcription,
                minutes = minutes,
                actionItems = actionItems,
                includeMinutes = includeMinutes.value,
                includeActionItems = includeActionItems.value
            )
        }
    }

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is EmailDraftEvent.ShareDraft -> {
                    context.startActivity(event.intent)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email Draft") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.shareDraft(subject.value, body.value)
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Share email draft")
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = recordingTitle,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = recordingDate,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = subject.value,
                onValueChange = { subject.value = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = includeMinutes.value,
                    onCheckedChange = { includeMinutes.value = it },
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Include meeting minutes",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { includeMinutes.value = !includeMinutes.value }
                    )
                    if (minutes == null) {
                        Text(
                            text = "No minutes available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = includeActionItems.value,
                    onCheckedChange = { includeActionItems.value = it },
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Include action items",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { includeActionItems.value = !includeActionItems.value }
                    )
                    if (actionItems.isEmpty()) {
                        Text(
                            text = "No action items available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = body.value,
                onValueChange = { body.value = it; userEditedBody.value = true },
                singleLine = false,
                maxLines = 20,
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

private fun buildEmailBody(
    title: String,
    date: String,
    transcription: Transcription?,
    minutes: Minutes?,
    actionItems: List<ActionItem>,
    includeMinutes: Boolean,
    includeActionItems: Boolean
): String {
    val sb = StringBuilder()
    sb.append("Meeting: $title\n")
    if (date.isNotBlank()) {
        sb.append("Date: $date\n")
    }
    sb.append("\n")

    if (includeMinutes) {
        minutes?.text?.let { minutesText ->
            sb.append("MEETING MINUTES\n")
            sb.append(minutesText)
            if (!minutesText.endsWith("\n")) {
                sb.append("\n")
            }
            sb.append("\n")
        }
    }

    if (includeActionItems) {
        if (actionItems.isNotEmpty()) {
            sb.append("ACTION ITEMS\n")
            actionItems.forEach { item ->
                val marker = if (item.status == ActionItemStatus.COMPLETED) "[x]" else "[ ]"
                sb.append("$marker ${item.text}\n")
            }
            sb.append("\n")
        }
    }

    sb.append("TRANSCRIPT\n")
    val segments = transcription?.segments
    if (segments.isNullOrEmpty()) {
        transcription?.text?.let { transcriptText ->
            sb.append(transcriptText)
            if (!transcriptText.endsWith("\n")) {
                sb.append("\n")
            }
        }
    } else {
        segments.forEach { segment ->
            sb.append("${Formats.formatDuration(segment.startMs)} - ${segment.text}\n")
        }
    }

    return sb.toString()
}
