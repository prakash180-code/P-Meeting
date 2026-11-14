package com.prakash.pmeeting.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prakash.pmeeting.transcription.ModelStatus
import com.prakash.pmeeting.transcription.TranscriptionLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val darkThemeOverride by viewModel.darkTheme.collectAsState()
    val isDarkTheme = darkThemeOverride ?: androidx.compose.foundation.isSystemInDarkTheme()
    val modelState by viewModel.modelState.collectAsState()
    val language by viewModel.transcriptionLanguage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ------------------------------------------------------------------
            // Dark theme
            // ------------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .clickable { viewModel.setDarkTheme(!isDarkTheme) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Dark theme",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Use dark theme for the app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { viewModel.setDarkTheme(it) }
                )
            }

            HorizontalDivider()

            // ------------------------------------------------------------------
            // Transcription language
            // ------------------------------------------------------------------
            val showLanguageMenu = remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Transcription language",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Language Whisper uses to transcribe (${
                            if (language.langCode == null) "auto-detect" else language.langCode
                        }).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box {
                    IconButton(
                        onClick = { showLanguageMenu.value = true }
                    ) {
                        Icon(
                            Icons.Filled.Language,
                            contentDescription = "Transcription language"
                        )
                    }
                    DropdownMenu(
                        expanded = showLanguageMenu.value,
                        onDismissRequest = { showLanguageMenu.value = false }
                    ) {
                        TranscriptionLanguage.values().forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.label) },
                                onClick = {
                                    showLanguageMenu.value = false
                                    viewModel.setTranscriptionLanguage(lang)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ------------------------------------------------------------------
            // About
            // ------------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "About P-Meeting",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Local-first meeting recorder. Recordings and transcripts stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            HorizontalDivider()

            // ------------------------------------------------------------------
            // Whisper model management
            // ------------------------------------------------------------------
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            ) {
                Text(
                    text = "Whisper transcription model",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Model: ${modelState.config.name}",
                    style = MaterialTheme.typography.bodyMedium
                )

                val approxSizeStr = viewModel.formatModelSize(modelState.config.approxSizeBytes)
                Text(
                    text = "Approximate download size: $approxSizeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                when (modelState.status) {
                    ModelStatus.IDLE, ModelStatus.INTERRUPTED -> {
                        if (viewModel.hasModelFile()) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = { viewModel.retryDownload() },
                                    enabled = !viewModel.isDownloading()
                                ) {
                                    Text("Validate model")
                                }
                                TextButton(
                                    onClick = { viewModel.deleteModel() }
                                ) {
                                    Text("Delete model")
                                }
                            }
                        } else {
                            TextButton(
                                onClick = { viewModel.downloadModel() },
                                enabled = !viewModel.isDownloading()
                            ) {
                                Text("Download model ($approxSizeStr)")
                            }
                            Text(
                                text = "The model is not bundled in the APK. Downloading is optional and " +
                                    "required only for offline transcription.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    ModelStatus.DOWNLOADING -> {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LinearProgressIndicator(
                                progress = { modelState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Downloading... ${(modelState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            TextButton(
                                onClick = { viewModel.cancelDownload() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    ModelStatus.COMPLETED -> {
                        Text(
                            text = "Download complete. Validating model with native engine...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    ModelStatus.VALIDATED -> {
                        Text(
                            text = "Model is ready for offline transcription.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        TextButton(
                            onClick = { viewModel.deleteModel() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Delete model")
                        }
                    }

                    ModelStatus.FAILED -> {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Download or validation failed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            modelState.errorMessage?.let { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = { viewModel.retryDownload() },
                                    enabled = !viewModel.isDownloading()
                                ) {
                                    Text("Retry")
                                }
                                TextButton(
                                    onClick = { viewModel.deleteModel() }
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
