package com.idupi.app.data.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Respuesta del agente lista" ping (alerts-flow A3): the title is fixed
 * and the body is the owner's own instruction, so the notification never
 * carries third-party alert text or an agent answer back into the shade.
 */
class InboxAgentAnswerTest {

    @Test
    fun `title is the fixed owner-facing line`() {
        assertEquals("Respuesta del agente lista", InboxAgentAnswer.TITLE)
    }

    @Test
    fun `text uses the first non-blank line of the instruction`() {
        val text = InboxAgentAnswer.notificationText(
            "\n  revisa si este error de Render afecta mis proyectos\ncon urgencia"
        )

        assertEquals("revisa si este error de Render afecta mis proyectos", text)
    }

    @Test
    fun `text falls back when the instruction is empty`() {
        assertEquals("La investigación terminó", InboxAgentAnswer.notificationText("  \n "))
    }

    @Test
    fun `text caps a very long first line`() {
        val text = InboxAgentAnswer.notificationText("x".repeat(300))

        assertEquals(InboxAgentAnswer.MAX_TEXT_CHARS, text.length)
        assertTrue(text.endsWith("…"))
    }
}
