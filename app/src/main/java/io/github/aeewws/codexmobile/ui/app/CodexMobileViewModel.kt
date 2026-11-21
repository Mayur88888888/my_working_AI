package io.github.aeewws.codexmobile.ui.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aeewws.codexmobile.llm.ChatPromptMessage
import io.github.aeewws.codexmobile.llm.LocalGgufEngine
import io.github.aeewws.codexmobile.llm.OfflineConversationStore
import io.github.aeewws.codexmobile.llm.OfflineMessageRecord
import io.github.aeewws.codexmobile.llm.OfflineThreadRecord
import io.github.aeewws.codexmobile.runtime.CodexRuntimeController
import io.github.aeewws.codexmobile.workspace.ParsedWriteDirective
import io.github.aeewws.codexmobile.workspace.WorkspaceActionParser
import io.github.aeewws.codexmobile.workspace.WorkspaceFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CodexMobileViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtimeController = CodexRuntimeController(application)
    private val attachmentProcessor = AttachmentProcessor(application, runtimeController)
    private val workspaceFileManager = WorkspaceFileManager(application)
    private val ggufEngine = LocalGgufEngine(application)
    private val offlineStore = OfflineConversationStore(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rpcClient = CodexRpcClient(viewModelScope)

    private val offlineThreadsById = linkedMapOf<String, OfflineThreadRecord>().apply {
        offlineStore.loadAll().forEach { put(it.id, it) }
    }
    private val archivedThreadIds = loadArchivedThreadIds().toMutableSet()
    private val deletedThreadIds = loadDeletedThreadIds().toMutableSet()
    private val customThreadTitles = loadCustomThreadTitles().toMutableMap()
    private val localDraftsByThread = linkedMapOf<String, MutableList<LocalDraftMessage>>()
    private val persistedMessageAttachments = mutableMapOf<String, PersistedMessageAttachments>()

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<CodexMobileUiState> = _uiState

    private val localHistoryById = linkedMapOf<String, ThreadSummary>()
    private val remoteHistoryById = linkedMapOf<String, ThreadSummary>()
    private val lastUserTextByThread = mutableMapOf<String, String>()

    private var activeCliThread: CliMutableThread? = null
    private val runningTurnIds = linkedSetOf<String>()
    private val pendingTurns = linkedMapOf<String, PendingTurn>()
    private val pendingTurnJobs = mutableMapOf<String, Job>()
    private val resumedThreadIds = linkedSetOf<String>()
    private var offlineGenerationJob: Job? = null
    private var transientStatusJob: Job? = null
    private var reconnectJob: Job? = null
    private var loginPollJob: Job? = null
    private val reconnectMutex = Mutex()

    init {
        publishMergedHistory()
        restoreInitialOfflineConversation()
        updateOfflineBanner()
    }

    fun onAppResume() {
        viewModelScope.launch {
            refreshWorkspaceFolderInternal(silent = true)
            if (_uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
                autoLoadSavedGgufModelIfNeeded()
                updateOfflineBanner()
                refreshSupplementalState()
                syncForegroundService()
            } else {
                if (rpcClient.isOpen()) {
                    refreshSupplementalState()
                    refreshActiveThreadSnapshot()
                    syncForegroundService()
                } else {
                    recoverAndConnect()
                }
            }
        }
    }

    fun onAppPause() {
        syncForegroundService()
    }

    fun onUiClosed() {
        loginPollJob?.cancel()
        loginPollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        offlineGenerationJob?.cancel()
        offlineGenerationJob = null
        resumedThreadIds.clear()
        rpcClient.close(notifyClosure = false)
        viewModelScope.launch {
            runCatching { ggufEngine.unloadModel() }
            runCatching { runtimeController.shutdownManagedSession() }
        }
    }

    fun onClearedFromUi() {
        loginPollJob?.cancel()
        loginPollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        resumedThreadIds.clear()
        rpcClient.close(notifyClosure = false)
    }

    fun switchTab(tab: BottomDestination) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun selectEngineMode(mode: EngineMode) {
        if (_uiState.value.engineMode == mode) return
        prefs.edit().putString(PREF_ENGINE_MODE, mode.name).apply()
        _uiState.update {
            it.copy(
                engineMode = mode,
                activeApproval = null,
                errorMessage = "",
            )
        }
        if (mode == EngineMode.OFFLINE_GGUF) {
            reconnectJob?.cancel()
            rpcClient.close(notifyClosure = false)
            viewModelScope.launch {
                autoLoadSavedGgufModelIfNeeded()
                updateOfflineBanner()
                publishMergedHistory()
                restoreInitialOfflineConversation()
            }
        } else {
            showNewConversation()
            viewModelScope.launch {
                recoverAndConnect(forceReconnect = true, manual = true)
            }
        }
    }

    fun startNewConversation() {
        showNewConversation()
        switchTab(BottomDestination.CHAT)
    }

    fun updateDraft(text: String) {
        _uiState.update { state ->
            state.copy(composer = state.composer.copy(text = text))
        }
    }

    // ---- GGUF Model Management ----

    fun selectGgufModel(uri: Uri) {
        viewModelScope.launch {
            loadGgufFromUri(uri, takePersistable = true)
        }
    }

    fun reloadGgufModel() {
        val uri = ggufEngine.getSavedModelUri()
        if (uri == null) {
            setError("No saved GGUF model URI. Pick a .gguf file first.")
            return
        }
        viewModelScope.launch {
            loadGgufFromUri(uri, takePersistable = false)
        }
    }

    fun unloadGgufModel() {
        viewModelScope.launch {
            offlineGenerationJob?.cancel()
            ggufEngine.stopGeneration()
            ggufEngine.unloadModel()
            _uiState.update { state ->
                state.copy(
                    gguf = state.gguf.copy(isLoading = false, isLoaded = false, loadError = ""),
                )
            }
            updateOfflineBanner()
            showTransientStatus("Unloaded GGUF model from memory")
        }
    }

    fun updateGgufContextLength(contextLength: Int) {
        val clamped = contextLength.coerceIn(1024, 16384)
        prefs.edit().putInt(PREF_GGUF_CTX, clamped).apply()
        _uiState.update { it.copy(gguf = it.gguf.copy(contextLength = clamped)) }
        if (ggufEngine.isModelLoaded()) {
            reloadGgufModel()
        }
    }

    fun updateGgufThreads(threads: Int) {
        val clamped = threads.coerceIn(1, 12)
        prefs.edit().putInt(PREF_GGUF_THREADS, clamped).apply()
        _uiState.update { it.copy(gguf = it.gguf.copy(threads = clamped)) }
        if (ggufEngine.isModelLoaded()) {
            reloadGgufModel()
        }
    }

    fun updateGgufTemperature(temperature: Float) {
        val clamped = temperature.coerceIn(0f, 1.5f)
        prefs.edit().putFloat(PREF_GGUF_TEMP, clamped).apply()
        _uiState.update { it.copy(gguf = it.gguf.copy(temperature = clamped)) }
    }

    fun updateGgufMaxTokens(maxTokens: Int) {
        val clamped = maxTokens.coerceIn(256, 4096)
        prefs.edit().putInt(PREF_GGUF_MAX_TOKENS, clamped).apply()
        _uiState.update { it.copy(gguf = it.gguf.copy(maxTokens = clamped)) }
    }

    private suspend fun autoLoadSavedGgufModelIfNeeded() {
        if (ggufEngine.isModelLoaded() || _uiState.value.gguf.isLoading) return
        val savedUri = ggufEngine.getSavedModelUri() ?: return
        loadGgufFromUri(savedUri, takePersistable = false)
    }

    private suspend fun loadGgufFromUri(uri: Uri, takePersistable: Boolean) {
        val cfg = _uiState.value.gguf
        setError("")
        setStatus("Loading GGUF model...")
        _uiState.update { state ->
            state.copy(
                gguf = state.gguf.copy(isLoading = true, loadError = ""),
            )
        }
        updateOfflineBanner()

        val result = ggufEngine.loadModel(
            uri = uri,
            contextLength = cfg.contextLength,
            threads = cfg.threads,
            takePersistablePermission = takePersistable,
        )

        result.onSuccess { info ->
            _uiState.update { state ->
                state.copy(
                    gguf = state.gguf.copy(
                        modelUri = info.uriString,
                        modelName = info.displayName,
                        modelSizeBytes = info.sizeBytes,
                        isLoading = false,
                        isLoaded = true,
                        loadError = "",
                    ),
                )
            }
            updateOfflineBanner()
            showTransientStatus("Loaded ${info.displayName} (${LocalGgufEngine.formatByteSize(info.sizeBytes)})")
        }.onFailure { err ->
            val msg = err.message ?: "Failed to load GGUF model"
            _uiState.update { state ->
                state.copy(
                    gguf = state.gguf.copy(
                        isLoading = false,
                        isLoaded = false,
                        loadError = msg,
                    ),
                )
            }
            updateOfflineBanner()
            setError(msg)
        }
    }

    // ---- Direct Selected Folder Workspace Management ----

    fun selectWorkspaceFolder(uri: Uri) {
        viewModelScope.launch {
            setError("")
            setStatus("Opening selected folder...")
            workspaceFileManager.selectFolder(uri).onSuccess { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        workspace = WorkspaceFolderUiState(
                            folderUri = snapshot.treeUri,
                            folderName = snapshot.displayName,
                            canWrite = snapshot.canWrite,
                            files = snapshot.files,
                            isRefreshing = false,
                            lastWriteSummary = state.workspace.lastWriteSummary,
                        ),
                    )
                }
                updateOfflineBanner()
                showTransientStatus("Output folder set to ${snapshot.displayName}")
            }.onFailure { err ->
                setError(err.message ?: "Unable to access the selected folder")
            }
        }
    }

    fun refreshWorkspaceFolder() {
        viewModelScope.launch {
            refreshWorkspaceFolderInternal(silent = false)
        }
    }

    fun clearWorkspaceFolder() {
        workspaceFileManager.clearSelectedFolder()
        _uiState.update { state ->
            state.copy(workspace = WorkspaceFolderUiState())
        }
        updateOfflineBanner()
        showTransientStatus("Cleared selected output folder")
    }

    fun writeFileDirectlyToWorkspace(
        relativePath: String,
        content: String,
        recordInChat: Boolean = true,
    ) {
        viewModelScope.launch {
            setError("")
            workspaceFileManager.writeFile(relativePath, content).onSuccess { writeResult ->
                refreshWorkspaceFolderInternal(silent = true)
                val folderName = _uiState.value.workspace.folderName.ifBlank { "Selected Folder" }
                val verb = if (writeResult.created) "Created" else "Updated"
                val summaryLine = "$verb ${writeResult.relativePath} (${writeResult.bytesWritten} bytes) in $folderName"
                _uiState.update { state ->
                    state.copy(workspace = state.workspace.copy(lastWriteSummary = summaryLine))
                }
                if (recordInChat && _uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
                    appendOfflineFileChangeMessage(
                        summary = summaryLine,
                        output = content.take(4000),
                        status = "written",
                    )
                }
                showTransientStatus("Saved ${writeResult.relativePath} to $folderName")
            }.onFailure { err ->
                setError(err.message ?: "Failed to write file to selected folder")
            }
        }
    }

    fun readWorkspaceFile(relativePath: String, onLoaded: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = workspaceFileManager.readFile(relativePath)
            onLoaded(result)
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        viewModelScope.launch {
            workspaceFileManager.deleteFile(relativePath).onSuccess {
                refreshWorkspaceFolderInternal(silent = true)
                showTransientStatus("Deleted $relativePath")
            }.onFailure { err ->
                setError(err.message ?: "Failed to delete $relativePath")
            }
        }
    }

    private suspend fun refreshWorkspaceFolderInternal(silent: Boolean) {
        _uiState.update { state ->
            state.copy(workspace = state.workspace.copy(isRefreshing = true))
        }
        workspaceFileManager.refreshFolder().onSuccess { snapshot ->
            _uiState.update { state ->
                if (snapshot == null) {
                    state.copy(workspace = state.workspace.copy(isRefreshing = false))
                } else {
                    state.copy(
                        workspace = WorkspaceFolderUiState(
                            folderUri = snapshot.treeUri,
                            folderName = snapshot.displayName,
                            canWrite = snapshot.canWrite,
                            files = snapshot.files,
                            isRefreshing = false,
                            lastWriteSummary = state.workspace.lastWriteSummary,
                        ),
                    )
                }
            }
            if (!silent) {
                showTransientStatus("Folder contents refreshed")
            }
        }.onFailure { err ->
            _uiState.update { state ->
                state.copy(workspace = state.workspace.copy(isRefreshing = false))
            }
            if (!silent) {
                setError(err.message ?: "Failed to refresh folder")
            }
        }
    }

    // ---- Attachments ----

    fun attachFiles(uris: List<Uri>) {
        viewModelScope.launch {
            runCatching {
                setError("")
                setStatus("Preparing attachments...")
                val existing = _uiState.value.composer.attachments
                val kept = existing.take(MAX_ATTACHMENTS)
                val prepared = mutableListOf<ChatAttachmentUi>()
                uris.take(MAX_ATTACHMENTS - kept.size).forEach { uri ->
                    prepared += attachmentProcessor.prepareAttachment(uri)
                }
                _uiState.update { state ->
                    state.copy(composer = state.composer.copy(attachments = (kept + prepared).take(MAX_ATTACHMENTS)))
                }
                clearStatus()
            }.onFailure { error ->
                setError(error.message ?: "Failed to attach file")
            }
        }
    }

    fun attachCameraBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            runCatching {
                setError("")
                setStatus("Preparing photo...")
                val existing = _uiState.value.composer.attachments
                if (existing.size >= MAX_ATTACHMENTS) {
                    error("Maximum of $MAX_ATTACHMENTS attachments reached")
                }
                val attachment = attachmentProcessor.prepareCameraAttachment(bitmap)
                _uiState.update { state ->
                    state.copy(composer = state.composer.copy(attachments = (state.composer.attachments + attachment).take(MAX_ATTACHMENTS)))
                }
                clearStatus()
            }.onFailure { error ->
                setError(error.message ?: "Failed to attach photo")
            }
        }
    }

    fun clearComposerAttachments(index: Int) {
        val attachments = _uiState.value.composer.attachments
        if (index !in attachments.indices) return
        scheduleAttachmentDeletion(attachments[index])
        _uiState.update { state ->
            state.copy(
                composer = state.composer.copy(
                    attachments = state.composer.attachments.filterIndexed { i, _ -> i != index },
                ),
            )
        }
    }

    // ---- Prompt Sending & Inference ----

    fun sendPrompt() {
        val draftText = _uiState.value.composer.text.trim()
        val attachments = _uiState.value.composer.attachments
        if (draftText.isEmpty() && attachments.isEmpty()) return

        if (_uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
            sendOfflineGgufPrompt(draftText, attachments)
        } else {
            viewModelScope.launch {
                runCatching {
                    startCliTurn(buildPreparedSubmission(draftText, attachments))
                }.onFailure { error ->
                    setError(error.message ?: "Failed to send message")
                }
            }
        }
    }

    private fun sendOfflineGgufPrompt(
        draftText: String,
        attachments: List<ChatAttachmentUi>,
    ) {
        if (offlineGenerationJob?.isActive == true) {
            setError("Generation is already in progress. Stop the current reply first.")
            return
        }

        offlineGenerationJob = viewModelScope.launch {
            setError("")
            if (!ggufEngine.isModelLoaded()) {
                val savedUri = ggufEngine.getSavedModelUri()
                if (savedUri != null) {
                    loadGgufFromUri(savedUri, takePersistable = false)
                }
            }
            if (!ggufEngine.isModelLoaded()) {
                setError("No GGUF model is loaded. Pick a .gguf model file to run offline.")
                return@launch
            }

            val prepared = runCatching { buildPreparedSubmission(draftText, attachments) }.getOrElse { err ->
                setError(err.message ?: "Failed to prepare message")
                return@launch
            }

            val threadId = ensureOfflineThreadId(initialTitleHint = prepared.displayText)
            val userMsg = OfflineMessageRecord(
                id = "user-${System.currentTimeMillis()}",
                role = "user",
                text = prepared.displayText,
                status = "completed",
                attachments = prepared.attachments,
            )
            appendOfflineMessage(threadId, userMsg)

            val assistantMsgId = "assistant-${System.currentTimeMillis()}"
            appendOfflineMessage(
                threadId,
                OfflineMessageRecord(
                    id = assistantMsgId,
                    role = "assistant",
                    text = "",
                    status = "inProgress",
                ),
            )

            _uiState.update { state ->
                state.copy(
                    composer = ChatComposerState(
                        text = "",
                        sending = true,
                        canInterrupt = true,
                        attachments = emptyList(),
                    ),
                )
            }
            syncForegroundService()

            try {
                val cfg = _uiState.value.gguf
                val systemPrompt = buildOfflineSystemPrompt()
                val conversationTurns = buildOfflinePromptMessages(
                    threadId = threadId,
                    latestUserPrompt = prepared.transportText,
                    assistantPlaceholderId = assistantMsgId,
                ).toMutableList()

                var finalReply = ""
                var rounds = 0
                while (rounds < 3) {
                    rounds++
                    val promptMessages = listOf(ChatPromptMessage("system", systemPrompt)) + conversationTurns
                    val genResult = ggufEngine.generate(
                        messages = promptMessages,
                        temperature = cfg.temperature.toDouble(),
                        maxTokens = cfg.maxTokens,
                        onToken = { partial ->
                            updateOfflineMessageText(threadId, assistantMsgId, partial, status = "inProgress")
                        },
                    )
                    val replyText = genResult.getOrThrow()
                    finalReply = replyText
                    updateOfflineMessageText(threadId, assistantMsgId, replyText, status = "completed")

                    val parsed = WorkspaceActionParser.parseDirectives(replyText)
                    if (parsed.readDirectives.isNotEmpty() && parsed.writeDirectives.isEmpty() && rounds < 3) {
                        val readResults = parsed.readDirectives.take(4).map { req ->
                            val content = workspaceFileManager.readFile(req.relativePath)
                                .getOrElse { err -> "ERROR reading ${req.relativePath}: ${err.message}" }
                            "<file_contents path=\"${req.relativePath}\">\n$content\n</file_contents>"
                        }.joinToString("\n\n")
                        conversationTurns += ChatPromptMessage("assistant", replyText)
                        conversationTurns += ChatPromptMessage(
                            "user",
                            "Here are the requested file contents from the selected folder:\n\n$readResults\n\nNow continue and provide your final answer or <write_file> updates.",
                        )
                        continue
                    }
                    break
                }

                handleOfflineWriteDirectives(threadId, finalReply)
            } catch (cancel: CancellationException) {
                updateOfflineMessageStatus(threadId, assistantMsgId, "interrupted")
            } catch (err: Throwable) {
                val msg = err.message ?: "Offline GGUF generation failed"
                updateOfflineMessageText(
                    threadId = threadId,
                    messageId = assistantMsgId,
                    text = "Generation error: $msg",
                    status = "failed",
                )
                setError(msg)
            } finally {
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(
                            sending = false,
                            canInterrupt = false,
                        ),
                    )
                }
                syncForegroundService()
            }
        }
    }

    private suspend fun handleOfflineWriteDirectives(threadId: String, replyText: String) {
        val parsed = WorkspaceActionParser.parseDirectives(replyText)
        val directives = parsed.writeDirectives
        if (directives.isEmpty()) return

        val workspace = _uiState.value.workspace
        if (workspace.folderUri.isBlank() || !workspace.canWrite) {
            appendOfflineFileChangeMessage(
                summary = "Generated ${directives.size} file(s), but no writable output folder is selected.",
                output = directives.joinToString("\n") { "- ${it.relativePath} (${it.content.length} chars)" } +
                    "\n\nPick an output folder and tap 'Write to Folder' on any code block above.",
                status = "folder_required",
                threadId = threadId,
            )
            setError("Pick a writable output folder to save generated files directly.")
            return
        }

        when (_uiState.value.permissionMode) {
            PermissionMode.FULL_ACCESS -> {
                executeOfflineWrites(threadId, directives)
            }

            PermissionMode.ASK_EACH_TIME -> {
                val preview = directives.joinToString("\n") { d ->
                    "• ${d.relativePath} (${d.content.toByteArray(Charsets.UTF_8).size} bytes)"
                }
                _uiState.update { state ->
                    state.copy(
                        activeApproval = ApprovalRequestUi(
                            requestId = "offline-write-${System.currentTimeMillis()}",
                            requestIdRaw = threadId,
                            title = "Write ${directives.size} file(s) to ${workspace.folderName}?",
                            subtitle = "The offline model generated file updates for your selected folder.",
                            body = preview,
                            kind = "offline_write",
                            pendingWrites = directives,
                        ),
                        activeTab = BottomDestination.CHAT,
                    )
                }
            }

            PermissionMode.DEFAULT_DENY -> {
                appendOfflineFileChangeMessage(
                    summary = "Read-Only mode blocked automatic write for ${directives.size} file(s).",
                    output = directives.joinToString("\n") { "- ${it.relativePath}" } +
                        "\n\nSwitch permission to Ask Before Write or Auto-Write, or tap 'Write to Folder' on any code block.",
                    status = "blocked_readonly",
                    threadId = threadId,
                )
            }
        }
    }

    private suspend fun executeOfflineWrites(
        threadId: String,
        directives: List<ParsedWriteDirective>,
    ) {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()
        directives.forEach { directive ->
            workspaceFileManager.writeFile(directive.relativePath, directive.content)
                .onSuccess { res ->
                    val verb = if (res.created) "Created" else "Updated"
                    successes += "$verb ${res.relativePath} (${res.bytesWritten} bytes)"
                }
                .onFailure { err ->
                    failures += "Failed ${directive.relativePath}: ${err.message}"
                }
        }
        refreshWorkspaceFolderInternal(silent = true)
        val folderName = _uiState.value.workspace.folderName.ifBlank { "Selected Folder" }
        val summary = if (failures.isEmpty()) {
            "Wrote ${successes.size} file(s) directly to $folderName"
        } else {
            "Wrote ${successes.size} file(s), ${failures.size} failed"
        }
        val output = (successes + failures).joinToString("\n")
        _uiState.update { state ->
            state.copy(workspace = state.workspace.copy(lastWriteSummary = summary))
        }
        appendOfflineFileChangeMessage(
            summary = summary,
            output = output,
            status = if (failures.isEmpty()) "written" else "partial_error",
            threadId = threadId,
        )
        if (failures.isEmpty()) {
            showTransientStatus(summary)
        } else {
            setError(failures.first())
        }
    }

    private fun buildOfflineSystemPrompt(): String {
        val workspace = _uiState.value.workspace
        val filesList = if (workspace.files.isEmpty()) {
            "(empty folder)"
        } else {
            workspace.files.take(40).joinToString("\n") { entry ->
                if (entry.isDirectory) "- [dir] ${entry.relativePath}/" else "- ${entry.relativePath} (${entry.sizeBytes} bytes)"
            }
        }
        return buildString {
            appendLine("You are Codex Mobile, an offline on-device coding assistant.")
            if (workspace.folderUri.isNotBlank()) {
                appendLine("Selected output folder: \"${workspace.folderName}\" (writable=${workspace.canWrite}).")
                appendLine("Current files in folder:")
                appendLine(filesList)
                appendLine()
                appendLine("To create or overwrite a file directly inside the user's selected folder, output:")
                appendLine("<write_file path=\"relative/path.ext\">")
                appendLine("full file contents here")
                appendLine("</write_file>")
                appendLine("To read an existing file from the folder before answering, output:")
                appendLine("<read_file path=\"relative/path.ext\" />")
                appendLine("Always use clean relative paths without '..' and provide complete file contents when writing.")
            } else {
                appendLine("No output folder is currently selected. Put code in fenced markdown blocks with the language and filename.")
            }
        }.trim()
    }

    private fun buildOfflinePromptMessages(
        threadId: String,
        latestUserPrompt: String,
        assistantPlaceholderId: String,
    ): List<ChatPromptMessage> {
        val thread = offlineThreadsById[threadId] ?: return listOf(ChatPromptMessage("user", latestUserPrompt))
        val priorMessages = thread.messages
            .filter { it.id != assistantPlaceholderId && (it.role == "user" || it.role == "assistant") }
        val result = mutableListOf<ChatPromptMessage>()
        val windowed = priorMessages.takeLast(12)
        windowed.forEachIndexed { idx, record ->
            val isLatestUser = idx == windowed.lastIndex && record.role == "user"
            val content = if (isLatestUser) latestUserPrompt else record.text
            if (content.isNotBlank()) {
                result += ChatPromptMessage(record.role, content)
            }
        }
        return result
    }

    fun retryLocalDraft(localMessageId: String) {
        val draft = findLocalDraft(localMessageId) ?: return
        updateLocalDraftStatus(localMessageId, LocalMessageStatus.PENDING)
        viewModelScope.launch {
            runCatching {
                startCliTurn(
                    prepared = rebuildPreparedSubmission(draft),
                    targetThreadId = draft.threadId,
                    localDraftId = draft.id,
                    clearDraft = false,
                )
            }.onFailure { error ->
                setError(error.message ?: "Retry failed")
            }
        }
    }

    fun interruptTurn() {
        if (_uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
            viewModelScope.launch {
                ggufEngine.stopGeneration()
                offlineGenerationJob?.cancel()
                _uiState.update { state ->
                    state.copy(composer = state.composer.copy(sending = false, canInterrupt = false))
                }
                showTransientStatus("Stopped generation")
                syncForegroundService()
            }
            return
        }

        val threadId = _uiState.value.activeThreadId ?: return
        val turnId = latestRunningTurnId() ?: return
        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request(
                    method = "turn/interrupt",
                    params = JSONObject()
                        .put("threadId", threadId)
                        .put("turnId", turnId),
                )
                noteTurnFinished(turnId)
                clearStatus()
                syncForegroundService()
            }.onFailure { error ->
                setError(error.message ?: "Failed to stop generation")
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            refreshWorkspaceFolderInternal(silent = false)
            if (_uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
                autoLoadSavedGgufModelIfNeeded()
                updateOfflineBanner()
                return@launch
            }
            if (!rpcClient.isOpen()) {
                recoverAndConnect(forceReconnect = true, manual = true)
                return@launch
            }
            runCatching {
                val latestBackend = runtimeController.getBackendStatus().toStatusSnapshot()
                applyBackendStatus(latestBackend)
                refreshSupplementalState()
                if (!latestBackend.backendListening) {
                    error("Local backend is not listening")
                }
                loadRemoteData()
                clearStatus()
                setError("")
            }.onFailure { error ->
                if (error.isInternalCancellation()) return@onFailure
                recoverAndConnect(forceReconnect = true, manual = true)
            }
        }
    }

    fun restartBackend() {
        viewModelScope.launch {
            runCatching {
                setError("")
                setStatus("Restarting local backend...")
                val latestBackend = runtimeController.restartBackend().toStatusSnapshot()
                applyBackendStatus(latestBackend)
                refreshSupplementalState()
                if (!latestBackend.backendListening) {
                    error(latestBackend.backendStatusDetail.ifBlank { "Backend restart failed" })
                }
                if (latestBackend.authPresent) {
                    recoverAndConnect(forceReconnect = true, manual = true)
                } else {
                    clearStatus()
                }
            }.onFailure { error ->
                setError(error.message ?: "Failed to restart backend")
            }
        }
    }

    fun startLogin() {
        if (_uiState.value.status.loginInProgress) return
        viewModelScope.launch {
            runCatching {
                setError("")
                _uiState.update { state ->
                    state.copy(
                        deviceAuthCode = "",
                        deviceAuthUrl = "",
                        loginHint = "",
                        status = state.status.copy(loginInProgress = true),
                        connection = state.connection.copy(
                            code = "login_in_progress",
                            title = "Signing in to Codex",
                            detail = "Preparing browser OAuth. If the browser page fails to open, copy the verification URL manually.",
                            tone = BannerTone.INFO,
                        ),
                    )
                }
                syncForegroundService()
                val response = runtimeController.startLogin()
                val latestBackend = response.toStatusSnapshot()
                val verificationUrl = response.optString("verificationUrl")
                val loginHint = when {
                    verificationUrl.isNotBlank() ->
                        "If the browser redirect opens blank, copy the URL below and open it in your preferred browser."
                    else -> response.optString("loginHint")
                }
                loginPollJob?.cancel()
                applyBackendStatus(latestBackend)
                updateDeviceLoginInfo(code = "", url = verificationUrl, hint = loginHint)
                refreshSupplementalState()
                val errorMessage = response.optString("error")
                if (errorMessage.isNotBlank()) error(errorMessage)
                if (latestBackend.authPresent) {
                    showTransientStatus("Signed in; reconnecting backend")
                    recoverAndConnect(forceReconnect = true, manual = true)
                } else {
                    showTransientStatus("Login link ready")
                    beginLoginPolling()
                }
            }.onFailure { error ->
                _uiState.update { state -> state.copy(status = state.status.copy(loginInProgress = false)) }
                setError(error.message ?: "Sign-in failed")
            }
        }
    }

    fun startDeviceLogin() {
        if (_uiState.value.status.loginInProgress) return
        viewModelScope.launch {
            runCatching {
                setError("")
                _uiState.update { state ->
                    state.copy(
                        deviceAuthCode = "",
                        deviceAuthUrl = "",
                        loginHint = "",
                        status = state.status.copy(loginInProgress = true),
                        connection = state.connection.copy(
                            code = "login_in_progress",
                            title = "Starting Device Code Login",
                            detail = "Generating device code. Copy the code and open the verification page.",
                            tone = BannerTone.INFO,
                        ),
                    )
                }
                syncForegroundService()
                val response = runtimeController.startDeviceLogin()
                val latestBackend = response.toStatusSnapshot()
                val deviceCode = response.optString("deviceCode")
                val verificationUrl = response.optString("verificationUrl")
                val loginHint = when {
                    deviceCode.isNotBlank() && verificationUrl.isNotBlank() ->
                        "Copy the device code first, then tap Open Verification Page."
                    verificationUrl.isNotBlank() ->
                        "Verification URL is ready. Copy and open it in a browser if needed."
                    else -> response.optString("loginHint")
                }
                applyBackendStatus(latestBackend)
                updateDeviceLoginInfo(code = deviceCode, url = verificationUrl, hint = loginHint)
                refreshSupplementalState()
                val errorMessage = response.optString("error")
                if (errorMessage.isNotBlank()) error(errorMessage)
                showTransientStatus("Device code generated")
                beginLoginPolling()
            }.onFailure { error ->
                _uiState.update { state -> state.copy(status = state.status.copy(loginInProgress = false)) }
                setError(error.message ?: "Device login failed")
            }
        }
    }

    fun copyDeviceCode() {
        val code = _uiState.value.deviceAuthCode.ifBlank {
            setError("No device code available to copy")
            return
        }
        copyToClipboard(label = "Codex Device Code", value = code)
        showTransientStatus("Device code copied")
    }

    fun copyVerificationUrl() {
        val url = _uiState.value.deviceAuthUrl.ifBlank {
            setError("No verification URL available to copy")
            return
        }
        copyToClipboard(label = "Codex Verification URL", value = url)
        showTransientStatus("Verification URL copied")
    }

    fun openVerificationPage(forceEnglish: Boolean = false) {
        val url = _uiState.value.deviceAuthUrl.ifBlank {
            setError("No verification URL available")
            return
        }
        val target = if (forceEnglish) withUiLocales(url, "en-US") else url
        runCatching {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)),
                "Choose a browser",
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(chooser)
            showTransientStatus("Opened verification page in browser")
        }.onFailure { error ->
            setError(error.message ?: "Failed to open verification page")
        }
    }

    fun logout() {
        if (_uiState.value.status.loginInProgress) return
        viewModelScope.launch {
            runCatching {
                setError("")
                setStatus("Clearing local credentials...")
                loginPollJob?.cancel()
                loginPollJob = null
                reconnectJob?.cancel()
                reconnectJob = null
                resumedThreadIds.clear()
                rpcClient.close(notifyClosure = false)
                val latestBackend = runtimeController.logout().toStatusSnapshot()
                applyBackendStatus(latestBackend.copy(loginInProgress = false))
                updateDeviceLoginInfo(code = "", url = "", hint = "")
                refreshSupplementalState()
                clearStatus()
                showTransientStatus("Signed out; local chats and settings kept")
            }.onFailure { error ->
                setError(error.message ?: "Sign-out failed")
            }
        }
    }

    fun selectModel(modelId: String) {
        _uiState.update { state ->
            val selectedModel = state.availableModels.find { it.id == modelId }
            state.copy(
                selectedModel = modelId,
                selectedReasoning = selectedModel?.let { chooseReasoning(state.availableModels, modelId, state.selectedReasoning) }
                    ?: state.selectedReasoning,
            )
        }
        persistPreferences()
    }

    fun selectReasoningEffort(effort: String) {
        _uiState.update { state -> state.copy(selectedReasoning = effort) }
        persistPreferences()
    }

    fun selectFastMode(enabled: Boolean) {
        _uiState.update { state -> state.copy(fastModeEnabled = enabled) }
        if (!enabled) setError("")
        persistPreferences()
    }

    fun selectPermissionMode(mode: PermissionMode) {
        _uiState.update { state -> state.copy(permissionMode = mode) }
        persistPreferences()
    }

    fun selectFontScale(option: FontScaleOption) {
        _uiState.update { state -> state.copy(fontScale = option) }
        persistPreferences()
    }

    // ---- History & Thread Actions ----

    fun openHistoryThread(threadId: String) {
        if (offlineThreadsById.containsKey(threadId) || _uiState.value.engineMode == EngineMode.OFFLINE_GGUF) {
            val thread = offlineThreadsById[threadId]
            if (thread != null) {
                publishOfflineThread(thread)
                _uiState.update { it.copy(activeTab = BottomDestination.CHAT) }
            } else {
                setError("Conversation not found")
            }
            return
        }

        viewModelScope.launch {
            runCatching {
                loadCliThreadIntoChat(threadId, switchToChat = true, showStatusHint = true)
            }.onFailure { error ->
                setError(error.message ?: "Failed to open conversation")
            }
        }
    }

    fun archiveHistoryThread(threadId: String) {
        if (offlineThreadsById.containsKey(threadId)) {
            hideArchivedThread(threadId)
            if (_uiState.value.activeThreadId == threadId) {
                showNewConversation()
            } else {
                publishMergedHistory()
            }
            showTransientStatus("Archived conversation")
            return
        }

        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request("thread/archive", JSONObject().put("threadId", threadId))
                hideArchivedThread(threadId)
                if (_uiState.value.activeThreadId == threadId) showNewConversation() else publishMergedHistory()
                showTransientStatus("Archived conversation")
            }.onFailure { error ->
                hideArchivedThread(threadId)
                if (_uiState.value.activeThreadId == threadId) showNewConversation() else publishMergedHistory()
                showTransientStatus("Archived locally")
            }
        }
    }

    fun unarchiveHistoryThread(threadId: String) {
        if (offlineThreadsById.containsKey(threadId)) {
            showArchivedThread(threadId)
            publishMergedHistory()
            showTransientStatus("Restored conversation")
            return
        }

        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request("thread/unarchive", JSONObject().put("threadId", threadId))
                showArchivedThread(threadId)
                publishMergedHistory()
                showTransientStatus("Restored conversation")
            }.onFailure {
                showArchivedThread(threadId)
                publishMergedHistory()
                showTransientStatus("Restored locally")
            }
        }
    }

    fun deleteHistoryThread(threadId: String) {
        if (threadId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                if (offlineThreadsById.remove(threadId) != null) {
                    persistOfflineThreads()
                } else {
                    runtimeController.deleteThreadArtifacts(threadId)
                    deletedThreadIds += threadId
                    persistDeletedThreadIds()
                }
                archivedThreadIds.remove(threadId)
                customThreadTitles.remove(threadId)
                persistArchivedThreadIds()
                persistCustomThreadTitles()
                localHistoryById.remove(threadId)
                remoteHistoryById.remove(threadId)
                if (_uiState.value.activeThreadId == threadId || activeCliThread?.id == threadId) {
                    showNewConversation()
                } else {
                    publishMergedHistory()
                }
                setError("")
                showTransientStatus("Deleted conversation")
            }.onFailure { error ->
                setError(error.message ?: "Failed to delete conversation")
            }
        }
    }

    fun renameHistoryThread(threadId: String, rawTitle: String) {
        val title = rawTitle.trim()
        if (threadId.isBlank()) return
        if (title.isBlank()) {
            setError("Conversation title cannot be empty")
            return
        }
        customThreadTitles[threadId] = title
        persistCustomThreadTitles()

        offlineThreadsById[threadId]?.let { existing ->
            val updated = existing.copy(title = title)
            offlineThreadsById[threadId] = updated
            persistOfflineThreads()
            if (_uiState.value.activeThreadId == threadId) {
                publishOfflineThread(updated)
            }
        }

        activeCliThread = activeCliThread?.let { thread ->
            if (thread.id == threadId) thread.copy(customTitle = title) else thread
        }
        if (_uiState.value.activeThreadId == threadId) {
            persistThreadSelection(threadId, title)
        }
        publishMergedHistory()
        if (_uiState.value.activeThreadId == threadId && _uiState.value.engineMode == EngineMode.CLI_BACKEND) {
            publishActiveCliThread()
        }
        setError("")
        showTransientStatus("Renamed conversation")
    }

    fun answerApproval(accept: Boolean) {
        val approval = _uiState.value.activeApproval ?: return
        if (approval.kind == "offline_write") {
            _uiState.update { it.copy(activeApproval = null) }
            val threadId = approval.requestIdRaw as? String ?: _uiState.value.activeThreadId ?: return
            viewModelScope.launch {
                if (accept) {
                    executeOfflineWrites(threadId, approval.pendingWrites)
                } else {
                    appendOfflineFileChangeMessage(
                        summary = "Declined writing ${approval.pendingWrites.size} file(s) to folder.",
                        output = approval.pendingWrites.joinToString("\n") { "- ${it.relativePath}" },
                        status = "declined",
                        threadId = threadId,
                    )
                    showTransientStatus("Skipped automatic file write")
                }
            }
            return
        }

        viewModelScope.launch {
            runCatching {
                val result = JSONObject().put("decision", if (accept) "accept" else "decline")
                rpcClient.respond(approval.requestIdRaw, result)
                _uiState.update { it.copy(activeApproval = null) }
                syncForegroundService()
            }.onFailure { error ->
                setError(error.message ?: "Failed to send approval response")
            }
        }
    }

    override fun onCleared() {
        onClearedFromUi()
        super.onCleared()
    }

    // ---- Offline Thread Helpers ----

    private fun restoreInitialOfflineConversation() {
        val activeId = _uiState.value.activeThreadId
        val restored = activeId?.let { offlineThreadsById[it] }
        if (restored != null && !archivedThreadIds.contains(restored.id)) {
            publishOfflineThread(restored)
        } else {
            showNewConversation()
        }
    }

    private fun ensureOfflineThreadId(initialTitleHint: String): String {
        val currentId = _uiState.value.activeThreadId
        if (!currentId.isNullOrBlank() && offlineThreadsById.containsKey(currentId)) {
            return currentId
        }
        val newId = "offline-${System.currentTimeMillis()}"
        val derivedTitle = customTitleFor(newId)
            ?: initialTitleHint.lineSequence().firstOrNull()?.trim()?.take(42)?.ifBlank { null }
            ?: "New Chat"
        val record = OfflineThreadRecord(
            id = newId,
            title = derivedTitle,
            updatedAtEpochSeconds = Instant.now().epochSecond,
            messages = emptyList(),
        )
        offlineThreadsById[newId] = record
        persistOfflineThreads()
        publishMergedHistory()
        publishOfflineThread(record)
        return newId
    }

    private fun appendOfflineMessage(threadId: String, message: OfflineMessageRecord) {
        val existing = offlineThreadsById[threadId] ?: return
        val updatedTitle = if (existing.title == "New Chat" && message.role == "user" && message.text.isNotBlank()) {
            message.text.lineSequence().firstOrNull()?.trim()?.take(42).orEmpty().ifBlank { "New Chat" }
        } else {
            existing.title
        }
        val updated = existing.copy(
            title = customTitleFor(threadId) ?: updatedTitle,
            updatedAtEpochSeconds = Instant.now().epochSecond,
            messages = existing.messages + message,
        )
        offlineThreadsById[threadId] = updated
        persistOfflineThreads()
        publishMergedHistory()
        if (_uiState.value.activeThreadId == threadId || _uiState.value.activeThreadId == null) {
            publishOfflineThread(updated)
        }
    }

    private fun appendOfflineFileChangeMessage(
        summary: String,
        output: String,
        status: String,
        threadId: String? = _uiState.value.activeThreadId,
    ) {
        val resolvedId = threadId?.takeIf { offlineThreadsById.containsKey(it) }
            ?: ensureOfflineThreadId(initialTitleHint = "Workspace Files")
        appendOfflineMessage(
            resolvedId,
            OfflineMessageRecord(
                id = "file-${System.currentTimeMillis()}-${(100..999).random()}",
                role = "fileChange",
                text = output,
                summary = summary,
                status = status,
            ),
        )
    }

    private fun updateOfflineMessageText(
        threadId: String,
        messageId: String,
        text: String,
        status: String,
    ) {
        val existing = offlineThreadsById[threadId] ?: return
        val updatedMessages = existing.messages.map { msg ->
            if (msg.id == messageId) msg.copy(text = text, status = status) else msg
        }
        val updated = existing.copy(
            updatedAtEpochSeconds = Instant.now().epochSecond,
            messages = updatedMessages,
        )
        offlineThreadsById[threadId] = updated
        if (status != "inProgress") {
            persistOfflineThreads()
            publishMergedHistory()
        }
        if (_uiState.value.activeThreadId == threadId) {
            publishOfflineThread(updated)
        }
    }

    private fun updateOfflineMessageStatus(
        threadId: String,
        messageId: String,
        status: String,
    ) {
        val existing = offlineThreadsById[threadId] ?: return
        val updatedMessages = existing.messages.map { msg ->
            if (msg.id == messageId) msg.copy(status = status) else msg
        }
        val updated = existing.copy(
            updatedAtEpochSeconds = Instant.now().epochSecond,
            messages = updatedMessages,
        )
        offlineThreadsById[threadId] = updated
        persistOfflineThreads()
        if (_uiState.value.activeThreadId == threadId) {
            publishOfflineThread(updated)
        }
    }

    private fun publishOfflineThread(thread: OfflineThreadRecord) {
        val uiItems = thread.messages.mapIndexed { idx, record ->
            val sortKey = idx.toString().padStart(5, '0')
            when (record.role) {
                "user" -> ChatItemUi.User(
                    id = record.id,
                    sortKey = sortKey,
                    text = record.text,
                    attachments = record.attachments,
                )
                "fileChange" -> ChatItemUi.FileChange(
                    id = record.id,
                    sortKey = sortKey,
                    summary = record.summary.ifBlank { "Folder Write" },
                    output = record.text,
                    status = record.status,
                )
                "command" -> ChatItemUi.Command(
                    id = record.id,
                    sortKey = sortKey,
                    command = record.summary,
                    output = record.text,
                    status = record.status,
                )
                else -> if (record.text.isBlank() && record.status == "inProgress") {
                    ChatItemUi.AgentPlaceholder(
                        id = record.id,
                        sortKey = sortKey,
                        text = "Thinking offline...",
                    )
                } else {
                    ChatItemUi.Agent(
                        id = record.id,
                        sortKey = sortKey,
                        text = record.text,
                        status = record.status,
                    )
                }
            }
        }
        val title = customTitleFor(thread.id) ?: thread.title
        persistThreadSelection(thread.id, title)
        _uiState.update { state ->
            state.copy(
                activeThreadId = thread.id,
                activeThreadTitle = title,
                messages = uiItems,
            )
        }
    }

    private fun persistOfflineThreads() {
        offlineStore.saveAll(offlineThreadsById.values.toList())
    }

    private fun updateOfflineBanner() {
        if (_uiState.value.engineMode != EngineMode.OFFLINE_GGUF) return
        val gguf = _uiState.value.gguf
        val folder = _uiState.value.workspace
        val banner = when {
            gguf.isLoading -> ConnectionBanner(
                code = "starting",
                title = "Loading GGUF Model",
                detail = "Preparing on-device inference engine...",
                tone = BannerTone.INFO,
            )
            gguf.loadError.isNotBlank() -> ConnectionBanner(
                code = "error",
                title = "Model Load Failed",
                detail = gguf.loadError,
                tone = BannerTone.DANGER,
            )
            !gguf.isLoaded -> ConnectionBanner(
                code = "model_missing",
                title = "Select a GGUF Model",
                detail = if (folder.folderUri.isBlank()) {
                    "Pick a local .gguf model and an output folder to start."
                } else {
                    "Output folder '${folder.folderName}' is ready. Pick a .gguf model file."
                },
                tone = BannerTone.INFO,
            )
            folder.folderUri.isBlank() -> ConnectionBanner(
                code = "connected",
                title = "Offline GGUF Ready",
                detail = "Model ${gguf.modelName} loaded. Select a folder to write files directly.",
                tone = BannerTone.OK,
            )
            else -> ConnectionBanner(
                code = "connected",
                title = "Offline GGUF + Folder Ready",
                detail = "${gguf.modelName} · Folder: ${folder.folderName}",
                tone = BannerTone.OK,
            )
        }
        _uiState.update { it.copy(connection = banner) }
    }

    // ---- Optional CLI Backend Flows ----

    private suspend fun startCliTurn(
        prepared: PreparedSubmission,
        targetThreadId: String? = null,
        localDraftId: String? = null,
        clearDraft: Boolean = true,
    ) {
        setError("")
        val normalizedTargetThreadId = targetThreadId?.takeUnless(::isLocalConversationId)
        val conversationKey = normalizedTargetThreadId ?: currentDraftThreadId()
        val attachedDraftId = localDraftId ?: appendLocalDraft(
            conversationKey,
            displayText = prepared.displayText,
            transportText = prepared.transportText,
            attachments = prepared.attachments,
        ).id
        val pendingTurn = registerPendingTurn(
            localDraftId = attachedDraftId,
            prompt = prepared.transportText,
            conversationKey = conversationKey,
            clearDraft = clearDraft,
        )

        try {
            ensureConnected()
            val threadId = ensureCliThreadForChat(normalizedTargetThreadId)
            moveLocalDraftToThread(attachedDraftId, threadId)
            bindPendingTurnToThread(pendingTurn.requestId, threadId)
            lastUserTextByThread[threadId] = prepared.displayText.trim()
            val response = performTurnStartWithResumeFallback(threadId, prepared) as? JSONObject
            markPendingTurnRunning(
                requestId = pendingTurn.requestId,
                threadId = threadId,
                turnId = response?.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotBlank() },
            )
            syncForegroundService()
        } catch (error: Throwable) {
            failPendingTurn(pendingTurn.requestId)
            throw error
        }
    }

    private suspend fun recoverAndConnect(
        forceReconnect: Boolean = false,
        manual: Boolean = false,
    ) {
        if (_uiState.value.engineMode == EngineMode.OFFLINE_GGUF) return
        reconnectJob?.cancel()
        reconnectJob = null

        reconnectMutex.withLock {
            try {
                setError("")
                if (!rpcClient.isOpen() || forceReconnect || manual) {
                    setConnection(
                        code = if (forceReconnect || manual) "reconnecting" else "starting",
                        title = if (forceReconnect || manual) "Reconnecting" else "Checking Local Runtime",
                        detail = "Checking embedded Codex runtime, backend listener, and credentials.",
                        tone = BannerTone.INFO,
                    )
                }

                runtimeController.reapplyHardeningIfEnabled()
                var latestBackend = runtimeController.getBackendStatus().toStatusSnapshot()
                applyBackendStatus(latestBackend)
                refreshSupplementalState()

                if (latestBackend.runtimeReady && latestBackend.authPresent && !latestBackend.backendListening) {
                    latestBackend = runtimeController.ensureBackendRunning().toStatusSnapshot()
                    applyBackendStatus(latestBackend)
                    refreshSupplementalState()
                }

                if (!latestBackend.backendListening) {
                    _uiState.update { state ->
                        state.copy(connection = deriveCliConnectionBanner(latestBackend, state.connection.code))
                    }
                    syncForegroundService()
                    return
                }

                if (!forceReconnect && rpcClient.isOpen()) {
                    _uiState.update { state ->
                        state.copy(
                            status = state.status.copy(backendListening = true, authPresent = true),
                            connection = ConnectionBanner(
                                code = "connected",
                                title = "Codex Backend Connected",
                                detail = "Connected to local app-server over WebSocket.",
                                tone = BannerTone.OK,
                            ),
                        )
                    }
                    syncForegroundService()
                    return
                }

                connectRpc(forceReconnect)
            } catch (error: Throwable) {
                if (error is CancellationException || error.isInternalCancellation()) return
                setError(error.message ?: "Connection failed")
            }
        }
    }

    private fun refreshSupplementalState() {
        viewModelScope.launch {
            runCatching {
                runtimeController.getKeepaliveStatus().toKeepaliveSnapshot()
            }.onSuccess { keepalive ->
                _uiState.update { state -> state.copy(keepalive = keepalive) }
            }
            if (_uiState.value.engineMode == EngineMode.CLI_BACKEND) {
                runCatching {
                    runtimeController.getLocalThreadIndex()
                }.onSuccess { localHistory ->
                    mergeLocalHistory(localHistory)
                }
            }
        }
    }

    private suspend fun connectRpc(forceReconnect: Boolean) {
        if (forceReconnect) {
            resumedThreadIds.clear()
            rpcClient.close(notifyClosure = false)
        }
        if (rpcClient.isOpen()) return

        setConnection(
            code = if (forceReconnect) "reconnecting" else "starting",
            title = "Connecting to Local Codex",
            detail = "Establishing local WebSocket connection to app-server.",
            tone = BannerTone.INFO,
        )

        rpcClient.connect(
            url = RPC_URL,
            onRequest = ::handleRpcRequest,
            onNotification = ::handleRpcNotification,
            onClosed = { reason ->
                resumedThreadIds.clear()
                if (_uiState.value.engineMode == EngineMode.CLI_BACKEND) {
                    _uiState.update { state ->
                        state.copy(
                            connection = deriveCliConnectionBanner(state.status, "reconnecting").copy(
                                code = "reconnecting",
                                title = "Connection Closed",
                                detail = reason ?: "Local WebSocket closed; attempting to reconnect.",
                                tone = BannerTone.WARN,
                            ),
                        )
                    }
                    scheduleReconnect()
                }
            },
        )

        val initResult = rpcClient.request(
            method = "initialize",
            params = JSONObject()
                .put("clientInfo", JSONObject().put("name", "Codex Mobile").put("version", "0.4.0"))
                .put("capabilities", JSONObject().put("experimentalApi", true)),
        ) as? JSONObject

        loadRemoteData()

        _uiState.update { state ->
            state.copy(
                status = state.status.copy(backendListening = true, authPresent = true),
                connection = ConnectionBanner(
                    code = "connected",
                    title = "Codex Backend Connected",
                    detail = initResult?.optString("userAgent").orEmpty().ifBlank {
                        "Local Codex CLI app-server is ready."
                    },
                    tone = BannerTone.OK,
                ),
            )
        }
        syncForegroundService()
    }

    private suspend fun ensureConnected() {
        if (!rpcClient.isOpen()) recoverAndConnect(forceReconnect = true)
        if (!rpcClient.isOpen()) error("Local backend is still disconnected")
    }

    private suspend fun loadRemoteData() {
        val modelResponse = rpcClient.request("model/list", JSONObject()) as? JSONObject
        val threadResponse = rpcClient.request("thread/list", JSONObject().put("limit", THREAD_LIMIT)) as? JSONObject
        val models = CliBackendJsonParser.parseModels(modelResponse?.optJSONArray("data"))
        val remoteThreads = parseThreadSummaries(threadResponse?.optJSONArray("data"))
        mergeRemoteHistory(remoteThreads)

        val preferredModel = chooseBestModel(models, _uiState.value.selectedModel)
        val preferredReasoning = chooseReasoning(models, preferredModel, _uiState.value.selectedReasoning)

        _uiState.update { state ->
            state.copy(
                availableModels = models,
                selectedModel = preferredModel,
                selectedReasoning = preferredReasoning,
            )
        }
        persistPreferences()
    }

    private suspend fun handleRpcRequest(id: Any, method: String, params: JSONObject) {
        when (method) {
            "item/commandExecution/requestApproval" -> handleCliApprovalRequest(
                id = id,
                kind = "command",
                title = "Approve Command Execution",
                subtitle = params.optString("command").ifBlank { "Command details" },
                body = buildCliApprovalBody(params, params.optString("command")),
            )
            "item/fileChange/requestApproval" -> handleCliApprovalRequest(
                id = id,
                kind = "file",
                title = "Approve File Change",
                subtitle = "The assistant wants to write file changes.",
                body = buildCliApprovalBody(params, params.optString("summary")),
            )
            else -> rpcClient.respond(id, JSONObject())
        }
    }

    private suspend fun handleCliApprovalRequest(
        id: Any,
        kind: String,
        title: String,
        subtitle: String,
        body: String,
    ) {
        when (_uiState.value.permissionMode) {
            PermissionMode.FULL_ACCESS -> rpcClient.respond(id, JSONObject().put("decision", "accept"))
            PermissionMode.DEFAULT_DENY -> rpcClient.respond(id, JSONObject().put("decision", "decline"))
            PermissionMode.ASK_EACH_TIME -> {
                _uiState.update { state ->
                    state.copy(
                        activeApproval = ApprovalRequestUi(
                            requestId = id.toString(),
                            requestIdRaw = id,
                            title = title,
                            subtitle = subtitle,
                            body = body,
                            kind = kind,
                        ),
                        activeTab = BottomDestination.CHAT,
                    )
                }
                syncForegroundService()
            }
        }
    }

    private suspend fun handleRpcNotification(method: String, params: JSONObject) {
        when (method) {
            "turn/started" -> {
                val turnId = params.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: params.optString("turnId").takeIf { it.isNotBlank() }
                val threadId = params.optString("threadId").takeIf { it.isNotBlank() }
                noteTurnStarted(turnId, threadId)
            }
            "turn/completed" -> {
                val turnId = params.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: params.optString("turnId").takeIf { it.isNotBlank() }
                val threadId = params.optString("threadId").takeIf { it.isNotBlank() }
                noteTurnFinished(turnId, threadId)
            }
            "item/started", "item/completed" -> {
                val threadId = params.optString("threadId")
                val turnId = params.optString("turnId")
                val item = params.optJSONObject("item") ?: return
                upsertCliTurnItem(threadId, turnId, item)
            }
            "item/agentMessage/delta" -> updateCliTurnDelta(
                threadId = params.optString("threadId"),
                turnId = params.optString("turnId"),
                itemId = params.optString("itemId"),
                type = "agentMessage",
                delta = params.optString("delta"),
            )
            "serverRequest/resolved" -> {
                _uiState.update { it.copy(activeApproval = null) }
                syncForegroundService()
            }
        }
    }

    private fun scheduleReconnect() {
        if (_uiState.value.engineMode != EngineMode.CLI_BACKEND) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(1_500L)
            recoverAndConnect(forceReconnect = true)
        }
    }

    private suspend fun loadCliThreadIntoChat(
        threadId: String,
        switchToChat: Boolean = false,
        showStatusHint: Boolean = false,
    ) {
        if (showStatusHint) setStatus("Loading conversation...")
        ensureConnected()
        val response = rpcClient.request(
            "thread/read",
            JSONObject().put("threadId", threadId).put("includeTurns", true),
        ) as? JSONObject ?: error("Empty thread response")
        val threadJson = response.optJSONObject("thread") ?: error("Missing thread payload")
        activeCliThread = parseCliThread(threadJson)
        resetTurnTracking(activeCliThread?.runningTurnIds().orEmpty())
        publishActiveCliThread()
        if (switchToChat) _uiState.update { it.copy(activeTab = BottomDestination.CHAT) }
        clearStatus()
        syncForegroundService()
    }

    private suspend fun ensureCliThreadForChat(preferredThreadId: String? = null): String {
        val existing = preferredThreadId
            ?: activeCliThread?.id
            ?: _uiState.value.activeThreadId?.takeUnless(::isLocalConversationId)
        if (!existing.isNullOrBlank()) return existing

        val response = rpcClient.request(
            method = "thread/start",
            params = JSONObject()
                .put("model", _uiState.value.selectedModel)
                .put("modelProvider", "openai")
                .put("cwd", runtimeController.defaultWorkingDirectory())
                .put("approvalPolicy", _uiState.value.permissionMode.approvalPolicy)
                .put("sandbox", _uiState.value.permissionMode.sandboxCliValue)
                .put("persistExtendedHistory", true),
        ) as? JSONObject ?: error("Failed to create thread")
        val threadJson = response.optJSONObject("thread") ?: error("Missing thread payload")
        activeCliThread = parseCliThread(threadJson)
        resumedThreadIds += activeCliThread!!.id
        publishActiveCliThread()
        mergeRemoteHistory(parseThreadSummaries(JSONArray().put(threadJson)))
        return activeCliThread!!.id
    }

    private suspend fun performTurnStartWithResumeFallback(threadId: String, prepared: PreparedSubmission): Any? =
        rpcClient.request(
            method = "turn/start",
            params = JSONObject()
                .put("threadId", threadId)
                .put("input", prepared.input)
                .put("model", _uiState.value.selectedModel)
                .put("modelReasoningEffort", _uiState.value.selectedReasoning)
                .put("approvalPolicy", _uiState.value.permissionMode.approvalPolicy)
                .put("sandboxPolicy", JSONObject().put("type", _uiState.value.permissionMode.sandboxPolicyType))
                .put("cwd", runtimeController.defaultWorkingDirectory()),
        )

    private fun showNewConversation(localConversationId: String = newLocalConversationId()) {
        _uiState.value.composer.attachments.forEach(::scheduleAttachmentDeletion)
        activeCliThread = null
        resetTurnTracking()
        persistThreadSelection(
            activeThreadId = localConversationId,
            activeThreadTitle = "New Chat",
            rememberLastOpened = false,
        )
        _uiState.update { state ->
            state.copy(
                activeThreadId = localConversationId,
                activeThreadTitle = "New Chat",
                messages = emptyList(),
                composer = ChatComposerState(),
                activeApproval = null,
            )
        }
        setError("")
        clearStatus()
        syncForegroundService()
    }

    private fun applyBackendStatus(status: StatusSnapshot) {
        val effectiveStatus = if (rpcClient.isOpen()) {
            status.copy(
                backendListening = true,
                authPresent = true,
                loginInProgress = false,
                backendStatusDetail = "app-server is listening on 127.0.0.1:8765.",
            )
        } else {
            status
        }
        _uiState.update { state ->
            state.copy(
                status = effectiveStatus,
                connection = if (state.engineMode == EngineMode.CLI_BACKEND) {
                    deriveCliConnectionBanner(effectiveStatus, state.connection.code)
                } else {
                    state.connection
                },
            )
        }
    }

    private fun beginLoginPolling() {
        loginPollJob?.cancel()
        loginPollJob = viewModelScope.launch {
            repeat(180) {
                delay(2_000L)
                val latestBackend = runtimeController.checkLoginStatus().toStatusSnapshot()
                if (latestBackend.authPresent) {
                    applyBackendStatus(latestBackend.copy(loginInProgress = false))
                    updateDeviceLoginInfo(code = "", url = "", hint = "")
                    showTransientStatus("Signed in; restoring local backend")
                    recoverAndConnect(forceReconnect = true, manual = true)
                    loginPollJob = null
                    return@launch
                }
            }
            _uiState.update { state ->
                state.copy(status = state.status.copy(loginInProgress = false))
            }
            setError("Device code login timed out. Please try again.")
            loginPollJob = null
        }
    }

    private fun updateDeviceLoginInfo(code: String, url: String, hint: String) {
        _uiState.update { state ->
            state.copy(
                deviceAuthCode = code.ifBlank { "" },
                deviceAuthUrl = url.ifBlank { "" },
                loginHint = hint.ifBlank { state.loginHint.takeUnless { code.isBlank() && url.isBlank() } ?: "" },
            )
        }
    }

    private fun mergeLocalHistory(index: JSONArray) {
        localHistoryById.clear()
        for (i in 0 until index.length()) {
            val item = index.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { continue }
            val updatedAt = item.optEpochSeconds("updated_at", "updatedAt")
            localHistoryById[id] = applyCustomTitle(
                ThreadSummary(
                    id = id,
                    title = CliBackendJsonParser.displayThreadName(item.optString("thread_name"), item.optString("preview")),
                    updatedAtLabel = formatEpoch(updatedAt),
                    updatedAtEpochSeconds = updatedAt,
                    source = "cli-local",
                ),
            )
        }
        publishMergedHistory()
    }

    private fun mergeRemoteHistory(items: List<ThreadSummary>) {
        items.forEach { summary ->
            remoteHistoryById[summary.id] = applyCustomTitle(summary)
        }
        publishMergedHistory()
    }

    private fun allMergedHistory(): List<ThreadSummary> {
        val merged = linkedMapOf<String, ThreadSummary>()
        offlineThreadsById.values.forEach { thread ->
            merged[thread.id] = applyCustomTitle(
                ThreadSummary(
                    id = thread.id,
                    title = thread.title,
                    updatedAtLabel = formatEpoch(thread.updatedAtEpochSeconds),
                    updatedAtEpochSeconds = thread.updatedAtEpochSeconds,
                    source = "Offline GGUF",
                ),
            )
        }
        if (_uiState.value.engineMode == EngineMode.CLI_BACKEND) {
            localHistoryById.values.forEach { merged[it.id] = applyCustomTitle(it) }
            remoteHistoryById.values.forEach { merged[it.id] = applyCustomTitle(it) }
        }
        return merged.values.sortedByDescending { it.updatedAtEpochSeconds ?: 0L }
    }

    private fun publishMergedHistory() {
        val merged = allMergedHistory().filterNot { deletedThreadIds.contains(it.id) }
        _uiState.update { state ->
            state.copy(
                historyThreads = merged.filterNot { archivedThreadIds.contains(it.id) },
                archivedThreads = merged.filter { archivedThreadIds.contains(it.id) },
            )
        }
    }

    private fun publishActiveCliThread() {
        val thread = activeCliThread
        val items = thread?.flattenToUiItems().orEmpty()
        if (thread != null) {
            persistThreadSelection(thread.id, thread.displayName)
        }
        _uiState.update { state ->
            state.copy(
                activeThreadId = thread?.id,
                activeThreadTitle = thread?.displayName ?: "New Chat",
                messages = items,
            )
        }
        syncForegroundService()
    }

    private fun upsertCliTurnItem(threadId: String, turnId: String, item: JSONObject) {
        val parsed = CliBackendJsonParser.parseItem(item, persistedMessageAttachments[item.optString("id")]?.attachments)
        val thread = activeCliThread ?: return
        if (thread.id != threadId) return
        val turn = thread.ensureTurn(turnId)
        turn.upsert(parsed)
        publishActiveCliThread()
    }

    private fun updateCliTurnDelta(
        threadId: String,
        turnId: String,
        itemId: String,
        type: String,
        delta: String,
    ) {
        val thread = activeCliThread ?: return
        if (thread.id != threadId) return
        val turn = thread.ensureTurn(turnId)
        val current = turn.find(itemId) ?: CliMutableItem(id = itemId, type = type).also { turn.upsert(it) }
        current.type = type
        current.primaryText += delta
        publishActiveCliThread()
    }

    private fun parseThreadSummaries(array: JSONArray?): List<ThreadSummary> {
        if (array == null) return emptyList()
        val items = mutableListOf<ThreadSummary>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { continue }
            val updatedAt = item.optEpochSeconds("updatedAt", "updated_at")
            items += applyCustomTitle(
                ThreadSummary(
                    id = id,
                    title = CliBackendJsonParser.displayThreadName(
                        item.optString("name"),
                        item.optString("summary").ifBlank { item.optString("preview") },
                    ),
                    updatedAtLabel = formatEpoch(updatedAt),
                    updatedAtEpochSeconds = updatedAt,
                    source = "cli-remote",
                ),
            )
        }
        return items
    }

    private fun parseCliThread(threadJson: JSONObject): CliMutableThread {
        val thread = CliMutableThread(
            id = threadJson.optString("id").ifBlank { error("Missing thread id") },
            name = threadJson.optStringOrNull("name"),
            preview = threadJson.optStringOrNull("summary") ?: threadJson.optStringOrNull("preview"),
            cwd = threadJson.optStringOrNull("cwd"),
            status = threadJson.optString("status").ifBlank { "idle" },
            updatedAtEpochSeconds = threadJson.optEpochSeconds("updatedAt", "updated_at"),
            customTitle = customTitleFor(threadJson.optString("id")),
        )
        val turns = threadJson.optJSONArray("turns") ?: JSONArray()
        for (turnIndex in 0 until turns.length()) {
            val turnJson = turns.optJSONObject(turnIndex) ?: continue
            val turn = CliMutableTurn(
                id = turnJson.optString("id").ifBlank { "turn-$turnIndex" },
                status = turnJson.optString("status").ifBlank { "completed" },
            )
            val items = turnJson.optJSONArray("items") ?: JSONArray()
            for (itemIndex in 0 until items.length()) {
                val itemJson = items.optJSONObject(itemIndex) ?: continue
                turn.upsert(CliBackendJsonParser.parseItem(itemJson))
            }
            thread.turns += turn
        }
        return thread
    }

    private fun buildCliApprovalBody(params: JSONObject, primary: String): String {
        val lines = mutableListOf<String>()
        primary.takeIf { it.isNotBlank() }?.let { lines += it }
        params.optStringOrNull("cwd")?.let { lines += "Folder: $it" }
        CliBackendJsonParser.summarizeChanges(params.optJSONArray("changes")).takeIf { it.isNotBlank() }?.let { lines += it }
        params.optStringOrNull("summary")?.takeIf { it != primary }?.let { lines += it }
        return lines.joinToString("\n\n").ifBlank { "Confirmation required." }
    }

    private fun buildTextInput(text: String): JSONArray =
        JSONArray().put(buildTextInputBlock(text))

    private fun buildTextInputBlock(text: String): JSONObject =
        JSONObject()
            .put("type", "text")
            .put("text", text.trim())
            .put("text_elements", JSONArray())

    private fun buildPreparedSubmission(
        displayText: String,
        attachments: List<ChatAttachmentUi>,
    ): PreparedSubmission {
        val normalizedDisplayText = displayText.trim()
        if (attachments.isEmpty()) {
            return PreparedSubmission(
                displayText = normalizedDisplayText,
                transportText = normalizedDisplayText,
                input = buildTextInput(normalizedDisplayText),
                attachments = emptyList(),
            )
        }

        val documentTexts = attachments
            .filter { it.kind == AttachmentKind.DOCUMENT }
            .map { attachment ->
                attachmentProcessor.buildDocumentTransportText(
                    displayText = "",
                    attachment = attachment,
                    extractedText = attachmentProcessor.readExtractedAttachmentText(attachment),
                )
            }
        val hasImages = attachments.any { it.kind == AttachmentKind.IMAGE }
        val transportText = buildString {
            if (normalizedDisplayText.isNotBlank()) {
                append(normalizedDisplayText)
            } else if (hasImages) {
                append(CliBackendJsonParser.IMAGE_ONLY_PROMPT)
            }
            if (documentTexts.isNotEmpty()) {
                if (isNotBlank()) append("\n\n")
                append(documentTexts.joinToString("\n\n"))
            }
        }.trim()
        val input = JSONArray()
        if (transportText.isNotBlank()) input.put(buildTextInputBlock(transportText))
        attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { attachment ->
            attachment.backendPath?.let { path ->
                input.put(JSONObject().put("type", "localImage").put("path", path))
            }
        }
        return PreparedSubmission(
            displayText = normalizedDisplayText.ifBlank { attachments.joinToString(", ") { it.displayName } },
            transportText = transportText,
            input = input,
            attachments = attachments,
        )
    }

    private fun rebuildPreparedSubmission(draft: LocalDraftMessage): PreparedSubmission =
        PreparedSubmission(
            displayText = draft.text,
            transportText = draft.transportText,
            input = buildTextInput(draft.transportText),
            attachments = draft.attachments,
        )

    private fun scheduleAttachmentDeletion(attachment: ChatAttachmentUi?) {
        if (attachment == null) return
        viewModelScope.launch {
            attachmentProcessor.deleteAttachmentFiles(attachment)
        }
    }

    private fun appendLocalDraft(
        threadId: String,
        displayText: String,
        transportText: String,
        attachments: List<ChatAttachmentUi>,
    ): LocalDraftMessage {
        val draft = LocalDraftMessage(
            id = "local-${System.currentTimeMillis()}-${(1000..9999).random()}",
            threadId = threadId,
            text = displayText.trim(),
            transportText = transportText.trim(),
            status = LocalMessageStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            attachments = attachments,
        )
        localDraftsByThread.getOrPut(threadId) { mutableListOf() }.add(draft)
        return draft
    }

    private fun findLocalDraft(id: String): LocalDraftMessage? =
        localDraftsByThread.values.asSequence().flatten().firstOrNull { it.id == id }

    private fun currentDraftThreadId(): String =
        activeCliThread?.id ?: _uiState.value.activeThreadId?.takeIf(::isLocalConversationId) ?: LOCAL_DRAFT_THREAD_ID

    private fun isLocalConversationId(id: String?): Boolean {
        val value = id.orEmpty()
        return value == LOCAL_DRAFT_THREAD_ID || value.startsWith(LOCAL_DRAFT_THREAD_PREFIX)
    }

    private fun newLocalConversationId(): String = "$LOCAL_DRAFT_THREAD_PREFIX${System.currentTimeMillis()}"

    private fun registerPendingTurn(
        localDraftId: String,
        prompt: String,
        conversationKey: String,
        clearDraft: Boolean,
    ): PendingTurn {
        val pending = PendingTurn(
            requestId = "turn-${System.currentTimeMillis()}-${(1000..9999).random()}",
            localDraftId = localDraftId,
            prompt = prompt,
            conversationKey = conversationKey,
            createdAt = System.currentTimeMillis(),
        )
        pendingTurns[pending.requestId] = pending
        refreshComposerActivity(clearText = clearDraft)
        return pending
    }

    private fun bindPendingTurnToThread(requestId: String, threadId: String) {
        val existing = pendingTurns[requestId] ?: return
        pendingTurns[requestId] = existing.copy(threadId = threadId)
    }

    private fun markPendingTurnRunning(
        requestId: String? = null,
        threadId: String? = null,
        turnId: String? = null,
    ) {
        turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds += it }
        requestId?.let { id ->
            pendingTurns[id]?.let { pendingTurns[id] = it.copy(threadId = threadId ?: it.threadId, turnId = turnId ?: it.turnId) }
        }
        refreshComposerActivity()
    }

    private fun failPendingTurn(requestId: String) {
        val removed = pendingTurns.remove(requestId) ?: return
        pendingTurnJobs.remove(requestId)?.cancel()
        removed.turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds.remove(it) }
        updateLocalDraftStatus(removed.localDraftId, LocalMessageStatus.FAILED)
        refreshComposerActivity()
    }

    private fun moveLocalDraftToThread(id: String, threadId: String) {
        localDraftsByThread.values.forEach { drafts ->
            drafts.replaceAll { if (it.id == id) it.copy(threadId = threadId) else it }
        }
    }

    private fun updateLocalDraftStatus(id: String, status: LocalMessageStatus) {
        localDraftsByThread.forEach { (_, drafts) ->
            drafts.replaceAll { if (it.id == id) it.copy(status = status) else it }
        }
    }

    private fun loadArchivedThreadIds(): Set<String> {
        val encoded = prefs.getString(PREF_ARCHIVED_THREAD_IDS, null).orEmpty()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun loadDeletedThreadIds(): Set<String> {
        val encoded = prefs.getString(PREF_DELETED_THREAD_IDS, null).orEmpty()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun loadCustomThreadTitles(): Map<String, String> {
        val encoded = prefs.getString(PREF_CUSTOM_THREAD_TITLES, null).orEmpty()
        val payload = runCatching { JSONObject(encoded) }.getOrNull() ?: return emptyMap()
        return buildMap {
            payload.keys().forEach { key ->
                CliBackendJsonParser.normalizeDisplayText(payload.optString(key))
                    ?.takeIf { key.isNotBlank() }
                    ?.let { put(key, it) }
            }
        }
    }

    private fun persistArchivedThreadIds() {
        val payload = JSONArray()
        archivedThreadIds.sorted().forEach(payload::put)
        prefs.edit().putString(PREF_ARCHIVED_THREAD_IDS, payload.toString()).apply()
    }

    private fun persistDeletedThreadIds() {
        val payload = JSONArray()
        deletedThreadIds.sorted().forEach(payload::put)
        prefs.edit().putString(PREF_DELETED_THREAD_IDS, payload.toString()).apply()
    }

    private fun persistCustomThreadTitles() {
        val payload = JSONObject()
        customThreadTitles.toSortedMap().forEach { (threadId, title) ->
            CliBackendJsonParser.normalizeDisplayText(title)?.let { payload.put(threadId, it) }
        }
        prefs.edit().putString(PREF_CUSTOM_THREAD_TITLES, payload.toString()).apply()
    }

    private fun hideArchivedThread(threadId: String) {
        if (threadId.isBlank()) return
        archivedThreadIds += threadId
        persistArchivedThreadIds()
        if (activeCliThread?.id == threadId) activeCliThread = null
    }

    private fun showArchivedThread(threadId: String) {
        if (threadId.isBlank()) return
        archivedThreadIds.remove(threadId)
        persistArchivedThreadIds()
    }

    private fun customTitleFor(threadId: String?): String? =
        threadId?.takeIf { it.isNotBlank() }?.let(customThreadTitles::get)?.let(CliBackendJsonParser::normalizeDisplayText)

    private fun applyCustomTitle(summary: ThreadSummary): ThreadSummary =
        customTitleFor(summary.id)?.let { summary.copy(title = it) } ?: summary

    private fun loadInitialState(): CodexMobileUiState {
        val engineMode = EngineMode.entries.firstOrNull {
            it.name == prefs.getString(PREF_ENGINE_MODE, EngineMode.OFFLINE_GGUF.name)
        } ?: EngineMode.OFFLINE_GGUF
        val model = prefs.getString(PREF_SELECTED_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        val reasoning = prefs.getString(PREF_SELECTED_REASONING, DEFAULT_REASONING).orEmpty().ifBlank { DEFAULT_REASONING }
        val activeThreadId = prefs.getString(PREF_ACTIVE_THREAD_ID, null).orEmpty().ifBlank { null }
        val activeThreadTitle = customTitleFor(activeThreadId)
            ?: prefs.getString(PREF_ACTIVE_THREAD_TITLE, null).orEmpty().ifBlank { "New Chat" }
        val fastModeEnabled = prefs.getBoolean(PREF_FAST_MODE_ENABLED, false)
        val permissionMode = PermissionMode.entries.firstOrNull {
            it.name == prefs.getString(PREF_PERMISSION_MODE, PermissionMode.FULL_ACCESS.name)
        } ?: PermissionMode.FULL_ACCESS
        val fontScale = FontScaleOption.entries.firstOrNull {
            it.name == prefs.getString(PREF_FONT_SCALE, FontScaleOption.NORMAL.name)
        } ?: FontScaleOption.NORMAL

        val ggufState = GgufModelState(
            modelUri = ggufEngine.getSavedModelUri()?.toString().orEmpty(),
            modelName = ggufEngine.getSavedModelName(),
            modelSizeBytes = ggufEngine.getSavedModelSizeBytes(),
            isLoaded = ggufEngine.isModelLoaded(),
            contextLength = prefs.getInt(PREF_GGUF_CTX, 4096),
            threads = prefs.getInt(PREF_GGUF_THREADS, LocalGgufEngine.recommendedThreads()),
            temperature = prefs.getFloat(PREF_GGUF_TEMP, 0.35f),
            maxTokens = prefs.getInt(PREF_GGUF_MAX_TOKENS, 1536),
        )

        val folderState = WorkspaceFolderUiState(
            folderUri = workspaceFileManager.getSavedTreeUri()?.toString().orEmpty(),
            folderName = workspaceFileManager.getSavedFolderDisplayName(),
        )

        return CodexMobileUiState(
            engineMode = engineMode,
            gguf = ggufState,
            workspace = folderState,
            selectedModel = model,
            selectedReasoning = reasoning,
            fastModeEnabled = fastModeEnabled,
            permissionMode = permissionMode,
            fontScale = fontScale,
            activeThreadId = activeThreadId,
            activeThreadTitle = if (activeThreadId == null) "New Chat" else activeThreadTitle,
        )
    }

    private fun persistPreferences() {
        val state = _uiState.value
        prefs.edit()
            .putString(PREF_ENGINE_MODE, state.engineMode.name)
            .putString(PREF_SELECTED_MODEL, state.selectedModel)
            .putString(PREF_SELECTED_REASONING, state.selectedReasoning)
            .putBoolean(PREF_FAST_MODE_ENABLED, state.fastModeEnabled)
            .putString(PREF_PERMISSION_MODE, state.permissionMode.name)
            .putString(PREF_FONT_SCALE, state.fontScale.name)
            .apply()
    }

    private fun persistThreadSelection(
        activeThreadId: String?,
        activeThreadTitle: String?,
        rememberLastOpened: Boolean = !activeThreadId.isNullOrBlank(),
    ) {
        prefs.edit().apply {
            putString(PREF_ACTIVE_THREAD_ID, activeThreadId)
            putString(PREF_ACTIVE_THREAD_TITLE, activeThreadTitle ?: "New Chat")
            if (rememberLastOpened && !activeThreadId.isNullOrBlank() && !isLocalConversationId(activeThreadId)) {
                putString(PREF_LAST_OPENED_THREAD_ID, activeThreadId)
                putString(PREF_LAST_OPENED_THREAD_TITLE, activeThreadTitle ?: "New Chat")
            }
        }.apply()
    }

    private fun chooseBestModel(models: List<ModelOption>, current: String): String {
        if (models.isEmpty()) return current.ifBlank { DEFAULT_MODEL }
        if (models.any { it.id == current }) return current
        return models.firstOrNull { it.id == DEFAULT_MODEL }?.id ?: models.first().id
    }

    private fun chooseReasoning(models: List<ModelOption>, modelId: String, current: String): String {
        val model = models.firstOrNull { it.id == modelId } ?: return current
        return if (model.reasoningOptions.any { it.value == current }) current else model.defaultReasoning
    }

    private fun deriveCliConnectionBanner(status: StatusSnapshot, previousCode: String): ConnectionBanner {
        return when {
            status.loginInProgress -> ConnectionBanner(
                code = "login_in_progress",
                title = "Signing In",
                detail = "Browser OAuth opened; waiting for local credentials.",
                tone = BannerTone.INFO,
            )
            !status.runtimeReady -> ConnectionBanner(
                code = "runtime_missing",
                title = "CLI Runtime Missing",
                detail = status.backendStatusDetail.ifBlank { "Embedded Codex CLI runtime is not packaged in this APK." },
                tone = BannerTone.DANGER,
            )
            !status.authPresent -> ConnectionBanner(
                code = "auth_missing",
                title = "Sign-In Required",
                detail = status.backendStatusDetail.ifBlank { "Complete sign-in before starting CLI sessions." },
                tone = BannerTone.WARN,
            )
            !status.backendListening -> ConnectionBanner(
                code = "backend_missing",
                title = "Backend Not Listening",
                detail = status.backendStatusDetail.ifBlank { "Starting local codex app-server." },
                tone = BannerTone.WARN,
            )
            previousCode == "reconnecting" -> ConnectionBanner(
                code = "connected",
                title = "Reconnected",
                detail = "Local Codex CLI backend is ready.",
                tone = BannerTone.OK,
            )
            else -> ConnectionBanner(
                code = "connected",
                title = "Codex Backend Connected",
                detail = status.backendStatusDetail.ifBlank { "Connected to local Codex CLI backend." },
                tone = BannerTone.OK,
            )
        }
    }

    private fun formatEpoch(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "Just now"
        return runCatching {
            TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))
        }.getOrElse { "Just now" }
    }

    private fun setConnection(code: String, title: String, detail: String, tone: BannerTone) {
        _uiState.update {
            it.copy(connection = ConnectionBanner(code = code, title = title, detail = detail, tone = tone))
        }
        syncForegroundService()
    }

    private fun refreshComposerActivity(clearText: Boolean = false) {
        val sending = runningTurnIds.isNotEmpty() || pendingTurns.isNotEmpty() || offlineGenerationJob?.isActive == true
        _uiState.update { state ->
            state.copy(
                composer = state.composer.copy(
                    sending = sending,
                    canInterrupt = latestRunningTurnId() != null || offlineGenerationJob?.isActive == true,
                    text = if (clearText) "" else state.composer.text,
                    attachments = if (clearText) emptyList() else state.composer.attachments,
                ),
            )
        }
        syncForegroundService()
    }

    private fun noteTurnStarted(turnId: String?, threadId: String? = activeCliThread?.id) {
        markPendingTurnRunning(threadId = threadId, turnId = turnId)
    }

    private fun noteTurnFinished(turnId: String?, threadId: String? = activeCliThread?.id) {
        turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds.remove(it) }
        pendingTurns.keys.toList().forEach { key ->
            val p = pendingTurns[key]
            if (p != null && (p.turnId == turnId || (turnId == null && p.threadId == threadId))) {
                pendingTurns.remove(key)
            }
        }
        refreshComposerActivity()
    }

    private fun resetTurnTracking(activeTurnIds: Collection<String> = emptyList()) {
        runningTurnIds.clear()
        runningTurnIds += activeTurnIds.filter { it.isNotBlank() }
        refreshComposerActivity()
    }

    private fun latestRunningTurnId(): String? = runningTurnIds.lastOrNull()

    private fun setStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun withUiLocales(url: String, locale: String): String {
        val uri = Uri.parse(url)
        if (uri.getQueryParameter("ui_locales") == locale) return url
        return uri.buildUpon().appendQueryParameter("ui_locales", locale).build().toString()
    }

    private fun showTransientStatus(message: String, durationMs: Long = 2_400L) {
        transientStatusJob?.cancel()
        setStatus(message)
        transientStatusJob = viewModelScope.launch {
            delay(durationMs)
            if (_uiState.value.statusMessage == message) clearStatus()
        }
    }

    private fun clearStatus() {
        transientStatusJob?.cancel()
        transientStatusJob = null
        _uiState.update { it.copy(statusMessage = "") }
    }

    private fun setError(message: String) {
        _uiState.update { state ->
            state.copy(errorMessage = message, statusMessage = if (message.isBlank()) state.statusMessage else "")
        }
    }

    private fun syncForegroundService() {
        viewModelScope.launch {
            runCatching {
                val active = offlineGenerationJob?.isActive == true ||
                    _uiState.value.gguf.isLoading ||
                    (_uiState.value.engineMode == EngineMode.CLI_BACKEND &&
                        (_uiState.value.connection.code == "connected" || runningTurnIds.isNotEmpty()))
                runtimeController.setForegroundSessionActive(active)
            }
        }
    }

    private suspend fun refreshActiveThreadSnapshot() {
        val activeId = _uiState.value.activeThreadId ?: return
        if (isLocalConversationId(activeId) || archivedThreadIds.contains(activeId)) return
        runCatching { loadCliThreadIntoChat(activeId) }
    }

    private fun Throwable.isInternalCancellation(): Boolean {
        val msg = message.orEmpty()
        return this is CancellationException ||
            msg.contains("StandaloneCoroutine was cancelled", ignoreCase = true) ||
            msg.contains("socket closed", ignoreCase = true)
    }

    private fun JSONObject.toStatusSnapshot(): StatusSnapshot = StatusSnapshot(
        rootAvailable = optBoolean("rootAvailable"),
        runtimeReady = optBoolean("runtimeReady"),
        runtimePackaged = optBoolean("runtimePackaged"),
        runtimeVersion = optString("runtimeVersion"),
        runtimeAbi = optString("runtimeAbi").ifBlank { "arm64-v8a" },
        backendListening = optBoolean("backendListening"),
        authPresent = optBoolean("authPresent"),
        authMode = optString("authMode"),
        accountId = optString("accountId"),
        loginInProgress = optBoolean("loginInProgress"),
        autoHardeningEnabled = optBoolean("autoHardeningEnabled", true),
        backendStatusDetail = optString("backendStatusDetail"),
    )

    private fun JSONObject.toKeepaliveSnapshot(): KeepaliveSnapshot = KeepaliveSnapshot(
        deviceIdleWhitelisted = optBoolean("deviceIdleWhitelisted"),
        restrictBackgroundWhitelisted = optBoolean("restrictBackgroundWhitelisted"),
        standbyBucket = optString("standbyBucket").ifBlank { "unknown" },
        batteryOptimizationIgnoredForUiApp = optBoolean("batteryOptimizationIgnoredForUiApp"),
    )

    private data class LocalDraftMessage(
        val id: String,
        val threadId: String,
        val text: String,
        val transportText: String,
        val status: LocalMessageStatus,
        val createdAt: Long,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    )

    private data class PreparedSubmission(
        val displayText: String,
        val transportText: String,
        val input: JSONArray,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    )

    private data class PersistedMessageAttachments(
        val itemId: String,
        val threadId: String,
        val attachments: List<ChatAttachmentUi>,
    )

    private data class PendingTurn(
        val requestId: String,
        val localDraftId: String,
        val prompt: String,
        val conversationKey: String,
        val threadId: String? = null,
        val turnId: String? = null,
        val createdAt: Long,
    )

    companion object {
        private const val PREFS_NAME = "codex_mobile_ui"
        private const val PREF_ENGINE_MODE = "engine_mode"
        private const val PREF_SELECTED_MODEL = "selected_model"
        private const val PREF_SELECTED_REASONING = "selected_reasoning"
        private const val PREF_FAST_MODE_ENABLED = "fast_mode_enabled"
        private const val PREF_PERMISSION_MODE = "permission_mode"
        private const val PREF_FONT_SCALE = "font_scale"
        private const val PREF_ACTIVE_THREAD_ID = "active_thread_id"
        private const val PREF_ACTIVE_THREAD_TITLE = "active_thread_title"
        private const val PREF_LAST_OPENED_THREAD_ID = "last_opened_thread_id"
        private const val PREF_LAST_OPENED_THREAD_TITLE = "last_opened_thread_title"
        private const val PREF_ARCHIVED_THREAD_IDS = "archived_thread_ids"
        private const val PREF_DELETED_THREAD_IDS = "deleted_thread_ids"
        private const val PREF_CUSTOM_THREAD_TITLES = "custom_thread_titles"
        private const val PREF_GGUF_CTX = "gguf_context_length"
        private const val PREF_GGUF_THREADS = "gguf_threads"
        private const val PREF_GGUF_TEMP = "gguf_temperature"
        private const val PREF_GGUF_MAX_TOKENS = "gguf_max_tokens"

        private const val LOCAL_DRAFT_THREAD_ID = "__local_new_thread__"
        private const val LOCAL_DRAFT_THREAD_PREFIX = "__local_new_thread__-"
        private const val DEFAULT_MODEL = "gpt-5.4"
        private const val DEFAULT_REASONING = "xhigh"
        private const val MAX_ATTACHMENTS = 4
        private const val THREAD_LIMIT = 60
        private const val RPC_URL = "ws://127.0.0.1:8765"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("MMM d, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault())
    }
}
