package io.github.aeewws.codexmobile.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickGgufModel: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onAttachGallery: () -> Unit,
    onAttachCamera: () -> Unit,
    onAttachSystemFile: () -> Unit,
    onClearAttachment: (Int) -> Unit,
    onInterrupt: () -> Unit,
    onRetryLocal: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onRequestSaveCode: (suggestedPath: String?, content: String) -> Unit,
) {
    val listState = remember(state.activeThreadId) { LazyListState() }
    val scope = rememberCoroutineScope()
    var autoFollow by rememberSaveable(state.activeThreadId) { mutableStateOf(true) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    val isNearBottom by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isNearBottom) {
        if (listState.isScrollInProgress && !isNearBottom) {
            autoFollow = false
        } else if (isNearBottom) {
            autoFollow = true
        }
    }

    LaunchedEffect(state.activeThreadId) {
        autoFollow = true
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.id, state.messages.size, autoFollow) {
        if (autoFollow && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatHeader(
            state = state,
            onInterrupt = onInterrupt,
            onPickGgufModel = onPickGgufModel,
            onPickOutputFolder = onPickOutputFolder,
        )

        if (state.connection.code != "connected" || state.errorMessage.isNotBlank() || state.statusMessage.isNotBlank()) {
            ChatStatusStrip(
                connection = state.connection,
                errorMessage = state.errorMessage,
                statusMessage = state.statusMessage,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                if (state.messages.isEmpty()) {
                    ChatEmptyState(
                        state = state,
                        onPickGgufModel = onPickGgufModel,
                        onPickOutputFolder = onPickOutputFolder,
                        onPrefillPrompt = onDraftChange,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = state.messages,
                            key = { "${state.activeThreadId}-${it.id}" },
                        ) { item ->
                            ChatBubble(
                                item = item,
                                onRetryLocal = onRetryLocal,
                                onRequestSaveCode = onRequestSaveCode,
                            )
                        }
                    }
                }
            }

            if (!autoFollow && state.messages.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex)
                            autoFollow = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .testTag("scroll_to_bottom_button"),
                ) {
                    Text("Jump to Latest")
                }
            }
        }

        state.activeApproval?.let { approval ->
            ApprovalCard(
                approval = approval,
                onApprove = { onApproval(true) },
                onReject = { onApproval(false) },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.composer.attachments.isNotEmpty()) {
                    ComposerAttachmentPreview(
                        attachments = state.composer.attachments,
                        onRemove = onClearAttachment,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { showAttachmentPicker = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("attach_menu_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Attach file or photo",
                        )
                    }
                    OutlinedTextField(
                        value = state.composer.text,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_composer_input"),
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = {
                            Text(
                                if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                                    "Ask offline model to code or write files..."
                                } else {
                                    "Message Codex..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        shape = RoundedCornerShape(20.dp),
                    )
                    if (state.composer.canInterrupt) {
                        IconButton(
                            onClick = onInterrupt,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("stop_generation_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop generation",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Button(
                            onClick = onSend,
                            enabled = (state.composer.text.isNotBlank() || state.composer.attachments.isNotEmpty()) && !state.composer.sending,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("send_prompt_button"),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send prompt",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAttachmentPicker) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentPicker = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Add Input or Workspace Resource", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AttachmentSourceCard(
                        title = "Camera",
                        subtitle = "Take photo",
                        icon = Icons.Outlined.CameraAlt,
                        accentColor = Color(0xFF2A9D6F),
                        containerColor = Color(0xFFEAF8F1),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentPicker = false
                            onAttachCamera()
                        },
                    )
                    AttachmentSourceCard(
                        title = "Photos",
                        subtitle = "Pick images",
                        icon = Icons.Outlined.PhotoLibrary,
                        accentColor = Color(0xFF4B7BE5),
                        containerColor = Color(0xFFEEF3FF),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentPicker = false
                            onAttachGallery()
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AttachmentSourceCard(
                        title = "Documents",
                        subtitle = "PDF, DOCX, Code",
                        icon = Icons.Outlined.Description,
                        accentColor = Color(0xFFB36B00),
                        containerColor = Color(0xFFFFF4E6),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentPicker = false
                            onAttachSystemFile()
                        },
                    )
                    AttachmentSourceCard(
                        title = "Output Folder",
                        subtitle = state.workspace.folderName.ifBlank { "Pick folder" },
                        icon = Icons.Outlined.FolderOpen,
                        accentColor = Color(0xFF8D5CF6),
                        containerColor = Color(0xFFF3EEFF),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentPicker = false
                            onPickOutputFolder()
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ChatHeader(
    state: CodexMobileUiState,
    onInterrupt: () -> Unit,
    onPickGgufModel: () -> Unit,
    onPickOutputFolder: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = state.activeThreadTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.connection.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.composer.canInterrupt) {
                    TextButton(onClick = onInterrupt) {
                        Text("Stop")
                    }
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = when (state.connection.tone) {
                                BannerTone.OK -> MaterialTheme.colorScheme.primary
                                BannerTone.INFO -> MaterialTheme.colorScheme.secondary
                                BannerTone.WARN -> MaterialTheme.colorScheme.tertiary
                                BannerTone.DANGER -> MaterialTheme.colorScheme.error
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }

        if (state.engineMode == EngineMode.OFFLINE_GGUF) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("header_pick_model_pill")
                        .clickable(onClick = onPickGgufModel),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Memory,
                            contentDescription = "Select GGUF model",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = state.gguf.modelName.ifBlank { "Pick .gguf model" },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("header_pick_folder_pill")
                        .clickable(onClick = onPickOutputFolder),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "Select output folder",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = state.workspace.folderName.ifBlank { "Pick output folder" },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(
    state: CodexMobileUiState,
    onPickGgufModel: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onPrefillPrompt: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                "Offline GGUF Coding Workspace"
            } else {
                "Start a New Chat"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (state.engineMode == EngineMode.OFFLINE_GGUF) {
                "Run .gguf models completely offline and write files directly into your selected folder."
            } else {
                "History stays on the Workspace tab so chat stays focused."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.engineMode == EngineMode.OFFLINE_GGUF) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("empty_pick_model_card")
                        .clickable(onClick = onPickGgufModel),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (state.gguf.isLoaded) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.Memory, contentDescription = "Pick GGUF model")
                        Text(
                            text = if (state.gguf.isLoaded) "Model Loaded" else "1. Pick Model",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = state.gguf.modelName.ifBlank { "Select a .gguf file" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ElevatedCard(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("empty_pick_folder_card")
                        .clickable(onClick = onPickOutputFolder),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (state.workspace.folderUri.isNotBlank()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Pick output folder")
                        Text(
                            text = if (state.workspace.folderUri.isNotBlank()) "Folder Ready" else "2. Pick Folder",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = state.workspace.folderName.ifBlank { "Choose output directory" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.gguf.isLoaded) {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        onPrefillPrompt("Create a clean starter README.md and hello.py in the selected folder.")
                    },
                    modifier = Modifier.testTag("starter_prompt_button"),
                ) {
                    Text("Try: Create starter files in folder")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    item: ChatItemUi,
    onRetryLocal: (String) -> Unit,
    onRequestSaveCode: (suggestedPath: String?, content: String) -> Unit,
) {
    val isUser = item is ChatItemUi.User || item is ChatItemUi.LocalUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.84f else 0.94f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (item) {
                    is ChatItemUi.User -> {
                        Text("You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (item.attachments.isNotEmpty()) {
                            AttachmentList(attachments = item.attachments, compact = false)
                        }
                        SelectionContainer {
                            if (item.text.isNotBlank()) {
                                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    is ChatItemUi.LocalUser -> {
                        val label = when (item.status) {
                            LocalMessageStatus.PENDING -> "You · Sending"
                            LocalMessageStatus.FAILED -> "You · Failed"
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.status == LocalMessageStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (item.attachments.isNotEmpty()) {
                            AttachmentList(attachments = item.attachments, compact = false)
                        }
                        SelectionContainer {
                            if (item.text.isNotBlank()) {
                                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (item.status == LocalMessageStatus.FAILED) {
                            TextButton(onClick = { onRetryLocal(item.id) }) {
                                Text("Retry")
                            }
                        }
                    }

                    is ChatItemUi.Agent -> {
                        Text(
                            assistantBubbleLabel(item.status),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        SelectionContainer {
                            AssistantMessageBody(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium,
                                onRequestSaveCode = onRequestSaveCode,
                            )
                        }
                    }

                    is ChatItemUi.AgentPlaceholder -> {
                        Text("Assistant", style = MaterialTheme.typography.labelMedium)
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ChatItemUi.Reasoning -> {
                        Text("Reasoning · ${item.status}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            AssistantMessageBody(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is ChatItemUi.Command -> {
                        Text("Command · ${item.status}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(item.command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                        if (item.output.isNotBlank()) {
                            SelectionContainer {
                                CodePanel(item.output)
                            }
                        }
                    }

                    is ChatItemUi.FileChange -> {
                        Text(
                            "Folder Write · ${item.status}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        SelectionContainer {
                            Text(item.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        if (item.output.isNotBlank()) {
                            SelectionContainer {
                                CodePanel(item.output)
                            }
                        }
                    }

                    is ChatItemUi.Plan -> {
                        Text("Plan", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentPreview(
    attachments: List<ChatAttachmentUi>,
    onRemove: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(attachments.size) { index ->
                val attachment = attachments[index]
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachmentThumb(attachment, sizeDp = 38)
                        Column(
                            modifier = Modifier.width(120.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                attachment.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                attachment.kind.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRemove(index) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

private fun assistantBubbleLabel(status: String): String {
    val normalized = status.trim().lowercase()
    return when {
        normalized.isBlank() || normalized == "completed" || normalized == "final_answer" || normalized == "agentmessage" -> "Assistant"
        normalized == "inprogress" -> "Assistant · Generating"
        else -> "Assistant · ${status.replace('_', ' ')}"
    }
}
