package io.github.aeewws.codexmobile.llm

import android.content.Context
import io.github.aeewws.codexmobile.ui.app.AttachmentKind
import io.github.aeewws.codexmobile.ui.app.ChatAttachmentUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OfflineMessageRecord(
    val id: String,
    val role: String, // "user", "assistant", "fileChange", "command"
    val text: String,
    val summary: String = "",
    val status: String = "completed",
    val attachments: List<ChatAttachmentUi> = emptyList(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

data class OfflineThreadRecord(
    val id: String,
    val title: String,
    val updatedAtEpochSeconds: Long,
    val messages: List<OfflineMessageRecord> = emptyList(),
)

class OfflineConversationStore(
    context: Context,
) {
    private val storageFile = File(context.filesDir, "offline_gguf_threads.json")

    fun loadAll(): List<OfflineThreadRecord> {
        if (!storageFile.exists()) return emptyList()
        val raw = runCatching { storageFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val threads = mutableListOf<OfflineThreadRecord>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { continue }
            val title = obj.optString("title").ifBlank { "New Chat" }
            val updatedAt = obj.optLong("updatedAtEpochSeconds", 0L)
            val messagesJson = obj.optJSONArray("messages") ?: JSONArray()
            val messages = mutableListOf<OfflineMessageRecord>()
            for (j in 0 until messagesJson.length()) {
                val msgObj = messagesJson.optJSONObject(j) ?: continue
                val msgId = msgObj.optString("id").ifBlank { continue }
                val role = msgObj.optString("role").ifBlank { continue }
                val text = msgObj.optString("text")
                val summary = msgObj.optString("summary")
                val status = msgObj.optString("status").ifBlank { "completed" }
                val created = msgObj.optLong("createdAtEpochMillis", 0L)
                val attachments = parseAttachments(msgObj.optJSONArray("attachments"))
                messages += OfflineMessageRecord(
                    id = msgId,
                    role = role,
                    text = text,
                    summary = summary,
                    status = status,
                    attachments = attachments,
                    createdAtEpochMillis = created,
                )
            }
            threads += OfflineThreadRecord(
                id = id,
                title = title,
                updatedAtEpochSeconds = updatedAt,
                messages = messages,
            )
        }
        return threads.sortedByDescending { it.updatedAtEpochSeconds }
    }

    fun saveAll(threads: List<OfflineThreadRecord>) {
        val payload = JSONArray()
        threads.sortedByDescending { it.updatedAtEpochSeconds }.forEach { thread ->
            val messagesArray = JSONArray()
            thread.messages.forEach { msg ->
                val msgObj = JSONObject()
                    .put("id", msg.id)
                    .put("role", msg.role)
                    .put("text", msg.text)
                    .put("summary", msg.summary)
                    .put("status", msg.status)
                    .put("createdAtEpochMillis", msg.createdAtEpochMillis)
                if (msg.attachments.isNotEmpty()) {
                    msgObj.put("attachments", attachmentsToJson(msg.attachments))
                }
                messagesArray.put(msgObj)
            }
            payload.put(
                JSONObject()
                    .put("id", thread.id)
                    .put("title", thread.title)
                    .put("updatedAtEpochSeconds", thread.updatedAtEpochSeconds)
                    .put("messages", messagesArray),
            )
        }
        runCatching {
            storageFile.writeText(payload.toString(), Charsets.UTF_8)
        }
    }

    private fun attachmentsToJson(attachments: List<ChatAttachmentUi>): JSONArray =
        JSONArray().also { array ->
            attachments.forEach { item ->
                array.put(
                    JSONObject()
                        .put("kind", item.kind.name)
                        .put("displayName", item.displayName)
                        .put("mimeType", item.mimeType)
                        .put("previewPath", item.previewPath)
                        .put("extractedTextPath", item.extractedTextPath),
                )
            }
        }

    private fun parseAttachments(array: JSONArray?): List<ChatAttachmentUi> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val kind = runCatching { AttachmentKind.valueOf(obj.optString("kind")) }.getOrNull() ?: continue
                val displayName = obj.optString("displayName").ifBlank { continue }
                add(
                    ChatAttachmentUi(
                        kind = kind,
                        displayName = displayName,
                        mimeType = obj.optString("mimeType").ifBlank { "application/octet-stream" },
                        previewPath = obj.optString("previewPath").ifBlank { null },
                        extractedTextPath = obj.optString("extractedTextPath").ifBlank { null },
                    ),
                )
            }
        }
    }
}
