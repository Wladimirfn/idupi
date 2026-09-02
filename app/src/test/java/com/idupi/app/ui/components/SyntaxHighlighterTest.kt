package com.idupi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    @Test
    fun `highlightCodeLine highlights keywords and strings`() {
        val line = "val greeting = \"Hola Mundo\""
        val highlighted = highlightCodeLine(line, "kt")

        assertEquals(line, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
    }

    @Test
    fun `highlightCodeLine highlights comments`() {
        val line = "// Esto es un comentario"
        val highlighted = highlightCodeLine(line, "kt")

        assertEquals(line, highlighted.text)
        assertEquals(1, highlighted.spanStyles.size)
        assertEquals(SyntaxColors.Comment, highlighted.spanStyles[0].item.color)
    }

    @Test
    fun `highlightCodeLine highlights functions and numbers`() {
        val line = "calculateTotal(42, true)"
        val highlighted = highlightCodeLine(line, "js")

        assertEquals(line, highlighted.text)
        assertTrue(highlighted.spanStyles.any { it.item.color == SyntaxColors.Function })
        assertTrue(highlighted.spanStyles.any { it.item.color == SyntaxColors.Number })
        assertTrue(highlighted.spanStyles.any { it.item.color == SyntaxColors.BooleanNull })
    }
}
