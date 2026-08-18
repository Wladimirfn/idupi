package com.example.idupi.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.idupi.ui.theme.*

@Composable
fun CodeViewer(
    fileName: String,
    content: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isMarkdown = fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".markdown", ignoreCase = true)
    var showRenderedMarkdown by remember(fileName) { mutableStateOf(isMarkdown) }

    val extension = fileName.substringAfterLast('.', "").lowercase()
    val lines = remember(content) { content.split("\n") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateBg)
    ) {
        // Toolbar with Stats, Copy Button & Mode Switch
        Surface(
            color = SlateCard,
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Surface(
                        color = PrimaryIndigo.copy(alpha = 0.15f),
                        shape = AppShapes.small
                    ) {
                        Text(
                            text = if (extension.isNotBlank()) extension.uppercase() else "TEXT",
                            style = AppTypography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryIndigo,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "${lines.size} líneas · ${content.length} caracteres",
                        style = AppTypography.labelSmall,
                        color = TextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (isMarkdown) {
                        FilterChip(
                            selected = showRenderedMarkdown,
                            onClick = { showRenderedMarkdown = !showRenderedMarkdown },
                            label = {
                                Text(
                                    if (showRenderedMarkdown) "Renderizado" else "Código",
                                    style = AppTypography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showRenderedMarkdown) Icons.Default.Visibility else Icons.Default.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("IDUPI Code", content))
                            Toast.makeText(context, "Código copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copiar código",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (isMarkdown && showRenderedMarkdown) {
            // Rendered Markdown View (Full tables, headers, lists, code blocks)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.md)
            ) {
                MarkdownText(
                    markdown = content,
                    textColor = TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            // Syntax Highlighted Code Editor View with Gutter Line Numbers
            val horizontalScrollState = rememberScrollState()
            val maxLineNumberDigits = lines.size.toString().length.coerceAtLeast(2)
            val gutterWidth = (maxLineNumberDigits * 10 + 20).dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = AppSpacing.sm)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val lineNumber = index + 1
                        val highlightedLine = remember(line, extension) {
                            highlightCodeLine(line, extension)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Line Number Gutter
                            Text(
                                text = lineNumber.toString(),
                                style = AppTypography.codeMono.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                ),
                                color = SyntaxColors.LineNumber,
                                textAlign = TextAlign.End,
                                modifier = Modifier
                                    .width(gutterWidth)
                                    .padding(end = AppSpacing.sm)
                            )

                            // Divider line between gutter and code
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(SlateBorder.copy(alpha = 0.5f))
                            )

                            Spacer(modifier = Modifier.width(AppSpacing.sm))

                            // Highlighted Code Text
                            Text(
                                text = highlightedLine,
                                style = AppTypography.codeMono.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                ),
                                modifier = Modifier.padding(end = AppSpacing.lg)
                            )
                        }
                    }
                }
            }
        }
    }
}
