package com.prakash.pmeeting.ui.actionitem

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.data.model.ActionItemStatus
import com.prakash.pmeeting.ui.components.ConfirmDialog
import com.prakash.pmeeting.ui.components.EmptyState
import com.prakash.pmeeting.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionItemScreen(
    onBack: () -> Unit,
    recordingId: Long,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: ActionItemViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ActionItemViewModel(app, recordingId) as T
            }
        }
    )
    val items by viewModel.actionItems.collectAsStateWithLifecycle()

    val showAdd = rememberSaveable { mutableStateOf(false) }
    val newItemText = rememberSaveable { mutableStateOf("") }
    val showDelete = rememberSaveable { mutableStateOf(false) }
    val itemToDelete = remember { mutableStateOf<Long?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ActionItemEvent.AddFailed -> "Could not add action item: ${event.message}"
                is ActionItemEvent.ToggleFailed -> "Could not update action item: ${event.message}"
                is ActionItemEvent.DeleteFailed -> "Could not delete action item: ${event.message}"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Action Items") },
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
                        onClick = { showAdd.value = true }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add action item")
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    title = "No action items",
                    message = "Add action items to track follow-ups from this meeting.",
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
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.status == ActionItemStatus.COMPLETED,
                        onCheckedChange = { viewModel.toggleActionItem(item.id) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.status == ActionItemStatus.COMPLETED)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Text(
                            text = Formats.formatDate(item.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            itemToDelete.value = item.id
                            showDelete.value = true
                        }
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete action item"
                        )
                    }
                }
            }
        }
    }

    if (showAdd.value) {
        AlertDialog(
            onDismissRequest = { showAdd.value = false },
            title = { Text("Add Action Item") },
            text = {
                OutlinedTextField(
                    value = newItemText.value,
                    onValueChange = { newItemText.value = it },
                    singleLine = true,
                    label = { Text("Action item") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newItemText.value.isNotBlank(),
                    onClick = {
                        viewModel.addActionItem(newItemText.value)
                        showAdd.value = false
                        newItemText.value = ""
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd.value = false }) { Text("Cancel") } }
        )
    }

    if (showDelete.value && itemToDelete.value != null) {
        ConfirmDialog(
            title = "Delete action item?",
            message = "This action item will be removed from the meeting.",
            confirmLabel = "Delete",
            onConfirm = {
                showDelete.value = false
                itemToDelete.value?.let { viewModel.deleteActionItem(it) }
                itemToDelete.value = null
            },
            onDismiss = {
                showDelete.value = false
                itemToDelete.value = null
            }
        )
    }
}
