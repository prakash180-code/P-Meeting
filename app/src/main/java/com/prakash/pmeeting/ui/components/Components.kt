package com.prakash.pmeeting.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prakash.pmeeting.data.model.MediaType
import com.prakash.pmeeting.data.model.RecordingSource
import com.prakash.pmeeting.data.model.StorageKind
import com.prakash.pmeeting.util.Formats
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

/**
 * Consent reminder shown before any recording starts. Recording people
 * without consent is illegal in many places; the app never claims otherwise.
 */
@Composable
fun ConsentDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording reminder") },
        text = {
            Text(
                "Always let everyone know that the meeting is being recorded " +
                    "and make sure they agree. Recording is not permitted in every " +
                    "country or situation — you are responsible for obtaining any " +
                    "consent required. Recordings stay on this device only."
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Title entry dialog shared by: save-after-record (audio/video), rename and
 * import. [extraContent] adds controls below the field (for import options).
 */
@Composable
fun TitleEditorDialog(
    dialogTitle: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    var text by rememberSaveable { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                extraContent?.invoke(this)
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Generic destructive-action confirmation. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Home search field with a trailing clear button. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text("Search recordings") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/** Empty state for the library (also used with "no search results" text). */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Common type icon used in list rows. */
@Composable
fun RecordingTypeIcon(mediaType: MediaType, modifier: Modifier = Modifier) {
    val icon = when (mediaType) {
        MediaType.AUDIO -> Icons.Filled.Mic
        MediaType.VIDEO -> Icons.Filled.Videocam
    }
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = mediaType.name.lowercase(),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/** Secondary line of a list row: date + duration + provenance. */
fun recordingSubtitle(createdAt: Long, durationMs: Long, source: RecordingSource, storageKind: StorageKind): String {
    val prefix = Formats.formatDate(createdAt)
    val duration = if (durationMs > 0L) " · ${Formats.formatDuration(durationMs)}" else ""
    val badge = when {
        source == RecordingSource.IMPORTED && storageKind == StorageKind.EXTERNAL_URI -> " · Imported (linked)"
        source == RecordingSource.IMPORTED -> " · Imported (copied)"
        else -> ""
    }
    return "$prefix$duration$badge"
}
