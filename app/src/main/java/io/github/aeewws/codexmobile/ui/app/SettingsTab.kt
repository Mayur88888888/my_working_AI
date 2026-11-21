package io.github.aeewws.codexmobile.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aeewws.codexmobile.llm.LocalGgufEngine

@Composable
internal fun SettingsTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onSelectEngineMode: (EngineMode) -> Unit,
    onRefresh: () -> Unit,
    onReloadGgufModel: () -> Unit,
    onRestartBackend: () -> Unit,
    onClearOutputFolder: () -> Unit,
    onSelectFontScale: (FontScaleOption) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Inference Engine",
                subtitle = "Offline GGUF runs completely on-device without internet, root, or account login.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineMode.entries.forEach { mode ->
                        val selected = state.engineMode == mode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("engine_mode_${mode.name}")
                                .clickable { onSelectEngineMode(mode) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(mode.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Runtime & Folder Status",
                subtitle = "Current offline model, folder access, and device diagnostics.",
            ) {
                StatusLine("Mode", state.engineMode.label)
                StatusLine("Status", state.connection.title)
                StatusLine(
                    "GGUF Model",
                    state.gguf.modelName.ifBlank { "Not selected" },
                )
                StatusLine(
                    "Model Loaded",
                    when {
                        state.gguf.isLoading -> "Loading"
                        state.gguf.isLoaded -> "Yes (${LocalGgufEngine.formatByteSize(state.gguf.modelSizeBytes)})"
                        else -> "No"
                    },
                )
                StatusLine(
                    "Output Folder",
                    state.workspace.folderName.ifBlank { "Not selected" },
                )
                StatusLine(
                    "Folder Write Access",
                    if (state.workspace.folderUri.isBlank()) "N/A" else if (state.workspace.canWrite) "Writable" else "Read-only",
                )
                StatusLine("Saved Chats", "${state.historyThreads.size}")
                StatusLine("Doze Allowlist", if (state.keepalive.deviceIdleWhitelisted) "Allowed" else "Standard")
                StatusLine("Standby Bucket", state.keepalive.standbyBucket)
                if (state.engineMode == EngineMode.CLI_BACKEND) {
                    StatusLine("CLI Runtime", if (state.status.runtimeReady) "Ready" else "Not packaged")
                    StatusLine("Root Access", if (state.status.rootAvailable) "Granted" else "Not granted")
                    StatusLine("CLI Backend", if (state.status.backendListening) "Listening on 8765" else "Stopped")
                }
            }
        }

        item {
            SectionCard(
                title = "Display",
                subtitle = "Adjust UI typography scale.",
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FontScaleOption.entries, key = { it.name }) { option ->
                        FilterChip(
                            selected = option == state.fontScale,
                            onClick = { onSelectFontScale(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Controls & Recovery",
                subtitle = "Refresh folder permissions, reload your local model, or clear folder selection.",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("settings_refresh_button"),
                    ) {
                        Text("Refresh")
                    }
                    if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                        Button(
                            onClick = onReloadGgufModel,
                            enabled = state.gguf.modelUri.isNotBlank() && !state.gguf.isLoading,
                            modifier = Modifier.testTag("settings_reload_gguf_button"),
                        ) {
                            Text("Reload Model")
                        }
                    } else {
                        Button(
                            onClick = onRestartBackend,
                            enabled = state.status.runtimeReady && state.status.authPresent,
                        ) {
                            Text("Restart CLI")
                        }
                    }
                }
                if (state.workspace.folderUri.isNotBlank()) {
                    OutlinedButton(
                        onClick = onClearOutputFolder,
                        modifier = Modifier.testTag("settings_clear_folder_button"),
                    ) {
                        Text("Clear Selected Folder")
                    }
                }
            }
        }
    }
}
