package io.github.aeewws.codexmobile.ui.app

import io.github.aeewws.codexmobile.ui.app.AttachmentProcessor.Companion.DOCUMENT_CONTENT_MARKER
import io.github.aeewws.codexmobile.ui.app.AttachmentProcessor.Companion.DOCUMENT_PREFIX
import io.github.aeewws.codexmobile.ui.app.AttachmentProcessor.Companion.DOCUMENT_SUFFIX
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class CliMutableThread(
    val id: String,
    val name: String?,
    val preview: String?,
    val cwd: String?,
    val status: String,
    val updatedAtEpochSeconds: Long?,
    val customTitle: String? = null,
    val turns: MutableList<CliMutableTurn> = mutableListOf(),
) {
    val displayName: String
        get() = customTitle ?: CliBackendJsonParser.displayThreadName(name, preview)

    fun ensureTurn(turnId: String): CliMutableTurn {
        val existing = turns.firstOrNull { it.id == turnId }
        if (existing != null) return existing
        return CliMutableTurn(id = turnId).also { turns += it }
    }

    fun currentRunningTurnId(): String? =
        turns.lastOrNull { it.status.contains("progress", ignoreCase = true) }?.id

    fun runningTurnIds(): List<String> =
        turns.filter { it.isRunning() }.map { it.id }

    fun flattenToUiItems(): List<ChatItemUi> {
        val uiItems = mutableListOf<ChatItemUi>()
        turns.forEachIndexed { turnIndex, turn ->
            turn.items.forEachIndexed { itemIndex, item ->
                uiItems += item.toUi(
                    sortKey = "${turnIndex.toString().padStart(4, '0')}-${itemIndex.toString().padStart(4, '0')}",
                    turnStatus = turn.status,
                )
            }
        }
        return uiItems
    }
}

internal data class CliMutableTurn(
    val id: String,
    var status: String = "inProgress",
    val items: MutableList<CliMutableItem> = mutableListOf(),
) {
    fun isRunning(): Boolean {
        val value = status.trim()
        return value.equals("inProgress", ignoreCase = true) ||
            value.contains("progress", ignoreCase = true) ||
            value.equals("running", ignoreCase = true)
    }

    fun upsert(item: CliMutableItem) {
        val existing = items.indexOfFirst { it.id == item.id }
        if (existing >= 0) items[existing] = item else items += item
    }

    fun find(itemId: String): CliMutableItem? = items.firstOrNull { it.id == itemId }
}

internal data class CliMutableItem(
    val id: String,
    var type: String,
    var primaryText: String = "",
    var secondaryText: String = "",
    var status: String = "",
    var command: String = "",
    var cwd: String = "",
    var attachments: List<ChatAttachmentUi> = emptyList(),
) {
    fun toUi(sortKey: String, turnStatus: String): ChatItemUi = when (type) {
        "userMessage" -> ChatItemUi.User(
            id = id,
            sortKey = sortKey,
            text = primaryText,
            attachments = attachments,
        )
        "agentMessage" -> ChatItemUi.Agent(
            id = id,
            sortKey = sortKey,
            text = primaryText.ifBlank { "Generating response..." },
            status = status.ifBlank { turnStatus },
        )
        "reasoning" -> ChatItemUi.Reasoning(
            id = id,
            sortKey = sortKey,
            text = primaryText.ifBlank { "Generating reasoning summary..." },
            status = status.ifBlank { turnStatus },
        )
        "commandExecution" -> ChatItemUi.Command(
            id = id,
            sortKey = sortKey,
            command = command.ifBlank { "pending" },
            output = primaryText,
            status = status.ifBlank { turnStatus },
        )
        "fileChange" -> ChatItemUi.FileChange(
            id = id,
            sortKey = sortKey,
            summary = secondaryText.ifBlank { "File change" },
            output = primaryText,
            status = status.ifBlank { turnStatus },
        )
        "plan" -> ChatItemUi.Plan(
            id = id,
            sortKey = sortKey,
            text = primaryText,
        )
        else -> ChatItemUi.Agent(
            id = id,
            sortKey = sortKey,
            text = primaryText.ifBlank { "Received an unrecognized backend event." },
            status = type,
        )
    }
}

internal object CliBackendJsonParser {
    const val IMAGE_ONLY_PROMPT = "Please review this attached image."

    fun displayThreadName(name: String?, preview: String?): String {
        return normalizeDisplayText(name)
            ?: normalizeDisplayText(preview)?.lineSequence()?.firstOrNull()?.let(::normalizeDisplayText)
            ?: "New Chat"
    }

    fun normalizeDisplayText(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val cleaned = run {
            val firstDocumentStart = text.indexOf(DOCUMENT_PREFIX)
            when {
                firstDocumentStart >= 0 -> text.substring(0, firstDocumentStart).trim()
                text.contains(DOCUMENT_CONTENT_MARKER) -> text.substringBefore(DOCUMENT_CONTENT_MARKER).trim()
                else -> text
            }
        }
        if (cleaned.isBlank()) return null
        return when (cleaned.lowercase()) {
            "null", "undefined" -> null
            else -> cleaned
        }
    }

    fun defaultReasoningOptions(): List<ReasoningEffort> = listOf(
        ReasoningEffort("low", "Low"),
        ReasoningEffort("medium", "Medium"),
        ReasoningEffort("high", "High"),
        ReasoningEffort("xhigh", "Max"),
    )

    fun parseModels(array: JSONArray?): List<ModelOption> {
        if (array == null) return emptyList()
        val models = mutableListOf<ModelOption>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("model").ifBlank {
                item.optString("slug").ifBlank { item.optString("id") }
            }
            if (id.isBlank()) continue
            val displayName = item.optString("displayName").ifBlank {
                item.optString("display_name").ifBlank { id }
            }
            val rawOptions = item.optJSONArray("supportedReasoningLevels")
                ?: item.optJSONArray("supported_reasoning_levels")
                ?: item.optJSONArray("reasoningEfforts")
            val reasoningOptions = parseReasoningOptions(rawOptions)
            val defaultReasoning = item.optString("defaultReasoningLevel").ifBlank {
                item.optString("default_reasoning_level").ifBlank {
                    reasoningOptions.firstOrNull()?.value ?: "xhigh"
                }
            }
            models += ModelOption(
                id = id,
                displayName = displayName,
                reasoningOptions = if (reasoningOptions.isEmpty()) defaultReasoningOptions() else reasoningOptions,
                defaultReasoning = defaultReasoning,
            )
        }
        return models
    }

    private fun parseReasoningOptions(array: JSONArray?): List<ReasoningEffort> {
        if (array == null) return emptyList()
        val options = mutableListOf<ReasoningEffort>()
        for (index in 0 until array.length()) {
            when (val entry = array.opt(index)) {
                is JSONObject -> {
                    val effort = entry.optString("effort").ifBlank { continue }
                    options += ReasoningEffort(effort, reasoningLabel(effort))
                }
                is String -> {
                    val effort = entry.ifBlank { continue }
                    options += ReasoningEffort(effort, reasoningLabel(effort))
                }
            }
        }
        return options.distinctBy { it.value }
    }

    fun parseItem(
        item: JSONObject,
        persistedAttachments: List<ChatAttachmentUi>? = null,
    ): CliMutableItem {
        val type = item.optString("type").ifBlank { "unknown" }
        return when (type) {
            "userMessage" -> {
                val itemId = item.optString("id").ifBlank { "user-${System.nanoTime()}" }
                val rawText = extractText(item.optJSONArray("content"))
                val attachments = persistedAttachments
                    ?: parseAttachmentsFromUserContent(item.optJSONArray("content"))
                    ?: parseDocumentAttachmentsFromText(rawText)
                    ?: emptyList()
                CliMutableItem(
                    id = itemId,
                    type = type,
                    primaryText = normalizeUserDisplayText(rawText, attachments),
                    attachments = attachments,
                )
            }
            "agentMessage" -> CliMutableItem(
                id = item.optString("id").ifBlank { "agent-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("text").ifBlank { extractText(item.optJSONArray("content")) },
                status = item.optString("phase").ifBlank { item.optString("status") },
            )
            "reasoning" -> CliMutableItem(
                id = item.optString("id").ifBlank { "reasoning-${System.nanoTime()}" },
                type = type,
                primaryText = extractText(item.optJSONArray("summary"))
                    .ifBlank { extractText(item.optJSONArray("content")) },
                status = item.optString("status"),
            )
            "commandExecution" -> CliMutableItem(
                id = item.optString("id").ifBlank { "command-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("aggregatedOutput"),
                status = item.optString("status"),
                command = item.optString("command"),
                cwd = item.optString("cwd"),
            )
            "fileChange" -> CliMutableItem(
                id = item.optString("id").ifBlank { "file-${System.nanoTime()}" },
                type = type,
                primaryText = buildFileDiffText(item.optJSONArray("changes")),
                secondaryText = summarizeChanges(item.optJSONArray("changes")),
                status = item.optString("status"),
            )
            "plan" -> CliMutableItem(
                id = item.optString("id").ifBlank { "plan-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("text").ifBlank {
                    buildPlanText(item.optString("explanation"), item.optJSONArray("plan"))
                },
            )
            else -> CliMutableItem(
                id = item.optString("id").ifBlank { "item-${System.nanoTime()}" },
                type = type,
                primaryText = item.toString(2),
                status = item.optString("status"),
            )
        }
    }

    fun extractText(array: JSONArray?): String {
        if (array == null) return ""
        val pieces = mutableListOf<String>()
        for (index in 0 until array.length()) {
            when (val entry = array.opt(index)) {
                is JSONObject -> {
                    val entryType = entry.optString("type")
                    val text = when {
                        entryType.equals("text", ignoreCase = true) -> entry.optString("text")
                        entry.has("text") -> entry.optString("text")
                        entry.has("content") && entry.opt("content") is String -> entry.optString("content")
                        else -> ""
                    }
                    if (text.isNotBlank()) pieces += text
                }
                is String -> if (entry.isNotBlank()) pieces += entry
            }
        }
        return pieces.joinToString("\n").trim()
    }

    fun summarizeChanges(changes: JSONArray?): String {
        if (changes == null || changes.length() == 0) return ""
        val lines = mutableListOf<String>()
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val kind = change.optString("kind").ifBlank { "edit" }
            val path = change.optString("path").ifBlank { "unknown" }
            lines += "$kind $path"
        }
        return lines.joinToString("\n")
    }

    fun buildFileDiffText(changes: JSONArray?): String {
        if (changes == null || changes.length() == 0) return ""
        val chunks = mutableListOf<String>()
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val kind = change.optString("kind").ifBlank { "edit" }
            val path = change.optString("path").ifBlank { "unknown" }
            val diff = change.optString("diff")
            chunks += buildString {
                append("$kind: $path")
                if (diff.isNotBlank()) {
                    append("\n")
                    append(diff)
                }
            }
        }
        return chunks.joinToString("\n\n").trim()
    }

    fun buildPlanText(explanation: String, plan: JSONArray?): String {
        val lines = mutableListOf<String>()
        explanation.takeIf { it.isNotBlank() }?.let { lines += it }
        if (plan != null) {
            for (index in 0 until plan.length()) {
                val step = plan.optJSONObject(index) ?: continue
                val label = step.optString("step").ifBlank { continue }
                val status = step.optString("status").ifBlank { "pending" }
                lines += "[$status] $label"
            }
        }
        return lines.joinToString("\n").ifBlank { "Plan update received." }
    }

    fun parseAttachmentsFromUserContent(content: JSONArray?): List<ChatAttachmentUi>? {
        if (content == null) return null
        val items = mutableListOf<ChatAttachmentUi>()
        for (index in 0 until content.length()) {
            val entry = content.optJSONObject(index) ?: continue
            val type = entry.optString("type")
            if (type.equals("localImage", ignoreCase = true)) {
                val path = entry.optString("path").ifBlank { continue }
                items += ChatAttachmentUi(
                    kind = AttachmentKind.IMAGE,
                    displayName = File(path).name.ifBlank { "Image" },
                    mimeType = resolveMimeTypeFromPath(path),
                    previewPath = path,
                    backendPath = path,
                )
            }
        }
        return items.ifEmpty { null }
    }

    fun parseDocumentAttachmentsFromText(rawText: String): List<ChatAttachmentUi>? {
        val matches = Regex(
            "${Regex.escape(DOCUMENT_PREFIX)}(.*?)${Regex.escape(DOCUMENT_SUFFIX)}",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(rawText)
        val attachments = matches.mapNotNull { match ->
            val json = match.groupValues.getOrNull(1).orEmpty().trim()
            runCatching { JSONArray(json) }.getOrNull()?.let(::jsonArrayToAttachments)
                ?: runCatching { JSONObject(json) }.getOrNull()?.let { payload ->
                    val name = payload.optString("name").ifBlank { return@let null }
                    val mime = payload.optString("mime").ifBlank { "text/plain" }
                    listOf(
                        ChatAttachmentUi(
                            kind = AttachmentKind.DOCUMENT,
                            displayName = name,
                            mimeType = mime,
                        ),
                    )
                }
        }.flatten().toList()
        return attachments.ifEmpty { null }
    }

    fun normalizeUserDisplayText(rawText: String, attachments: List<ChatAttachmentUi>): String {
        if (attachments.isEmpty()) return rawText.trim()
        if (attachments.all { it.kind == AttachmentKind.IMAGE }) {
            val normalized = rawText.trim()
            return if (normalized == IMAGE_ONLY_PROMPT) "" else normalized
        }
        if (attachments.none { it.kind == AttachmentKind.DOCUMENT }) return rawText.trim()
        val firstDocumentStart = rawText.indexOf(DOCUMENT_PREFIX)
        return when {
            firstDocumentStart >= 0 -> rawText.substring(0, firstDocumentStart).trim()
            rawText.contains(DOCUMENT_CONTENT_MARKER) -> rawText.substringBefore(DOCUMENT_CONTENT_MARKER).trim()
            else -> rawText.trim()
        }
    }

    private fun resolveMimeTypeFromPath(path: String): String =
        when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    fun jsonArrayToAttachments(array: JSONArray): List<ChatAttachmentUi> =
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val kind = runCatching { AttachmentKind.valueOf(obj.optString("kind")) }.getOrNull() ?: continue
                val displayName = obj.optString("displayName").ifBlank { continue }
                add(
                    ChatAttachmentUi(
                        kind = kind,
                        displayName = displayName,
                        mimeType = obj.optString("mimeType").ifBlank { "application/octet-stream" },
                        previewPath = obj.optString("previewPath").ifBlank { null },
                        backendPath = obj.optString("backendPath").ifBlank { null },
                        extractedTextPath = obj.optString("extractedTextPath").ifBlank { null },
                    ),
                )
            }
        }
}
