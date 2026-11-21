package io.github.aeewws.codexmobile.ui.app

import io.github.aeewws.codexmobile.workspace.ParsedWriteDirective
import io.github.aeewws.codexmobile.workspace.WorkspaceFileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class BottomDestination(val label: String) {
    CHAT("Chat"),
    COMMANDS("Workspace"),
    SETTINGS("Settings"),
}

enum class EngineMode(
    val label: String,
    val detail: String,
) {
    OFFLINE_GGUF(
        label = "Offline GGUF",
        detail = "Run local .gguf models completely offline on your device and write directly to your selected folder.",
    ),
    CLI_BACKEND(
        label = "Codex CLI Backend",
        detail = "Optional local Codex CLI app-server over WebSocket (requires embedded CLI binary and sign-in).",
    ),
}

enum class PermissionMode(
    val label: String,
    val detail: String,
    val approvalPolicy: String,
    val sandboxCliValue: String,
    val sandboxPolicyType: String,
) {
    DEFAULT_DENY(
        label = "Read-Only",
        detail = "Allow reading files only; block automatic writes to the selected folder.",
        approvalPolicy = "never",
        sandboxCliValue = "read-only",
        sandboxPolicyType = "readOnly",
    ),
    ASK_EACH_TIME(
        label = "Ask Before Write",
        detail = "Prompt for confirmation before writing model-generated files to the selected folder.",
        approvalPolicy = "untrusted",
        sandboxCliValue = "workspace-write",
        sandboxPolicyType = "workspaceWrite",
    ),
    FULL_ACCESS(
        label = "Auto-Write",
        detail = "Write model-generated files directly to the selected folder without prompting.",
        approvalPolicy = "never",
        sandboxCliValue = "danger-full-access",
        sandboxPolicyType = "dangerFullAccess",
    ),
}

enum class FontScaleOption(
    val label: String,
    val scale: Float,
) {
    COMPACT("Compact", 0.92f),
    NORMAL("Standard", 1.0f),
    LARGE("Large", 1.12f),
}

data class ReasoningEffort(
    val value: String,
    val label: String,
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val reasoningOptions: List<ReasoningEffort>,
    val defaultReasoning: String,
)

data class ThreadSummary(
    val id: String,
    val title: String,
    val updatedAtLabel: String,
    val updatedAtEpochSeconds: Long?,
    val source: String,
)

data class GgufModelState(
    val modelUri: String = "",
    val modelName: String = "",
    val modelSizeBytes: Long = 0L,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String = "",
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val temperature: Float = 0.35f,
    val maxTokens: Int = 1536,
)

data class WorkspaceFolderUiState(
    val folderUri: String = "",
    val folderName: String = "",
    val canWrite: Boolean = false,
    val files: List<WorkspaceFileEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastWriteSummary: String = "",
)

data class StatusSnapshot(
    val rootAvailable: Boolean = false,
    val runtimeReady: Boolean = false,
    val runtimePackaged: Boolean = false,
    val runtimeVersion: String = "",
    val runtimeAbi: String = "arm64-v8a",
    val backendListening: Boolean = false,
    val authPresent: Boolean = false,
    val authMode: String = "",
    val accountId: String = "",
    val loginInProgress: Boolean = false,
    val autoHardeningEnabled: Boolean = true,
    val backendStatusDetail: String = "",
)

data class KeepaliveSnapshot(
    val deviceIdleWhitelisted: Boolean = false,
    val restrictBackgroundWhitelisted: Boolean = false,
    val standbyBucket: String = "unknown",
    val batteryOptimizationIgnoredForUiApp: Boolean = false,
)

data class ApprovalRequestUi(
    val requestId: String,
    val requestIdRaw: Any,
    val title: String,
    val subtitle: String,
    val body: String,
    val kind: String,
    val pendingWrites: List<ParsedWriteDirective> = emptyList(),
)

enum class LocalMessageStatus {
    PENDING,
    FAILED,
}

enum class AttachmentKind(
    val label: String,
) {
    IMAGE("Image"),
    DOCUMENT("File"),
}

data class ChatAttachmentUi(
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val previewPath: String? = null,
    val backendPath: String? = null,
    val extractedTextPath: String? = null,
)

sealed interface ChatItemUi {
    val id: String
    val sortKey: String

    data class User(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    ) : ChatItemUi

    data class LocalUser(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: LocalMessageStatus,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    ) : ChatItemUi

    data class Agent(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: String,
    ) : ChatItemUi

    data class AgentPlaceholder(
        override val id: String,
        override val sortKey: String,
        val text: String,
    ) : ChatItemUi

    data class Reasoning(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: String,
    ) : ChatItemUi

    data class Command(
        override val id: String,
        override val sortKey: String,
        val command: String,
        val output: String,
        val status: String,
    ) : ChatItemUi

    data class FileChange(
        override val id: String,
        override val sortKey: String,
        val summary: String,
        val output: String,
        val status: String,
    ) : ChatItemUi

    data class Plan(
        override val id: String,
        override val sortKey: String,
        val text: String,
    ) : ChatItemUi
}

data class ChatComposerState(
    val text: String = "",
    val sending: Boolean = false,
    val canInterrupt: Boolean = false,
    val attachments: List<ChatAttachmentUi> = emptyList(),
)

data class ConnectionBanner(
    val code: String = "starting",
    val title: String = "Ready for Offline GGUF",
    val detail: String = "Select a local .gguf model and output folder to start coding offline.",
    val tone: BannerTone = BannerTone.INFO,
)

enum class BannerTone {
    INFO,
    OK,
    WARN,
    DANGER,
}

data class CodexMobileUiState(
    val activeTab: BottomDestination = BottomDestination.CHAT,
    val engineMode: EngineMode = EngineMode.OFFLINE_GGUF,
    val gguf: GgufModelState = GgufModelState(),
    val workspace: WorkspaceFolderUiState = WorkspaceFolderUiState(),
    val connection: ConnectionBanner = ConnectionBanner(),
    val status: StatusSnapshot = StatusSnapshot(),
    val keepalive: KeepaliveSnapshot = KeepaliveSnapshot(),
    val availableModels: List<ModelOption> = emptyList(),
    val selectedModel: String = "gpt-5.4",
    val selectedReasoning: String = "xhigh",
    val fastModeEnabled: Boolean = false,
    val permissionMode: PermissionMode = PermissionMode.FULL_ACCESS,
    val fontScale: FontScaleOption = FontScaleOption.NORMAL,
    val historyThreads: List<ThreadSummary> = emptyList(),
    val archivedThreads: List<ThreadSummary> = emptyList(),
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "New Chat",
    val messages: List<ChatItemUi> = emptyList(),
    val composer: ChatComposerState = ChatComposerState(),
    val activeApproval: ApprovalRequestUi? = null,
    val deviceAuthCode: String = "",
    val deviceAuthUrl: String = "",
    val loginHint: String = "",
    val statusMessage: String = "",
    val errorMessage: String = "",
)

internal fun reasoningLabel(value: String): String = when (value) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Max"
    else -> value.replaceFirstChar { it.uppercase() }
}

internal fun JSONObject.optEpochSeconds(vararg keys: String): Long? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Number -> return value.toLong()
            is String -> {
                value.toLongOrNull()?.let { return it }
                runCatching { Instant.parse(value).epochSecond }.getOrNull()?.let { return it }
            }
        }
    }
    return null
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.optJsonArray(key: String): JSONArray? =
    optJSONArray(key) ?: optJSONObject(key)?.optJSONArray(key)

internal fun JSONArray.toReasoningOptions(): List<ReasoningEffort> {
    val items = mutableListOf<ReasoningEffort>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val effort = item.optString("effort").ifBlank { continue }
        items += ReasoningEffort(
            value = effort,
            label = reasoningLabel(effort),
        )
    }
    return items
}
