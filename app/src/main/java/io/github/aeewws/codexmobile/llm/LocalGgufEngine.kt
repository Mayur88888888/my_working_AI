package io.github.aeewws.codexmobile.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.util.Locale

data class GgufModelInfo(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val contextLength: Int,
    val threads: Int,
)

data class ChatPromptMessage(
    val role: String,
    val content: String,
)

class LocalGgufEngine(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val llama = LlamaAndroid(context.contentResolver)
    private val mutex = Mutex()

    @Volatile
    private var loadedContextId: Int? = null

    @Volatile
    private var loadedModelInfo: GgufModelInfo? = null

    @Volatile
    private var activeTokenListener: ((String) -> Unit)? = null

    @Volatile
    private var generating: Boolean = false

    fun getSavedModelUri(): Uri? =
        prefs.getString(KEY_MODEL_URI, null)?.takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }

    fun getSavedModelName(): String =
        prefs.getString(KEY_MODEL_NAME, null).orEmpty()

    fun getSavedModelSizeBytes(): Long =
        prefs.getLong(KEY_MODEL_SIZE, 0L)

    fun getLoadedModelInfo(): GgufModelInfo? = loadedModelInfo

    fun isModelLoaded(): Boolean = loadedContextId != null

    fun isGenerating(): Boolean = generating

    suspend fun loadModel(
        uri: Uri,
        contextLength: Int,
        threads: Int,
        takePersistablePermission: Boolean = true,
    ): Result<GgufModelInfo> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (takePersistablePermission) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }

                if (!llama.isGGUF(uri)) {
                    error("The selected file is not a valid GGUF model.")
                }

                releaseCurrentContextLocked()

                val (displayName, sizeBytes) = queryModelMetadata(uri)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Cannot open the selected GGUF file.")
                val fd = pfd.detachFd()

                val params = mapOf<String, Any>(
                    "model" to uri.toString(),
                    "model_fd" to fd,
                    "use_mmap" to false,
                    "use_mlock" to false,
                    "n_ctx" to contextLength.coerceIn(512, 16384),
                    "embedding" to false,
                    "n_batch" to 512,
                    "n_threads" to threads.coerceAtLeast(0),
                    "n_gpu_layers" to 0,
                    "vocab_only" to false,
                    "lora" to "",
                    "lora_scaled" to 1.0,
                    "rope_freq_base" to 0.0,
                    "rope_freq_scale" to 0.0,
                )

                val result = llama.startEngine(params) { token ->
                    activeTokenListener?.invoke(token)
                }

                val errorMessage = result?.get("error") as? String
                if (!errorMessage.isNullOrBlank()) {
                    error(errorMessage)
                }

                val contextId = (result?.get("contextId") as? Number)?.toInt()
                    ?: error("llama.cpp did not return a context ID.")

                val info = GgufModelInfo(
                    uriString = uri.toString(),
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    contextLength = contextLength,
                    threads = threads,
                )
                loadedContextId = contextId
                loadedModelInfo = info

                prefs.edit()
                    .putString(KEY_MODEL_URI, info.uriString)
                    .putString(KEY_MODEL_NAME, info.displayName)
                    .putLong(KEY_MODEL_SIZE, info.sizeBytes)
                    .apply()

                info
            }
        }
    }

    suspend fun generate(
        messages: List<ChatPromptMessage>,
        temperature: Double,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val contextId = loadedContextId
            ?: return@withContext Result.failure(IllegalStateException("No GGUF model is loaded. Pick a .gguf file first."))

        if (generating) {
            return@withContext Result.failure(IllegalStateException("A generation is already running."))
        }

        generating = true
        val streamed = StringBuilder()
        activeTokenListener = { token ->
            streamed.append(token)
            onToken(streamed.toString())
        }

        try {
            val formattedPrompt = formatMessages(contextId, messages)
            val completionParams = mapOf<String, Any>(
                "prompt" to formattedPrompt,
                "temperature" to temperature.coerceIn(0.0, 2.0),
                "n_predict" to maxTokens.coerceIn(64, 8192),
                "top_k" to 40,
                "top_p" to 0.92,
                "min_p" to 0.05,
                "penalty_repeat" to 1.08,
                "emit_partial_completion" to true,
                "stop" to DEFAULT_STOP_TOKENS,
            )

            val result = llama.launchCompletion(contextId, completionParams)
                ?: return@withContext Result.failure(IllegalStateException("Local GGUF inference failed."))

            val finalText = (result["text"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: streamed.toString()

            Result.success(cleanStopTokens(finalText))
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            activeTokenListener = null
            generating = false
        }
    }

    suspend fun stopGeneration() {
        val contextId = loadedContextId ?: return
        runCatching {
            llama.stopCompletion(contextId)
        }
    }

    suspend fun unloadModel() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                releaseCurrentContextLocked()
            }
        }
    }

    fun clearSavedModel() {
        val existing = getSavedModelUri()
        if (existing != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    existing,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        prefs.edit()
            .remove(KEY_MODEL_URI)
            .remove(KEY_MODEL_NAME)
            .remove(KEY_MODEL_SIZE)
            .apply()
    }

    private suspend fun formatMessages(
        contextId: Int,
        messages: List<ChatPromptMessage>,
    ): String {
        val payload = messages.map { msg ->
            mapOf<String, Any>(
                "role" to msg.role,
                "content" to msg.content,
            )
        }
        val formatted = runCatching {
            llama.getFormattedChat(contextId, payload, "").firstOrNull()
        }.getOrNull()

        if (!formatted.isNullOrBlank()) {
            return formatted
        }

        // Fallback to ChatML if GGUF metadata does not include a chat template.
        return buildString {
            messages.forEach { msg ->
                append("<|im_start|>")
                append(msg.role)
                append('\n')
                append(msg.content)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    private fun releaseCurrentContextLocked() {
        val existing = loadedContextId ?: return
        loadedContextId = null
        loadedModelInfo = null
        runCatching {
            llama.releaseContext(existing)
        }
    }

    private fun queryModelMetadata(uri: Uri): Pair<String, Long> {
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "model.gguf"
        var sizeBytes = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                    }
                }
            }
        }
        return displayName to sizeBytes
    }

    private fun cleanStopTokens(raw: String): String {
        var output = raw
        DEFAULT_STOP_TOKENS.forEach { token ->
            if (output.endsWith(token)) {
                output = output.removeSuffix(token)
            }
        }
        return output.trim()
    }

    companion object {
        private const val PREFS_NAME = "codex_mobile_gguf"
        private const val KEY_MODEL_URI = "gguf_model_uri"
        private const val KEY_MODEL_NAME = "gguf_model_name"
        private const val KEY_MODEL_SIZE = "gguf_model_size"

        private val DEFAULT_STOP_TOKENS = listOf(
            "<|im_end|>",
            "<|eot_id|>",
            "</s>",
            "<end_of_turn>",
            "<|end|>",
        )

        fun formatByteSize(bytes: Long): String {
            if (bytes <= 0L) return "Unknown size"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }

        fun recommendedThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            return (cores / 2).coerceIn(2, 6)
        }
    }
}
