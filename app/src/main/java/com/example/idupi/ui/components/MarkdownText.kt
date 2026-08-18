package com.example.idupi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.idupi.ui.theme.AppShapes
import com.example.idupi.ui.theme.AppSpacing
import com.example.idupi.ui.theme.AppTypography
import com.example.idupi.ui.theme.PrimaryIndigo
import com.example.idupi.ui.theme.SlateBg
import com.example.idupi.ui.theme.SlateBorder
import com.example.idupi.ui.theme.SlateCard
import com.example.idupi.ui.theme.TextPrimary
import com.example.idupi.ui.theme.TextSecondary

// ---------------------------------------------------------------------------
// Block-level model + parser. Pure and Compose-free so it is unit-testable.
// ---------------------------------------------------------------------------

/**
 * Block-level Markdown elements this renderer understands. Scoped to what a
 * coding CLI actually emits: headings, fenced code, GFM pipe tables, lists,
 * paragraphs. Anything not recognized falls through as [Paragraph] verbatim
 * -- a renderer that mangles unknown syntax is worse than one that passes it
 * through untouched.
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock()
    data class Table(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>
    ) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<String>) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

enum class TableAlignment { LEFT, CENTER, RIGHT, NONE }

private val NUMBERED_ITEM_REGEX = Regex("""^(\d+)\.\s+(.*)$""")

/**
 * Splits raw Markdown text into block-level elements, in source order. Pure
 * and side-effect free.
 */
fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphBuffer = mutableListOf<String>()
    var i = 0

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            val text = paragraphBuffer.joinToString("\n").trim()
            if (text.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(text))
            paragraphBuffer.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim().ifBlank { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
                i++ // consume the closing fence (safe if we hit end of input instead)
            }

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') {
                    flushParagraph()
                    blocks.add(MarkdownBlock.Heading(level, trimmed.drop(level).trim()))
                    i++
                } else {
                    paragraphBuffer.add(line)
                    i++
                }
            }

            isTableHeaderStart(lines, i) -> {
                flushParagraph()
                val headerCells = splitTableRow(lines[i])
                val alignments = parseTableAlignments(lines[i + 1])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headerCells, alignments, rows))
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && (lines[i].trim().startsWith("- ") || lines[i].trim().startsWith("* "))) {
                    items.add(lines[i].trim().removePrefix("- ").removePrefix("* ").trim())
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items))
            }

            NUMBERED_ITEM_REGEX.matches(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val match = NUMBERED_ITEM_REGEX.find(lines[i].trim()) ?: break
                    items.add(match.groupValues[2])
                    i++
                }
                blocks.add(MarkdownBlock.NumberedList(items))
            }

            trimmed.isEmpty() -> {
                flushParagraph()
                i++
            }

            else -> {
                paragraphBuffer.add(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

/** A GFM table header requires the next line to be a `---|---` alignment separator. */
private fun isTableHeaderStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val headerLine = lines[index]
    val separatorLine = lines[index + 1].trim()
    if (!headerLine.contains("|") || separatorLine.isEmpty()) return false
    return splitTableRow(separatorLine).all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.any { it == '-' }
    }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun parseTableAlignments(separatorLine: String): List<TableAlignment> =
    splitTableRow(separatorLine).map { cell ->
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        when {
            left && right -> TableAlignment.CENTER
            right -> TableAlignment.RIGHT
            left -> TableAlignment.LEFT
            else -> TableAlignment.NONE
        }
    }

// ---------------------------------------------------------------------------
// Inline formatting: **bold**, *italic*, `code`.
// ---------------------------------------------------------------------------

private val INLINE_PATTERN = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*")

@Composable
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    for (match in INLINE_PATTERN.findAll(text)) {
        if (match.range.first > lastIndex) {
            append(text.substring(lastIndex, match.range.first))
        }
        val codeGroup = match.groups[1]
        val boldGroup = match.groups[2]
        val italicGroup = match.groups[3]
        when {
            codeGroup != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = SlateBg, color = TextPrimary)
            ) { append(codeGroup.value) }
            boldGroup != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldGroup.value) }
            italicGroup != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italicGroup.value) }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}

// ---------------------------------------------------------------------------
// Composables.
// ---------------------------------------------------------------------------

/**
 * Renders a Markdown string with the block/inline constructs a coding CLI
 * actually produces: headings, fenced code, GFM tables, lists, bold/italic,
 * inline code. Anything else is shown as plain text rather than mangled.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeading(block, textColor)
                is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block)
                is MarkdownBlock.Table -> MarkdownTable(block)
                is MarkdownBlock.BulletList -> MarkdownBulletList(block, textColor)
                is MarkdownBlock.NumberedList -> MarkdownNumberedList(block, textColor)
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = AppTypography.chatBody,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun MarkdownHeading(block: MarkdownBlock.Heading, textColor: Color) {
    val style = when (block.level) {
        1 -> AppTypography.titleMedium
        2 -> AppTypography.titleSmall
        else -> AppTypography.labelSmall
    }
    Text(text = inlineMarkdown(block.text), style = style, color = textColor)
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(SlateBg)
            .border(1.dp, SlateBorder, AppShapes.card)
    ) {
        if (!block.language.isNullOrBlank()) {
            Text(
                text = block.language.uppercase(),
                style = AppTypography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateCard)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
            )
        }
        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(AppSpacing.md)
        ) {
            Text(text = block.code, style = AppTypography.codeMono, color = TextPrimary)
        }
    }
}

@Composable
private fun MarkdownBulletList(block: MarkdownBlock.BulletList, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        block.items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text("•", style = AppTypography.chatBody, color = textColor)
                Text(text = inlineMarkdown(item), style = AppTypography.chatBody, color = textColor)
            }
        }
    }
}

@Composable
private fun MarkdownNumberedList(block: MarkdownBlock.NumberedList, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        block.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text("${index + 1}.", style = AppTypography.chatBody, color = textColor)
                Text(text = inlineMarkdown(item), style = AppTypography.chatBody, color = textColor)
            }
        }
    }
}

/**
 * Renders a GFM table with real, content-aligned columns: a [Layout] measures
 * every cell (header row included) up front, takes the max width per column
 * index across all rows, then places every cell in that row/column grid --
 * genuinely aligned, the way a CLI prints a table, not just visually close.
 */
@Composable
private fun MarkdownTable(block: MarkdownBlock.Table) {
    val columnCount = block.headers.size
    val allRows = listOf(block.headers) + block.rows

    Surface(
        color = SlateCard,
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    allRows.forEachIndexed { rowIndex, row ->
                        val isHeader = rowIndex == 0
                        for (colIndex in 0 until columnCount) {
                            val cellText = row.getOrElse(colIndex) { "" }
                            val alignment = tableCellAlignment(block.alignments.getOrElse(colIndex) { TableAlignment.NONE })
                            Box(
                                modifier = Modifier
                                    .background(if (isHeader) PrimaryIndigo.copy(alpha = 0.15f) else Color.Transparent)
                                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                contentAlignment = alignment
                            ) {
                                Text(
                                    text = cellText,
                                    style = if (isHeader) AppTypography.labelSmall else AppTypography.bodySmall,
                                    color = if (isHeader) PrimaryIndigo else TextPrimary
                                )
                            }
                        }
                    }
                }
            ) { measurables, constraints ->
                val rowCount = allRows.size
                val cellConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val placeables = measurables.map { it.measure(cellConstraints) }

                val columnWidths = IntArray(columnCount)
                val rowHeights = IntArray(rowCount)
                placeables.forEachIndexed { index, placeable ->
                    val row = index / columnCount
                    val col = index % columnCount
                    columnWidths[col] = maxOf(columnWidths[col], placeable.width)
                    rowHeights[row] = maxOf(rowHeights[row], placeable.height)
                }

                layout(columnWidths.sum(), rowHeights.sum()) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (col in 0 until columnCount) {
                            placeables[row * columnCount + col].place(x, y)
                            x += columnWidths[col]
                        }
                        y += rowHeights[row]
                    }
                }
            }
        }
    }
}

private fun tableCellAlignment(alignment: TableAlignment): Alignment = when (alignment) {
    TableAlignment.CENTER -> Alignment.Center
    TableAlignment.RIGHT -> Alignment.CenterEnd
    TableAlignment.LEFT, TableAlignment.NONE -> Alignment.CenterStart
}
