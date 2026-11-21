package io.github.aeewws.codexmobile.ui.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class PendingEditorTarget(
    val initialPath: String,
    val initialContent: String,
)

@Composable
fun CodexMobileApp(
    viewModel: CodexMobileViewModel,
    onStartLogin: () -> Unit,
    onStartDeviceLogin: () -> Unit,
    onCopyDeviceCode: () -> Unit,
    onCopyVerificationUrl: () -> Unit,
    onOpenVerificationPage: () -> Unit,
    onOpenEnglishVerificationPage: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    var editorTarget by remember { mutableStateOf<PendingEditorTarget?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.attachFiles(uris)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.attachFiles(uris)
        }
    }

    val cameraPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.attachCameraBitmap(bitmap)
        }
    }

    val ggufModelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectGgufModel(uri)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectWorkspaceFolder(uri)
        }
    }

    val launchGgufPicker = { ggufModelPicker.launch(arrayOf("*/*")) }
    val launchFolderPicker = { folderPicker.launch(null) }

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale * state.fontScale.scale,
        ),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    BottomDestination.entries.forEach { tab ->
                        val selected = state.activeTab == tab
                        val icon = when (tab) {
                            BottomDestination.CHAT -> if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
                            BottomDestination.COMMANDS -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
                            BottomDestination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { viewModel.switchTab(tab) },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("nav_${tab.name.lowercase()}"),
                        )
                    }
                }
            },
        ) { padding ->
            when (state.activeTab) {
                BottomDestination.CHAT -> ChatTab(
                    state = state,
                    padding = padding,
                    onDraftChange = viewModel::updateDraft,
                    onSend = viewModel::sendPrompt,
                    onPickGgufModel = launchGgufPicker,
                    onPickOutputFolder = launchFolderPicker,
                    onAttachGallery = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onAttachCamera = { cameraPicker.launch(null) },
                    onAttachSystemFile = { filePicker.launch(arrayOf("*/*")) },
                    onClearAttachment = viewModel::clearComposerAttachments,
                    onInterrupt = viewModel::interruptTurn,
                    onRetryLocal = viewModel::retryLocalDraft,
                    onApproval = viewModel::answerApproval,
                    onRequestSaveCode = { suggestedPath, content ->
                        editorTarget = PendingEditorTarget(
                            initialPath = suggestedPath ?: "snippet.txt",
                            initialContent = content,
                        )
                    },
                )

                BottomDestination.COMMANDS -> WorkspaceTab(
                    state = state,
                    padding = padding,
                    onPickGgufModel = launchGgufPicker,
                    onReloadGgufModel = viewModel::reloadGgufModel,
                    onUnloadGgufModel = viewModel::unloadGgufModel,
                    onUpdateGgufContext = viewModel::updateGgufContextLength,
                    onUpdateGgufThreads = viewModel::updateGgufThreads,
                    onUpdateGgufTemp = viewModel::updateGgufTemperature,
                    onUpdateGgufMaxTokens = viewModel::updateGgufMaxTokens,
                    onPickOutputFolder = launchFolderPicker,
                    onRefreshFolder = viewModel::refreshWorkspaceFolder,
                    onOpenNewFileDialog = {
                        editorTarget = PendingEditorTarget(
                            initialPath = "notes.txt",
                            initialContent = "",
                        )
                    },
                    onOpenExistingFile = { relativePath ->
                        viewModel.readWorkspaceFile(relativePath) { result ->
                            result.onSuccess { content ->
                                editorTarget = PendingEditorTarget(
                                    initialPath = relativePath,
                                    initialContent = content,
                                )
                            }
                        }
                    },
                    onDeleteWorkspaceFile = viewModel::deleteWorkspaceFile,
                    onStartLogin = onStartLogin,
                    onStartDeviceLogin = onStartDeviceLogin,
                    onCopyDeviceCode = onCopyDeviceCode,
                    onCopyVerificationUrl = onCopyVerificationUrl,
                    onOpenVerificationPage = onOpenVerificationPage,
                    onLogout = onLogout,
                    onCreateNewConversation = viewModel::startNewConversation,
                    onSelectModel = viewModel::selectModel,
                    onSelectReasoning = viewModel::selectReasoningEffort,
                    onSelectFastMode = viewModel::selectFastMode,
                    onSelectPermission = viewModel::selectPermissionMode,
                    onOpenHistory = viewModel::openHistoryThread,
                    onArchiveHistory = viewModel::archiveHistoryThread,
                    onUnarchiveHistory = viewModel::unarchiveHistoryThread,
                    onDeleteHistory = viewModel::deleteHistoryThread,
                    onRenameHistory = viewModel::renameHistoryThread,
                    onSwitchToChat = { viewModel.switchTab(BottomDestination.CHAT) },
                )

                BottomDestination.SETTINGS -> SettingsTab(
                    state = state,
                    padding = padding,
                    onSelectEngineMode = viewModel::selectEngineMode,
                    onRefresh = viewModel::refreshNow,
                    onReloadGgufModel = viewModel::reloadGgufModel,
                    onRestartBackend = viewModel::restartBackend,
                    onClearOutputFolder = viewModel::clearWorkspaceFolder,
                    onSelectFontScale = viewModel::selectFontScale,
                )
            }
        }
    }

    editorTarget?.let { target ->
        WriteFileDialog(
            initialPath = target.initialPath,
            initialContent = target.initialContent,
            folderName = state.workspace.folderName,
            hasFolder = state.workspace.folderUri.isNotBlank() && state.workspace.canWrite,
            onPickFolderFirst = launchFolderPicker,
            onConfirmWrite = { relativePath, content ->
                viewModel.writeFileDirectlyToWorkspace(
                    relativePath = relativePath,
                    content = content,
                    recordInChat = state.activeTab == BottomDestination.CHAT,
                )
                editorTarget = null
            },
            onDismiss = { editorTarget = null },
        )
    }
}
