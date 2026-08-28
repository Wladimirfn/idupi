package com.idupi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockParserTest {

    @Test
    fun `plain text becomes a single paragraph`() {
        val blocks = parseMarkdownBlocks("Just a plain sentence.")

        assertEquals(listOf(MarkdownBlock.Paragraph("Just a plain sentence.")), blocks)
    }

    @Test
    fun `fenced code block captures language and body separately from surrounding paragraphs`() {
        val source = """
            Before the code.
            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            After the code.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(source)

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Before the code."), blocks[0])
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", code.code)
        assertEquals(MarkdownBlock.Paragraph("After the code."), blocks[2])
    }

    @Test
    fun `fenced code block with no language tag has a null language`() {
        val source = "```\nraw text\n```"

        val blocks = parseMarkdownBlocks(source)

        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("raw text", code.code)
    }

    @Test
    fun `unterminated code fence does not crash and still captures its content`() {
        val source = "```python\nprint(1)"

        val blocks = parseMarkdownBlocks(source)

        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("python", code.language)
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `a pipe table is parsed with headers, alignments and rows -- distinct from a code block or paragraph`() {
        val source = """
            | Name | Age | Role |
            |:---|---:|:---:|
            | Ada | 36 | Engineer |
            | Grace | 85 | Admiral |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(source)

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("Name", "Age", "Role"), table.headers)
        assertEquals(
            listOf(TableAlignment.LEFT, TableAlignment.RIGHT, TableAlignment.CENTER),
            table.alignments
        )
        assertEquals(
            listOf(listOf("Ada", "36", "Engineer"), listOf("Grace", "85", "Admiral")),
            table.rows
        )
    }

    @Test
    fun `a table stops at the first blank line and does not swallow a following paragraph`() {
        val source = """
            | A | B |
            |---|---|
            | 1 | 2 |

            Not part of the table.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(source)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Table)
        assertEquals(MarkdownBlock.Paragraph("Not part of the table."), blocks[1])
    }

    @Test
    fun `text containing a pipe but no valid separator row is treated as a paragraph, not a table`() {
        val source = "This | is not | a table"

        val blocks = parseMarkdownBlocks(source)

        assertEquals(listOf(MarkdownBlock.Paragraph("This | is not | a table")), blocks)
    }

    @Test
    fun `headings are recognized at levels 1 to 3 with their level preserved`() {
        val blocks = parseMarkdownBlocks("# Title\n## Subtitle\n### Detail")

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Title"),
                MarkdownBlock.Heading(2, "Subtitle"),
                MarkdownBlock.Heading(3, "Detail")
            ),
            blocks
        )
    }

    @Test
    fun `a hash without a following space is not a heading`() {
        val blocks = parseMarkdownBlocks("#no-space-here")

        assertEquals(listOf(MarkdownBlock.Paragraph("#no-space-here")), blocks)
    }

    @Test
    fun `bullet list items are grouped into one BulletList block`() {
        val blocks = parseMarkdownBlocks("- first\n- second\n* third")

        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(listOf("first", "second", "third"), list.items)
    }

    @Test
    fun `numbered list items are grouped and their original numbering is discarded from the item text`() {
        val blocks = parseMarkdownBlocks("1. one\n2. two\n3. three")

        val list = blocks.single() as MarkdownBlock.NumberedList
        assertEquals(listOf("one", "two", "three"), list.items)
    }

    @Test
    fun `blank lines separate consecutive paragraphs instead of merging them`() {
        val blocks = parseMarkdownBlocks("First paragraph.\n\nSecond paragraph.")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("First paragraph."),
                MarkdownBlock.Paragraph("Second paragraph.")
            ),
            blocks
        )
    }
}
