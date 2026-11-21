package io.github.aeewws.codexmobile.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aeewws.codexmobile.llm.LocalGgufEngine

private enum class WorkspacePanel {
    HOME,
    FILES,
    MODEL,
    PERMISSION,
    HISTORY,
    ARCHIVED,
}

@Composable
internal fun WorkspaceTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onPickGgufModel: () -> Unit,
    onReloadGgufModel: () -> Unit,
    onUnloadGgufModel: () -> Unit,
    onUpdateGgufContext: (Int) -> Unit,
    onUpdateGgufThreads: (Int) -> Unit,
    onUpdateGgufTemp: (Float) -> Unit,
    onUpdateGgufMaxTokens: (Int) -> Unit,
    onPickOutputFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onOpenNewFileDialog: () -> Unit,
    onOpenExistingFile: (relativePath: String) -> Unit,
    onDeleteWorkspaceFile: (relativePath: String) -> Unit,
    onStartLogin: () -> Unit,
    onStartDeviceLogin: () -> Unit,
    onCopyDeviceCode: () -> Unit,
    onCopyVerificationUrl: () -> Unit,
    onOpenVerificationPage: () -> Unit,
    onLogout: () -> Unit,
    onCreateNewConversation: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoning: (String) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
    onSelectPermission: (PermissionMode) -> Unit,
    onOpenHistory: (String) -> Unit,
    onArchiveHistory: (String) -> Unit,
    onUnarchiveHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onRenameHistory: (String, String) -> Unit,
    onSwitchToChat: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(WorkspacePanel.HOME) }
    var renameTarget by remember { mutableStateOf<ThreadSummary?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = panel != WorkspacePanel.HOME) {
        panel = WorkspacePanel.HOME
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.errorMessage.isNotBlank() || state.statusMessage.isNotBlank()) {
            ChatStatusStrip(
                connection = state.connection,
                errorMessage = state.errorMessage,
                statusMessage = state.statusMessage,
            )
        }

        when (panel) {
            WorkspacePanel.HOME -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                        item {
                            GgufModelCard(
                                gguf = state.gguf,
                                onPickGgufModel = onPickGgufModel,
                                onReloadGgufModel = onReloadGgufModel,
                                onUnloadGgufModel = onUnloadGgufModel,
                            )
                        }
                    } else {
                        item {
                            CliAccountStatusCard(
                                state = state,
                                onStartLogin = onStartLogin,
                                onStartDeviceLogin = onStartDeviceLogin,
                                onCopyDeviceCode = onCopyDeviceCode,
                                onCopyVerificationUrl = onCopyVerificationUrl,
                                onOpenVerificationPage = onOpenVerificationPage,
                                onLogout = onLogout,
                            )
                        }
                    }

                    item {
                        OutputFolderCard(
                            workspace = state.workspace,
                            onPickOutputFolder = onPickOutputFolder,
                            onRefreshFolder = onRefreshFolder,
                            onNewFile = onOpenNewFileDialog,
                            onBrowseFiles = { panel = WorkspacePanel.FILES },
                        )
                    }

                    item {
                        CommandEntryCard(
                            title = "New Chat",
                            subtitle = "Start a fresh conversation without removing saved history.",
                            testTag = "workspace_new_chat_card",
                            onClick = {
                                onCreateNewConversation()
                                panel = WorkspacePanel.HOME
                                onSwitchToChat()
                            },
                        )
                    }

                    item {
                        CommandEntryCard(
                            title = "Chat History",
                            subtitle = if (state.historyThreads.isEmpty()) {
                                "No saved conversations yet"
                            } else {
                                "${state.historyThreads.size} conversation(s), sorted by recent activity"
                            },
                            testTag = "workspace_history_card",
                            onClick = { panel = WorkspacePanel.HISTORY },
                        )
                    }

                    item {
                        CommandEntryCard(
                            title = "Archived Chats",
                            subtitle = if (state.archivedThreads.isEmpty()) {
                                "No archived conversations"
                            } else {
                                "${state.archivedThreads.size} archived conversation(s)"
                            },
                            testTag = "workspace_archived_card",
                            onClick = { panel = WorkspacePanel.ARCHIVED },
                        )
                    }

                    item {
                        CommandEntryCard(
                            title = if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                                "Offline GGUF Parameters"
                            } else {
                                "CLI Model Options"
                            },
                            subtitle = if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                                "Context ${state.gguf.contextLength} · ${state.gguf.threads} threads · Temp ${state.gguf.temperature}"
                            } else {
                                state.selectedModel
                            },
                            testTag = "workspace_model_card",
                            onClick = { panel = WorkspacePanel.MODEL },
                        )
                    }

                    item {
                        CommandEntryCard(
                            title = "Folder Write Permissions",
                            subtitle = state.permissionMode.label,
                            testTag = "workspace_permission_card",
                            onClick = { panel = WorkspacePanel.PERMISSION },
                        )
                    }
                }
            }

            WorkspacePanel.FILES -> {
                FolderFilesPanel(
                    workspace = state.workspace,
                    onBack = { panel = WorkspacePanel.HOME },
                    onPickOutputFolder = onPickOutputFolder,
                    onRefreshFolder = onRefreshFolder,
                    onNewFile = onOpenNewFileDialog,
                    onOpenExistingFile = onOpenExistingFile,
                    onDeleteFile = onDeleteWorkspaceFile,
                )
            }

            WorkspacePanel.MODEL -> {
                ModelParametersPanel(
                    state = state,
                    onBack = { panel = WorkspacePanel.HOME },
                    onPickGgufModel = onPickGgufModel,
                    onUpdateGgufContext = onUpdateGgufContext,
                    onUpdateGgufThreads = onUpdateGgufThreads,
                    onUpdateGgufTemp = onUpdateGgufTemp,
                    onUpdateGgufMaxTokens = onUpdateGgufMaxTokens,
                    onSelectCliModel = onSelectModel,
                    onSelectReasoning = onSelectReasoning,
                    onSelectFastMode = onSelectFastMode,
                )
            }

            WorkspacePanel.PERMISSION -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "Folder Write Permissions",
                            subtitle = "Control whether the model writes generated files directly or prompts first.",
                            onBack = { panel = WorkspacePanel.HOME },
                        )
                    }
                    item {
                        SectionCard(
                            title = "Write Policy",
                            subtitle = "Applies whenever the assistant outputs file changes for the selected folder.",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PermissionMode.entries.forEach { mode ->
                                    PermissionModeRow(
                                        mode = mode,
                                        selected = mode == state.permissionMode,
                                        onClick = { onSelectPermission(mode) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            WorkspacePanel.HISTORY -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "Chat History",
                            subtitle = "Tap a conversation to resume, or long-press to rename.",
                            onBack = { panel = WorkspacePanel.HOME },
                        )
                    }
                    if (state.historyThreads.isEmpty()) {
                        item {
                            SectionCard(
                                title = "No Conversations Yet",
                                subtitle = "Send a message in Chat to start your first offline conversation.",
                            ) {
                                Text("Your saved conversations will appear here.")
                            }
                        }
                    } else {
                        items(state.historyThreads, key = { it.id }) { thread ->
                            HistoryRow(
                                thread = thread,
                                selected = thread.id == state.activeThreadId,
                                onOpen = {
                                    onOpenHistory(thread.id)
                                    panel = WorkspacePanel.HOME
                                    onSwitchToChat()
                                },
                                actionLabel = "Archive",
                                onAction = { onArchiveHistory(thread.id) },
                                onRename = {
                                    renameTarget = thread
                                    renameDraft = thread.title
                                },
                            )
                        }
                    }
                }
            }

            WorkspacePanel.ARCHIVED -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "Archived Chats",
                            subtitle = "Restore archived conversations back to your main history or delete them permanently.",
                            onBack = { panel = WorkspacePanel.HOME },
                        )
                    }
                    if (state.archivedThreads.isEmpty()) {
                        item {
                            SectionCard(
                                title = "No Archived Chats",
                                subtitle = "Archive any conversation from Chat History to keep your active list tidy.",
                            ) {
                                Text("Nothing archived right now.")
                            }
                        }
                    } else {
                        items(state.archivedThreads, key = { it.id }) { thread ->
                            HistoryRow(
                                thread = thread,
                                selected = false,
                                onOpen = {
                                    onOpenHistory(thread.id)
                                    panel = WorkspacePanel.HOME
                                    onSwitchToChat()
                                },
                                actionLabel = "Restore",
                                onAction = { onUnarchiveHistory(thread.id) },
                                secondaryActionLabel = "Delete",
                                onSecondaryAction = { onDeleteHistory(thread.id) },
                                onRename = {
                                    renameTarget = thread
                                    renameDraft = thread.title
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                renameTarget = null
                renameDraft = ""
            },
            title = { Text("Rename Conversation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Conversation Title") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameHistory(target.id, renameDraft)
                        renameTarget = null
                        renameDraft = ""
                    },
                    enabled = renameDraft.trim().isNotEmpty(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        renameDraft = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun GgufModelCard(
    gguf: GgufModelState,
    onPickGgufModel: () -> Unit,
    onReloadGgufModel: () -> Unit,
    onUnloadGgufModel: () -> Unit,
) {
    val statusText = when {
        gguf.isLoading -> "Loading model into memory..."
        gguf.isLoaded -> "Loaded · ${LocalGgufEngine.formatByteSize(gguf.modelSizeBytes)}"
        gguf.modelUri.isNotBlank() -> "Saved model not loaded (${gguf.modelName})"
        else -> "No GGUF model selected. Pick any local .gguf file on your device."
    }

    SectionCard(
        title = if (gguf.modelName.isNotBlank()) "GGUF · ${gguf.modelName}" else "Offline GGUF Model",
        subtitle = statusText,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPickGgufModel,
                enabled = !gguf.isLoading,
                modifier = Modifier.testTag("pick_gguf_button"),
            ) {
                Text("Select .gguf")
            }
            if (gguf.modelUri.isNotBlank() && !gguf.isLoaded) {
                OutlinedButton(
                    onClick = onReloadGgufModel,
                    enabled = !gguf.isLoading,
                    modifier = Modifier.testTag("reload_gguf_button"),
                ) {
                    Text("Load")
                }
            }
            if (gguf.isLoaded) {
                OutlinedButton(
                    onClick = onUnloadGgufModel,
                    modifier = Modifier.testTag("unload_gguf_button"),
                ) {
                    Text("Unload")
                }
            }
        }
    }
}

@Composable
private fun OutputFolderCard(
    workspace: WorkspaceFolderUiState,
    onPickOutputFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onNewFile: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val subtitle = when {
        workspace.folderUri.isBlank() -> "No output folder selected yet. Pick a folder to read and write files directly."
        !workspace.canWrite -> "${workspace.folderName} (read-only permission)"
        else -> "${workspace.folderName} · ${workspace.files.size} item(s) · Direct write enabled"
    }

    SectionCard(
        title = if (workspace.folderName.isNotBlank()) "Folder · ${workspace.folderName}" else "Selected Output Folder",
        subtitle = subtitle,
    ) {
        if (workspace.lastWriteSummary.isNotBlank()) {
            Text(
                text = "Last write: ${workspace.lastWriteSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPickOutputFolder,
                modifier = Modifier.testTag("pick_folder_button"),
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Pick folder", modifier = Modifier.size(18.dp))
                Text("  Pick Folder")
            }
            if (workspace.folderUri.isNotBlank()) {
                OutlinedButton(
                    onClick = onNewFile,
                    modifier = Modifier.testTag("new_file_button"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "New file", modifier = Modifier.size(18.dp))
                    Text("  Write File")
                }
            }
        }
        if (workspace.folderUri.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBrowseFiles,
                    modifier = Modifier.testTag("browse_folder_files_button"),
                ) {
                    Text("Browse Files (${workspace.files.size})")
                }
                TextButton(
                    onClick = onRefreshFolder,
                    enabled = !workspace.isRefreshing,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    Text(" Refresh")
                }
            }
        }
    }
}

@Composable
private fun FolderFilesPanel(
    workspace: WorkspaceFolderUiState,
    onBack: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onNewFile: () -> Unit,
    onOpenExistingFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CommandPanelHeader(
                title = workspace.folderName.ifBlank { "Folder Files" },
                subtitle = "Tap a file to view or edit and save changes directly to the selected folder.",
                onBack = onBack,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewFile, enabled = workspace.folderUri.isNotBlank()) {
                    Text("New / Write File")
                }
                OutlinedButton(onClick = onPickOutputFolder) {
                    Text("Change Folder")
                }
                TextButton(onClick = onRefreshFolder) {
                    Text("Refresh")
                }
            }
        }
        if (workspace.files.isEmpty()) {
            item {
                SectionCard(
                    title = "Empty Folder",
                    subtitle = "No files found in this folder yet.",
                ) {
                    Text("Create a file directly or ask the offline GGUF model to generate files for you.")
                }
            }
        } else {
            items(workspace.files, key = { it.relativePath }) { entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !entry.isDirectory) { onOpenExistingFile(entry.relativePath) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                            contentDescription = entry.relativePath,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.relativePath,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (entry.isDirectory) "Directory" else "${entry.sizeBytes} bytes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!entry.isDirectory && workspace.canWrite) {
                            IconButton(onClick = { onDeleteFile(entry.relativePath) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete ${entry.relativePath}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelParametersPanel(
    state: CodexMobileUiState,
    onBack: () -> Unit,
    onPickGgufModel: () -> Unit,
    onUpdateGgufContext: (Int) -> Unit,
    onUpdateGgufThreads: (Int) -> Unit,
    onUpdateGgufTemp: (Float) -> Unit,
    onUpdateGgufMaxTokens: (Int) -> Unit,
    onSelectCliModel: (String) -> Unit,
    onSelectReasoning: (String) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CommandPanelHeader(
                title = if (state.engineMode == EngineMode.OFFLINE_GGUF) "Offline GGUF Parameters" else "CLI Models",
                subtitle = if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                    "Tune local llama.cpp context length, thread count, temperature, and token limits."
                } else {
                    "Choose the Codex CLI model and reasoning depth."
                },
                onBack = onBack,
            )
        }

        if (state.engineMode == EngineMode.OFFLINE_GGUF) {
            item {
                SectionCard(
                    title = "Active Model File",
                    subtitle = state.gguf.modelName.ifBlank { "None selected" },
                ) {
                    Button(onClick = onPickGgufModel) {
                        Text("Pick .gguf Model File")
                    }
                }
            }
            item {
                SectionCard(
                    title = "Context Length (n_ctx)",
                    subtitle = "Larger contexts fit more folder files and history, but use more RAM.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(2048, 4096, 8192)) { ctx ->
                            FilterChip(
                                selected = state.gguf.contextLength == ctx,
                                onClick = { onUpdateGgufContext(ctx) },
                                label = { Text("$ctx tokens") },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "CPU Threads",
                    subtitle = "4 threads is balanced for most phones; increase carefully to avoid thermal throttling.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(2, 4, 6, 8)) { threads ->
                            FilterChip(
                                selected = state.gguf.threads == threads,
                                onClick = { onUpdateGgufThreads(threads) },
                                label = { Text("$threads threads") },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "Temperature",
                    subtitle = "Lower values give more deterministic code output.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(0.1f to "0.1 Precise", 0.35f to "0.35 Balanced", 0.7f to "0.7 Creative")) { (temp, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(state.gguf.temperature - temp) < 0.05f,
                                onClick = { onUpdateGgufTemp(temp) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "Max Output Tokens",
                    subtitle = "Maximum length of a single generated reply.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(512, 1536, 3072)) { maxTok ->
                            FilterChip(
                                selected = state.gguf.maxTokens == maxTok,
                                onClick = { onUpdateGgufMaxTokens(maxTok) },
                                label = { Text("$maxTok") },
                            )
                        }
                    }
                }
            }
        } else {
            val selectedModel = state.availableModels.firstOrNull { it.id == state.selectedModel }
            val reasoningOptions = selectedModel?.reasoningOptions.orEmpty()
            item {
                SectionCard(
                    title = "CLI Models",
                    subtitle = "Models reported by the local Codex CLI app-server.",
                ) {
                    if (state.availableModels.isEmpty()) {
                        Text("No CLI models loaded yet.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.availableModels, key = { it.id }) { model ->
                                FilterChip(
                                    selected = model.id == state.selectedModel,
                                    onClick = { onSelectCliModel(model.id) },
                                    label = { Text(model.displayName) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "Reasoning Effort",
                    subtitle = "Select reasoning depth supported by the active model.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(reasoningOptions, key = { it.value }) { effort ->
                            FilterChip(
                                selected = effort.value == state.selectedReasoning,
                                onClick = { onSelectReasoning(effort.value) },
                                label = { Text(effort.label) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "Service Tier",
                    subtitle = "Toggle priority Fast service tier when supported.",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = !state.fastModeEnabled,
                                onClick = { onSelectFastMode(false) },
                                label = { Text("Standard") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.fastModeEnabled,
                                onClick = { onSelectFastMode(true) },
                                label = { Text("Fast") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CliAccountStatusCard(
    state: CodexMobileUiState,
    onStartLogin: () -> Unit,
    onStartDeviceLogin: () -> Unit,
    onCopyDeviceCode: () -> Unit,
    onCopyVerificationUrl: () -> Unit,
    onOpenVerificationPage: () -> Unit,
    onLogout: () -> Unit,
) {
    val hasDeviceCode = state.deviceAuthCode.isNotBlank()
    val hasVerificationUrl = state.deviceAuthUrl.isNotBlank()
    val title = when {
        state.status.loginInProgress -> "CLI Account · Signing In"
        state.status.authPresent -> "CLI Account · Signed In"
        else -> "CLI Account · Signed Out"
    }
    SectionCard(
        title = title,
        subtitle = if (!state.status.runtimePackaged) {
            "Embedded CLI runtime is not packaged. Use Offline GGUF mode instead."
        } else {
            state.status.backendStatusDetail.ifBlank { "Sign in to use optional Codex CLI mode." }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStartLogin,
                enabled = !state.status.loginInProgress && state.status.runtimeReady && !state.status.authPresent,
            ) {
                Text("Sign In")
            }
            OutlinedButton(
                onClick = onStartDeviceLogin,
                enabled = !state.status.loginInProgress && state.status.runtimeReady && !state.status.authPresent,
            ) {
                Text("Device Code")
            }
            Button(
                onClick = onLogout,
                enabled = !state.status.loginInProgress && state.status.authPresent,
            ) {
                Text("Sign Out")
            }
        }
        if (hasDeviceCode || hasVerificationUrl || state.loginHint.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.deviceAuthCode.takeIf { it.isNotBlank() }?.let { code ->
                    SelectionContainer {
                        Text("Device code: $code", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    }
                }
                state.deviceAuthUrl.takeIf { it.isNotBlank() }?.let { url ->
                    SelectionContainer {
                        Text("Verification URL: $url", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (hasDeviceCode || hasVerificationUrl) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasDeviceCode) OutlinedButton(onClick = onCopyDeviceCode) { Text("Copy Code") }
                        if (hasVerificationUrl) OutlinedButton(onClick = onCopyVerificationUrl) { Text("Copy URL") }
                        if (hasVerificationUrl) OutlinedButton(onClick = onOpenVerificationPage) { Text("Open Page") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionModeRow(
    mode: PermissionMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(mode.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(mode.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    thread: ThreadSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { onRename?.invoke() },
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${thread.updatedAtLabel} · ${thread.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                secondaryActionLabel?.let { label ->
                    TextButton(
                        onClick = { onSecondaryAction?.invoke() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(label)
                    }
                }
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
