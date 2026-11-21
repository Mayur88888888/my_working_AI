package io.github.aeewws.codexmobile.ui.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aeewws.codexmobile.workspace.WorkspaceActionParser
import io.github.aeewws.codexmobile.workspace.WorkspaceFileManager

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
internal fun CommandPanelHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("panel_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Back")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CommandEntryCard(
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ChatStatusStrip(
    connection: ConnectionBanner,
    errorMessage: String,
    statusMessage: String,
) {
    val containerColor = when {
        errorMessage.isNotBlank() -> MaterialTheme.colorScheme.errorContainer
        connection.tone == BannerTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        connection.tone == BannerTone.DANGER -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val message = when {
        errorMessage.isNotBlank() -> errorMessage
        statusMessage.isNotBlank() -> statusMessage
        else -> connection.detail
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when {
                            errorMessage.isNotBlank() -> MaterialTheme.colorScheme.error
                            connection.tone == BannerTone.WARN -> MaterialTheme.colorScheme.tertiary
                            connection.tone == BannerTone.DANGER -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CodePanel(
    text: String,
    headerLabel: String? = null,
    onSaveToFolder: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!headerLabel.isNullOrBlank() || onSaveToFolder != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headerLabel?.ifBlank { "Code" } ?: "Code",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onSaveToFolder != null) {
                        TextButton(
                            onClick = onSaveToFolder,
                            modifier = Modifier.testTag("write_code_block_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = "Write to selected folder",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Write to Folder", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState)
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun AssistantMessageBody(
    text: String,
    style: TextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onRequestSaveCode: ((suggestedPath: String?, content: String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseAssistantBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AssistantBlock.Paragraph -> Text(
                    text = block.text,
                    style = style,
                    color = color,
                )

                is AssistantBlock.Code -> CodePanel(
                    text = block.code,
                    headerLabel = block.suggestedPath ?: block.language.ifBlank { "Code" },
                    onSaveToFolder = onRequestSaveCode?.let { handler ->
                        { handler(block.suggestedPath, block.code) }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ApprovalCard(
    approval: ApprovalRequestUi,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(approval.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(approval.subtitle, style = MaterialTheme.typography.bodyMedium)
            if (approval.body.isNotBlank()) {
                CodePanel(text = approval.body)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("approval_decline_button"),
                ) {
                    Text("Decline")
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.testTag("approval_approve_button"),
                ) {
                    Text("Write Files")
                }
            }
        }
    }
}

@Composable
internal fun WriteFileDialog(
    initialPath: String,
    initialContent: String,
    folderName: String,
    hasFolder: Boolean,
    onPickFolderFirst: () -> Unit,
    onConfirmWrite: (relativePath: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pathDraft by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var contentDraft by rememberSaveable(initialContent) { mutableStateOf(initialContent) }
    val pathValidationError = remember(pathDraft) {
        runCatching { WorkspaceFileManager.sanitizeRelativePath(pathDraft) }.exceptionOrNull()?.message
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write File to Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (hasFolder) {
                        "Target folder: $folderName"
                    } else {
                        "No output folder selected yet. Pick a folder to save files directly."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (!hasFolder) {
                    OutlinedButton(
                        onClick = onPickFolderFirst,
                        modifier = Modifier.testTag("dialog_pick_folder_button"),
                    ) {
                        Text("Select Output Folder")
                    }
                }
                OutlinedTextField(
                    value = pathDraft,
                    onValueChange = { pathDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("write_file_path_input"),
                    singleLine = true,
                    label = { Text("Relative file path") },
                    placeholder = { Text("e.g. src/main.py or notes.md") },
                    isError = pathDraft.isNotBlank() && pathValidationError != null,
                    supportingText = pathValidationError?.takeIf { pathDraft.isNotBlank() }?.let { err ->
                        { Text(err) }
                    },
                )
                OutlinedTextField(
                    value = contentDraft,
                    onValueChange = { contentDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 240.dp)
                        .testTag("write_file_content_input"),
                    label = { Text("File contents") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmWrite(pathDraft, contentDraft) },
                enabled = hasFolder && pathValidationError == null && pathDraft.isNotBlank(),
                modifier = Modifier.testTag("write_file_confirm_button"),
            ) {
                Text("Write File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun AttachmentSourceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.88f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun AttachmentList(
    attachments: List<ChatAttachmentUi>,
    compact: Boolean,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments.size) { index ->
            val attachment = attachments[index]
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentThumb(attachment, sizeDp = if (compact) 30 else 40)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            if (attachment.kind == AttachmentKind.IMAGE) "Image" else attachment.displayName,
                            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            attachment.kind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttachmentThumb(
    attachment: ChatAttachmentUi,
    sizeDp: Int,
) {
    val previewBitmap = remember(attachment.previewPath) {
        attachment.previewPath
            ?.takeIf { attachment.kind == AttachmentKind.IMAGE }
            ?.let(BitmapFactory::decodeFile)
            ?.asImageBitmap()
    }
    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap,
            contentDescription = attachment.displayName,
            modifier = Modifier.size(sizeDp.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier.size(sizeDp.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (attachment.kind == AttachmentKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Description,
                    contentDescription = attachment.displayName,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((sizeDp * 0.55f).dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private sealed interface AssistantBlock {
    data class Paragraph(val text: String) : AssistantBlock
    data class Code(val language: String, val suggestedPath: String?, val code: String) : AssistantBlock
}

private fun parseAssistantBlocks(raw: String): List<AssistantBlock> {
    val source = raw.replace("\r\n", "\n")
    val xmlWriteRegex = Regex(
        """<write_file\s+path\s*=\s*["']([^"']+)["']\s*>(.*?)</write_file>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // First split by <write_file> tags, then within non-tag segments parse markdown fences.
    val blocks = mutableListOf<AssistantBlock>()
    var cursor = 0
    for (match in xmlWriteRegex.findAll(source)) {
        if (match.range.first > cursor) {
            blocks += parseMarkdownFenceBlocks(source.substring(cursor, match.range.first))
        }
        val path = match.groupValues[1].trim()
        val body = match.groupValues[2].trim('\n')
        blocks += AssistantBlock.Code(
            language = path.substringAfterLast('.', ""),
            suggestedPath = path,
            code = body,
        )
        cursor = match.range.last + 1
    }
    if (cursor < source.length) {
        blocks += parseMarkdownFenceBlocks(source.substring(cursor))
    }
    return blocks.ifEmpty { listOf(AssistantBlock.Paragraph(cleanInlineMarkdown(source))) }
}

private fun parseMarkdownFenceBlocks(source: String): List<AssistantBlock> {
    if (!source.contains("```")) return paragraphBlocks(source)
    val blocks = mutableListOf<AssistantBlock>()
    var cursor = 0
    while (cursor < source.length) {
        val fenceStart = source.indexOf("```", cursor)
        if (fenceStart < 0) {
            blocks += paragraphBlocks(source.substring(cursor))
            break
        }
        if (fenceStart > cursor) {
            blocks += paragraphBlocks(source.substring(cursor, fenceStart))
        }
        val fenceEnd = source.indexOf("```", fenceStart + 3)
        if (fenceEnd < 0) {
            blocks += paragraphBlocks(source.substring(fenceStart))
            break
        }
        val fullFenced = source.substring(fenceStart, fenceEnd + 3)
        val snippet = WorkspaceActionParser.extractCodeSnippets(fullFenced).firstOrNull()
        if (snippet != null) {
            blocks += AssistantBlock.Code(
                language = snippet.language,
                suggestedPath = snippet.suggestedPath,
                code = snippet.content,
            )
        }
        cursor = fenceEnd + 3
    }
    return blocks
}

private fun paragraphBlocks(chunk: String): List<AssistantBlock.Paragraph> =
    chunk
        .split(Regex("\n{2,}"))
        .map(::cleanInlineMarkdown)
        .filter { it.isNotBlank() }
        .map(AssistantBlock::Paragraph)

private fun cleanInlineMarkdown(text: String): String =
    text
        .replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "$1")
        .replace("`", "")
        .trim()
