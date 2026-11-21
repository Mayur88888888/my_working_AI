package io.github.aeewws.codexmobile.workspace

data class ParsedWriteDirective(
    val relativePath: String,
    val content: String,
)

data class ParsedReadDirective(
    val relativePath: String,
)

data class ParsedWorkspaceActions(
    val writeDirectives: List<ParsedWriteDirective>,
    val readDirectives: List<ParsedReadDirective>,
)

data class ExtractedCodeSnippet(
    val language: String,
    val suggestedPath: String?,
    val content: String,
)

object WorkspaceActionParser {
    private val XML_WRITE_REGEX = Regex(
        """<write_file\s+path\s*=\s*["']([^"']+)["']\s*>(.*?)</write_file>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val XML_READ_REGEX = Regex(
        """<read_file\s+path\s*=\s*["']([^"']+)["']\s*(?:/\s*>|>\s*</read_file>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val FENCED_BLOCK_REGEX = Regex(
        """```([^\n`]*)\n(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val FENCE_HEADER_PATH_REGEX = Regex(
        """^(?:write_file|file|save)\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val FENCE_ATTR_PATH_REGEX = Regex(
        """(?:^|\s)path\s*=\s*["']?([^"'\s]+)["']?""",
        RegexOption.IGNORE_CASE,
    )

    private val FIRST_LINE_PATH_REGEX = Regex(
        """^(?://|#|--|<!--)\s*(?:file|path)\s*:\s*([^\s>]+)(?:\s*-->)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun parseDirectives(text: String): ParsedWorkspaceActions {
        val normalized = text.replace("\r\n", "\n")
        val writesByPath = linkedMapOf<String, String>()
        val reads = linkedSetOf<String>()

        XML_WRITE_REGEX.findAll(normalized).forEach { match ->
            val rawPath = match.groupValues[1]
            val body = trimLeadingTrailingBlankLine(match.groupValues[2])
            runCatching { WorkspaceFileManager.sanitizeRelativePath(rawPath) }
                .onSuccess { safe -> writesByPath[safe] = body }
        }

        XML_READ_REGEX.findAll(normalized).forEach { match ->
            val rawPath = match.groupValues[1]
            runCatching { WorkspaceFileManager.sanitizeRelativePath(rawPath) }
                .onSuccess { safe -> reads += safe }
        }

        FENCED_BLOCK_REGEX.findAll(normalized).forEach { match ->
            val header = match.groupValues[1].trim()
            val body = match.groupValues[2]
            val explicitPath = extractExplicitPath(header, body)
            if (!explicitPath.isNullOrBlank()) {
                val cleanedBody = stripFirstLinePathCommentIfPresent(header, body)
                runCatching { WorkspaceFileManager.sanitizeRelativePath(explicitPath) }
                    .onSuccess { safe -> writesByPath[safe] = cleanedBody }
            }
        }

        return ParsedWorkspaceActions(
            writeDirectives = writesByPath.entries.map { (path, content) ->
                ParsedWriteDirective(relativePath = path, content = content)
            },
            readDirectives = reads.map(::ParsedReadDirective),
        )
    }

    fun extractCodeSnippets(text: String): List<ExtractedCodeSnippet> {
        val normalized = text.replace("\r\n", "\n")
        val snippets = mutableListOf<ExtractedCodeSnippet>()

        XML_WRITE_REGEX.findAll(normalized).forEach { match ->
            val rawPath = match.groupValues[1].trim()
            val body = trimLeadingTrailingBlankLine(match.groupValues[2])
            val safePath = runCatching { WorkspaceFileManager.sanitizeRelativePath(rawPath) }.getOrNull()
            if (body.isNotBlank()) {
                snippets += ExtractedCodeSnippet(
                    language = safePath?.substringAfterLast('.', "").orEmpty(),
                    suggestedPath = safePath,
                    content = body,
                )
            }
        }

        FENCED_BLOCK_REGEX.findAll(normalized).forEach { match ->
            val header = match.groupValues[1].trim()
            val body = match.groupValues[2]
            val explicitPath = extractExplicitPath(header, body)
                ?.let { runCatching { WorkspaceFileManager.sanitizeRelativePath(it) }.getOrNull() }
            val cleaned = stripFirstLinePathCommentIfPresent(header, body).trimEnd()
            if (cleaned.isNotBlank()) {
                val language = header
                    .substringBefore(' ')
                    .substringBefore(':')
                    .trim()
                snippets += ExtractedCodeSnippet(
                    language = language,
                    suggestedPath = explicitPath,
                    content = cleaned,
                )
            }
        }

        return snippets
    }

    private fun extractExplicitPath(header: String, body: String): String? {
        FENCE_HEADER_PATH_REGEX.find(header)?.let { return it.groupValues[1].trim() }
        FENCE_ATTR_PATH_REGEX.find(header)?.let { return it.groupValues[1].trim() }
        val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
        FIRST_LINE_PATH_REGEX.find(firstLine)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun stripFirstLinePathCommentIfPresent(header: String, body: String): String {
        val withoutEdgeBlank = trimLeadingTrailingBlankLine(body)
        if (FENCE_HEADER_PATH_REGEX.matches(header) || FENCE_ATTR_PATH_REGEX.containsMatchIn(header)) {
            return withoutEdgeBlank
        }
        val lines = withoutEdgeBlank.lines()
        if (lines.isNotEmpty() && FIRST_LINE_PATH_REGEX.matches(lines.first().trim())) {
            return lines.drop(1).joinToString("\n").trimStart('\n')
        }
        return withoutEdgeBlank
    }

    private fun trimLeadingTrailingBlankLine(raw: String): String {
        var result = raw
        if (result.startsWith("\n")) {
            result = result.removePrefix("\n")
        }
        if (result.endsWith("\n")) {
            result = result.removeSuffix("\n")
        }
        return result
    }
}
