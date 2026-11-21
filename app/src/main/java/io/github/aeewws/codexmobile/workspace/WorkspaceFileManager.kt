package io.github.aeewws.codexmobile.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale

data class WorkspaceFileEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

data class WorkspaceFolderSnapshot(
    val treeUri: String,
    val displayName: String,
    val canWrite: Boolean,
    val files: List<WorkspaceFileEntry>,
)

data class WorkspaceWriteResult(
    val relativePath: String,
    val created: Boolean,
    val bytesWritten: Int,
)

class WorkspaceFileManager(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedTreeUri(): Uri? {
        val raw = prefs.getString(KEY_TREE_URI, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun getSavedFolderDisplayName(): String =
        prefs.getString(KEY_FOLDER_NAME, null).orEmpty()

    suspend fun selectFolder(uri: Uri): Result<WorkspaceFolderSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: error("Unable to open the selected folder.")
            if (!root.exists() || !root.isDirectory) {
                error("Selected path is not a readable directory.")
            }
            val displayName = root.name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                ?: "Selected Folder"
            prefs.edit()
                .putString(KEY_TREE_URI, uri.toString())
                .putString(KEY_FOLDER_NAME, displayName)
                .apply()
            buildSnapshot(uri, root, displayName)
        }
    }

    suspend fun refreshFolder(): Result<WorkspaceFolderSnapshot?> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = getSavedTreeUri() ?: return@runCatching null
            val root = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching null
            if (!root.exists() || !root.isDirectory) {
                return@runCatching null
            }
            val displayName = root.name?.takeIf { it.isNotBlank() }
                ?: getSavedFolderDisplayName().ifBlank { "Selected Folder" }
            buildSnapshot(uri, root, displayName)
        }
    }

    fun clearSelectedFolder() {
        val existing = getSavedTreeUri()
        if (existing != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    existing,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit()
            .remove(KEY_TREE_URI)
            .remove(KEY_FOLDER_NAME)
            .apply()
    }

    suspend fun writeFile(relativePath: String, content: String): Result<WorkspaceWriteResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safePath = sanitizeRelativePath(relativePath)
                val treeUri = getSavedTreeUri()
                    ?: error("No output folder selected. Pick a folder first.")
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("Selected folder is no longer accessible.")
                if (!root.canWrite()) {
                    error("Selected folder does not allow write access.")
                }

                val segments = safePath.split('/')
                val fileName = segments.last()
                var currentDir = root
                for (i in 0 until segments.lastIndex) {
                    val dirName = segments[i]
                    val existingDir = currentDir.findFile(dirName)
                    currentDir = when {
                        existingDir != null && existingDir.isDirectory -> existingDir
                        existingDir != null && !existingDir.isDirectory ->
                            error("Cannot create directory '$dirName'; a file with that name already exists.")
                        else -> currentDir.createDirectory(dirName)
                            ?: error("Failed to create subfolder '$dirName'.")
                    }
                }

                val existingFile = currentDir.findFile(fileName)
                val created: Boolean
                val targetFile = if (existingFile != null) {
                    if (existingFile.isDirectory) {
                        error("Target '$safePath' is a directory, not a file.")
                    }
                    created = false
                    existingFile
                } else {
                    created = true
                    currentDir.createFile(mimeTypeForCreate(fileName), fileName)
                        ?: error("Failed to create '$safePath' in the selected folder.")
                }

                val bytes = content.toByteArray(Charsets.UTF_8)
                writeBytesTruncating(targetFile.uri, bytes)
                WorkspaceWriteResult(
                    relativePath = safePath,
                    created = created,
                    bytesWritten = bytes.size,
                )
            }
        }

    suspend fun readFile(relativePath: String, maxChars: Int = MAX_READ_CHARS): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safePath = sanitizeRelativePath(relativePath)
                val target = resolveExistingFile(safePath)
                    ?: error("File not found in selected folder: $safePath")
                if (target.isDirectory) {
                    error("'$safePath' is a directory.")
                }
                context.contentResolver.openInputStream(target.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val text = reader.readText()
                    if (text.length > maxChars) {
                        text.take(maxChars) + "\n\n... [truncated at $maxChars characters]"
                    } else {
                        text
                    }
                } ?: error("Unable to read '$safePath'.")
            }
        }

    suspend fun deleteFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safePath = sanitizeRelativePath(relativePath)
            val target = resolveExistingFile(safePath)
                ?: error("File not found in selected folder: $safePath")
            if (!target.delete()) {
                error("Failed to delete '$safePath'.")
            }
        }
    }

    private fun resolveExistingFile(safePath: String): DocumentFile? {
        val treeUri = getSavedTreeUri() ?: return null
        var current: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val segments = safePath.split('/')
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun writeBytesTruncating(uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver
        val wroteViaPfd = runCatching {
            resolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.write(bytes)
                    out.flush()
                    runCatching { pfd.fileDescriptor.sync() }
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (wroteViaPfd) return

        val stream = resolver.openOutputStream(uri, "wt")
            ?: resolver.openOutputStream(uri, "w")
            ?: error("Could not open output stream for file write.")
        stream.use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    private fun buildSnapshot(
        uri: Uri,
        root: DocumentFile,
        displayName: String,
    ): WorkspaceFolderSnapshot {
        val entries = mutableListOf<WorkspaceFileEntry>()
        collectEntries(
            dir = root,
            prefix = "",
            depth = 0,
            maxDepth = MAX_SCAN_DEPTH,
            maxEntries = MAX_SCAN_ENTRIES,
            out = entries,
        )
        return WorkspaceFolderSnapshot(
            treeUri = uri.toString(),
            displayName = displayName,
            canWrite = root.canWrite(),
            files = entries.sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.relativePath.lowercase(Locale.ROOT) }),
        )
    }

    private fun collectEntries(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        maxDepth: Int,
        maxEntries: Int,
        out: MutableList<WorkspaceFileEntry>,
    ) {
        if (depth > maxDepth || out.size >= maxEntries) return
        val children = dir.listFiles()
        for (child in children) {
            if (out.size >= maxEntries) break
            val name = child.name ?: continue
            if (name in IGNORED_NAMES) continue
            val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
            val isDir = child.isDirectory
            out += WorkspaceFileEntry(
                relativePath = relPath,
                name = name,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else child.length().coerceAtLeast(0L),
                lastModifiedMillis = child.lastModified(),
            )
            if (isDir && depth < maxDepth) {
                collectEntries(
                    dir = child,
                    prefix = relPath,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    maxEntries = maxEntries,
                    out = out,
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "codex_mobile_workspace"
        private const val KEY_TREE_URI = "workspace_tree_uri"
        private const val KEY_FOLDER_NAME = "workspace_folder_name"
        private const val MAX_SCAN_DEPTH = 3
        private const val MAX_SCAN_ENTRIES = 120
        private const val MAX_READ_CHARS = 24_000
        private val IGNORED_NAMES = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")

        fun sanitizeRelativePath(rawPath: String): String {
            val normalized = rawPath
                .trim()
                .replace('\\', '/')
                .trimStart('/')
            require(normalized.isNotBlank()) { "File path cannot be empty." }
            require(!normalized.contains('\u0000')) { "Invalid character in file path." }
            val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
            require(segments.isNotEmpty()) { "File path cannot be empty." }
            require(segments.none { it == ".." }) { "Parent directory traversal ('..') is not allowed." }
            require(segments.none { it.contains(':') }) { "Drive or scheme prefixes are not allowed in file path." }
            return segments.joinToString("/")
        }

        fun mimeTypeForCreate(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "txt" -> "text/plain"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "xml" -> "application/xml"
                else -> "application/octet-stream"
            }
        }
    }
}
