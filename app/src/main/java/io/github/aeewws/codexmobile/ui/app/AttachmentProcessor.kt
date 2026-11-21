package io.github.aeewws.codexmobile.ui.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.aeewws.codexmobile.runtime.CodexRuntimeController
import io.github.aeewws.codexmobile.runtime.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

private enum class DocumentExtractMode {
    PDF,
    DOCX,
    XLSX,
    TEXT,
}

class AttachmentProcessor(
    private val context: Context,
    private val runtimeController: CodexRuntimeController,
) {
    suspend fun prepareAttachment(uri: Uri): ChatAttachmentUi = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri).ifBlank { "attachment" }
        val mimeType = resolveMimeType(uri, displayName)
        when {
            mimeType.startsWith("image/", ignoreCase = true) ->
                prepareImageAttachment(uri, displayName, mimeType)
            isPdfAttachment(mimeType, displayName) ->
                prepareDocumentAttachment(uri, displayName, mimeType, DocumentExtractMode.PDF)
            isDocxAttachment(mimeType, displayName) ->
                prepareDocumentAttachment(uri, displayName, mimeType, DocumentExtractMode.DOCX)
            isXlsxAttachment(mimeType, displayName) ->
                prepareDocumentAttachment(uri, displayName, mimeType, DocumentExtractMode.XLSX)
            isLegacyXlsAttachment(mimeType, displayName) ->
                error("Legacy .xls files are not supported yet. Please convert to .xlsx or CSV.")
            isTextAttachment(mimeType, displayName) ->
                prepareDocumentAttachment(uri, displayName, mimeType, DocumentExtractMode.TEXT)
            else -> error("Unsupported file type: $displayName")
        }
    }

    suspend fun prepareCameraAttachment(bitmap: Bitmap): ChatAttachmentUi = withContext(Dispatchers.IO) {
        storePreparedBitmap(bitmap, "photo.jpg", "image/jpeg")
    }

    fun readExtractedAttachmentText(attachment: ChatAttachmentUi): String {
        val path = attachment.extractedTextPath ?: error("Missing extracted text for attachment.")
        val file = File(path)
        if (!file.exists()) {
            error("Cached attachment content is no longer available.")
        }
        return file.readText(Charsets.UTF_8)
    }

    fun buildDocumentTransportText(
        displayText: String,
        attachment: ChatAttachmentUi,
        extractedText: String,
    ): String {
        val metadata = JSONObject()
            .put("name", attachment.displayName)
            .put("mime", attachment.mimeType)
        return buildString {
            append(DOCUMENT_PREFIX)
            append(metadata.toString())
            append(DOCUMENT_SUFFIX)
            append("\n\n")
            append(displayText)
            append(DOCUMENT_CONTENT_MARKER)
            append(extractedText)
        }.trim()
    }

    suspend fun deleteAttachmentFiles(attachment: ChatAttachmentUi?) = withContext(Dispatchers.IO) {
        if (attachment == null) return@withContext
        attachment.previewPath?.let { runCatching { File(it).delete() } }
        attachment.extractedTextPath?.let { runCatching { File(it).delete() } }
        attachment.backendPath?.let { path ->
            runCatching {
                RootShell.run(
                    command = "rm -f ${RootShell.shellQuote(path)}",
                    timeoutMillis = 8_000L,
                )
            }
        }
    }

    private fun prepareImageAttachment(
        uri: Uri,
        displayName: String,
        mimeType: String,
    ): ChatAttachmentUi {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Unable to read image file.")
        return storePreparedBitmap(bitmap, displayName, mimeType)
    }

    private fun storePreparedBitmap(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String,
    ): ChatAttachmentUi {
        val scaled = scaleBitmap(bitmap)
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        val appFile = createAppAttachmentFile(displayName, "jpg")
        FileOutputStream(appFile).use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Failed to save cached image." }
        }
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        val backendPath = runCatching { copyFileToBackendReadableLocation(appFile) }.getOrNull()
        return ChatAttachmentUi(
            kind = AttachmentKind.IMAGE,
            displayName = displayName,
            mimeType = mimeType,
            previewPath = appFile.absolutePath,
            backendPath = backendPath,
        )
    }

    private fun prepareDocumentAttachment(
        uri: Uri,
        displayName: String,
        mimeType: String,
        mode: DocumentExtractMode,
    ): ChatAttachmentUi {
        val extension = displayName.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        val sourceFile = createAppAttachmentFile(displayName, extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected file.")

        val extractedText = runCatching {
            when (mode) {
                DocumentExtractMode.PDF -> extractPdfText(sourceFile)
                DocumentExtractMode.DOCX -> extractDocxText(sourceFile)
                DocumentExtractMode.XLSX -> extractXlsxText(sourceFile)
                DocumentExtractMode.TEXT -> extractTextDocument(sourceFile)
            }
        }.getOrElse { throwable ->
            throw IllegalStateException(documentParseErrorMessage(mode), throwable)
        }.ifBlank { error("No readable text could be extracted from $displayName.") }

        val extractedFile = createAppAttachmentFile("${sourceFile.nameWithoutExtension}-extracted", "txt")
        extractedFile.writeText(extractedText, Charsets.UTF_8)
        return ChatAttachmentUi(
            kind = AttachmentKind.DOCUMENT,
            displayName = displayName,
            mimeType = mimeType,
            extractedTextPath = extractedFile.absolutePath,
        )
    }

    private fun queryDisplayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("")

    private fun resolveMimeType(uri: Uri, displayName: String): String {
        val direct = context.contentResolver.getType(uri).orEmpty()
        if (direct.isNotBlank()) return direct
        return when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "txt", "log", "md", "json", "xml", "csv", "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh", "yaml", "yml", "toml", "gradle", "kts" -> "text/plain"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun isPdfAttachment(mimeType: String, displayName: String): Boolean =
        mimeType.equals("application/pdf", ignoreCase = true) ||
            displayName.endsWith(".pdf", ignoreCase = true)

    private fun isTextAttachment(mimeType: String, displayName: String): Boolean {
        if (mimeType.startsWith("text/", ignoreCase = true)) return true
        return displayName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
            "txt", "log", "md", "json", "xml", "csv", "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh",
            "yaml", "yml", "toml", "gradle", "kts", "c", "cpp", "h", "hpp", "rs", "go", "html", "css", "sql",
        )
    }

    private fun isDocxAttachment(mimeType: String, displayName: String): Boolean =
        mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true) ||
            displayName.endsWith(".docx", ignoreCase = true)

    private fun isXlsxAttachment(mimeType: String, displayName: String): Boolean =
        mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ignoreCase = true) ||
            displayName.endsWith(".xlsx", ignoreCase = true)

    private fun isLegacyXlsAttachment(mimeType: String, displayName: String): Boolean =
        mimeType.equals("application/vnd.ms-excel", ignoreCase = true) ||
            displayName.endsWith(".xls", ignoreCase = true)

    private fun createAppAttachmentFile(displayName: String, extension: String): File {
        val safeBase = sanitizeFileName(displayName.substringBeforeLast('.').ifBlank { "attachment" })
        val ext = extension.trim('.').ifBlank { "bin" }
        val dir = File(context.cacheDir, APP_ATTACHMENT_DIR).apply { mkdirs() }
        return File(dir, "${safeBase}-${System.currentTimeMillis()}.$ext")
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "attachment" }

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int = 2048): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / maxSide.toFloat()
        return bitmap.scale(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun copyFileToBackendReadableLocation(file: File): String {
        val targetName = sanitizeFileName(file.nameWithoutExtension) + "." + file.extension.ifBlank { "bin" }
        val targetDir = File(runtimeController.backendAttachmentDirectoryPath()).apply { mkdirs() }
        val targetFile = File(targetDir, targetName)
        file.copyTo(targetFile, overwrite = true)
        targetFile.setReadable(true, false)
        return targetFile.absolutePath
    }

    private fun extractTextDocument(file: File): String {
        val bytes = file.readBytes()
        val utf8 = bytes.toString(Charsets.UTF_8)
        val text = if (utf8.contains('\uFFFD')) {
            runCatching { bytes.toString(Charset.forName("GB18030")) }.getOrDefault(utf8)
        } else {
            utf8
        }
        return text.trim().take(MAX_DOCUMENT_CHARS)
    }

    private fun extractPdfText(file: File): String =
        PDDocument.load(file).use { document ->
            PDFTextStripper().getText(document)
                .replace("\r\n", "\n")
                .trim()
                .take(MAX_DOCUMENT_CHARS)
        }

    private fun extractDocxText(file: File): String =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("Cannot read DOCX body.")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
                    .replace(Regex("</w:p>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .let(::decodeXmlEntities)
                    .replace(Regex("\n{2,}"), "\n\n")
                    .trim()
                    .take(MAX_DOCUMENT_CHARS)
            }
        }

    private fun extractXlsxText(file: File): String =
        ZipFile(file).use { zip ->
            val sharedStrings = zip.getEntry("xl/sharedStrings.xml")
                ?.let { parseSharedStrings(zip, it.name) }
                .orEmpty()
            val sheetEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .toList()
            if (sheetEntries.isEmpty()) {
                error("Cannot read XLSX worksheets.")
            }
            buildString {
                sheetEntries.forEachIndexed { index, entry ->
                    val rows = extractXlsxSheetRows(zip, entry.name, sharedStrings)
                    if (rows.isEmpty()) return@forEachIndexed
                    if (isNotBlank()) append("\n\n")
                    append("Sheet ")
                    append(index + 1)
                    append("\n")
                    append(rows.joinToString("\n"))
                }
            }.trim().take(MAX_DOCUMENT_CHARS)
        }

    private fun parseSharedStrings(zip: ZipFile, entryName: String): List<String> {
        val xml = zip.getInputStream(zip.getEntry(entryName)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Regex("<si[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
            .map { match ->
                Regex("<t[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(match.groupValues[1])
                    .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                    .trim()
            }
            .toList()
    }

    private fun extractXlsxSheetRows(
        zip: ZipFile,
        entryName: String,
        sharedStrings: List<String>,
    ): List<String> {
        val xml = zip.getInputStream(zip.getEntry(entryName)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Regex("<row[^>]*>(.*?)</row>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
            .mapNotNull { match -> extractXlsxRowText(match.groupValues[1], sharedStrings).takeIf { it.isNotBlank() } }
            .toList()
    }

    private fun extractXlsxRowText(
        rowXml: String,
        sharedStrings: List<String>,
    ): String {
        val values = Regex("<c([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(rowXml)
            .mapNotNull { match ->
                val attributes = match.groupValues[1]
                val cellXml = match.groupValues[2]
                val cellType = Regex("""\bt="([^"]+)"""").find(attributes)?.groupValues?.getOrNull(1).orEmpty()
                when (cellType) {
                    "s" -> Regex("<v>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                        .find(cellXml)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.toIntOrNull()
                        ?.let { sharedStrings.getOrNull(it).orEmpty().trim() }
                    "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                        .findAll(cellXml)
                        .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                        .trim()
                    else -> Regex("<v>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                        .find(cellXml)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let(::decodeXmlEntities)
                        ?.trim()
                }?.takeIf { it.isNotBlank() }
            }
            .toList()
        return values.joinToString(" | ")
    }

    private fun decodeXmlEntities(text: String): String =
        text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")

    private fun documentParseErrorMessage(mode: DocumentExtractMode): String =
        when (mode) {
            DocumentExtractMode.PDF -> "Failed to parse PDF document. Try another PDF file."
            DocumentExtractMode.DOCX -> "Failed to parse Word document. Try another DOCX file."
            DocumentExtractMode.XLSX -> "Failed to parse Excel workbook. Try another XLSX file."
            DocumentExtractMode.TEXT -> "Failed to read text file. Try another file."
        }

    companion object {
        const val DOCUMENT_PREFIX = "<<<CODEX_MOBILE_DOCUMENT:"
        const val DOCUMENT_SUFFIX = ">>>"
        const val DOCUMENT_CONTENT_MARKER = "\n\nAttached file contents (may be truncated):\n"
        private const val APP_ATTACHMENT_DIR = "codex-mobile-attachments"
        private const val MAX_DOCUMENT_CHARS = 20_000
    }
}
