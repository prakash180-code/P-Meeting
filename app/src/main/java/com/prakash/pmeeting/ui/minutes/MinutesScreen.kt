package com.prakash.pmeeting.ui.minutes

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.MinutesStatus
import com.prakash.pmeeting.ui.components.ConfirmDialog
import com.prakash.pmeeting.ui.components.EmptyState
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutesScreen(
    onBack: () -> Unit,
    recordingId: Long,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MinutesViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MinutesViewModel(app, recordingId) as T
            }
        }
    )
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val minutes = uiState.minutes

    val showEdit = rememberSaveable { mutableStateOf(false) }
    val editText = rememberSaveable { mutableStateOf("") }
    val showDelete = rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MinutesEvent.GenerationFailed ->
                    snackbarHostState.showSnackbar("Could not generate minutes: ${event.message}")
                is MinutesEvent.SaveFailed ->
                    snackbarHostState.showSnackbar("Could not save minutes: ${event.message}")
                is MinutesEvent.DeleteFailed ->
                    snackbarHostState.showSnackbar("Could not delete minutes: ${event.message}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Minutes") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    minutes?.let { mn ->
                        if (mn.status == MinutesStatus.COMPLETED) {
                            IconButton(
                                onClick = {
                                    editText.value = mn.text ?: ""
                                    showEdit.value = true
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit minutes")
                            }
                            IconButton(
                                onClick = { showDelete.value = true }
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete minutes")
                            }
                        }
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        val current = minutes
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    title = "No minutes yet",
                    message = "Generate minutes from a completed transcription.",
                    modifier = Modifier.fillMaxSize()
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Minutes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    uiState.isGenerating -> Text(
                        text = "Generating…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    current.status == MinutesStatus.PENDING -> Text(
                        text = "Pending",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    current.status == MinutesStatus.COMPLETED -> Text(
                        text = "Completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    current.status == MinutesStatus.FAILED -> Text(
                        text = "Failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            current.text?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            current.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            current.createdAt?.let { createdAt ->
                Text(
                    text = "Created ${Formats.formatDate(createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (current.status == MinutesStatus.COMPLETED && current.text.isNullOrBlank()) {
                Text(
                    text = "Minutes text is empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }

    if (showEdit.value) {
        AlertDialog(
            onDismissRequest = { showEdit.value = false },
            title = { Text("Edit minutes") },
            text = {
                OutlinedTextField(
                    value = editText.value,
                    onValueChange = { editText.value = it },
                    singleLine = false,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveMinutes(editText.value)
                        showEdit.value = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEdit.value = false }) { Text("Cancel") } }
        )
    }

    if (showDelete.value) {
        ConfirmDialog(
            title = "Delete minutes?",
            message = "The generated minutes for this recording will be removed.",
            confirmLabel = "Delete",
            onConfirm = {
                showDelete.value = false
                viewModel.deleteMinutes()
            },
            onDismiss = { showDelete.value = false }
        )
    }
}
