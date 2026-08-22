package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSelectionTest {

    private fun msg(id: String, text: String, sender: MessageSender = MessageSender.PI) =
        ChatMessage(id = id, sender = sender, text = text)

    private val chat = listOf(
        msg("1", "¿Cuántos archivos .kt hay?", MessageSender.USER),
        msg("2", "Son 60."),
        msg("3", "HERRAMIENTA Glob", MessageSender.TOOL),
        msg("4", "Listo.")
    )

    @Test
    fun `one selected message copies exactly its text`() {
        assertEquals("Son 60.", copyTextOf(chat, setOf("2")))
    }

    @Test
    fun `several messages are copied in screen order, not tap order`() {
        // Selecting the last one first must not paste it first: the order they
        // were read in is the order they were shown in.
        val text = copyTextOf(chat, linkedSetOf("4", "1"))
        assertEquals("¿Cuántos archivos .kt hay?\n\nListo.", text)
    }

    @Test
    fun `messages are copied as themselves, with no invented labels`() {
        val text = copyTextOf(chat, setOf("1", "2"))
        assertTrue(text.startsWith("¿Cuántos"))
        assertTrue("no debe agregar prefijos de hablante", !text.contains("Pi:"))
    }

    @Test
    fun `tool and subagent cards are copyable like any other message`() {
        assertEquals("HERRAMIENTA Glob", copyTextOf(chat, setOf("3")))
    }

    @Test
    fun `an empty selection copies nothing`() {
        assertEquals("", copyTextOf(chat, emptySet()))
    }

    @Test
    fun `a blank message does not add empty separators`() {
        val withBlank = chat + msg("5", "   ")
        assertEquals("Son 60.", copyTextOf(withBlank, setOf("2", "5")))
    }

    @Test
    fun `an id that is no longer in the chat is ignored`() {
        assertEquals("Son 60.", copyTextOf(chat, setOf("2", "borrado")))
    }

    @Test
    fun `toggling adds then removes`() {
        val once = emptySet<String>().toggled("2")
        assertEquals(setOf("2"), once)
        assertEquals(emptySet<String>(), once.toggled("2"))
    }
}
