package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice follow-up rules (phone-bubble T17).
 *
 * The owner's ask: "si envío un dictado de voz y el agente me devuelve una
 * duda, debe mostrarme el mensaje y dejarme activado el dictado de voz". Only a
 * request she dictated, answered with a question by a finished turn, reopens
 * the microphone; a typed request, a statement or a failed turn never does.
 *
 * The bubble is only allowed to leave the answer visible and the mic ready.
 * Since T21 the dictated reply is classified by the voice confirmation loop: a
 * short yes/plain "no" is sent straight to the agent, anything longer is read
 * back before sending.
 */
class BubbleVoiceFollowUpTest {

    private fun shouldListen(
        origin: BubbleRequestOrigin = BubbleRequestOrigin.DICTATED,
        phase: BubbleChatPhase = BubbleChatPhase.DONE,
        answer: String? = "¿Quieres que lo envíe?",
    ): Boolean = BubbleVoiceFollowUp.shouldListen(origin, phase, answer)

    @Test
    fun `a dictated request answered with a question listens again`() {
        assertTrue(shouldListen(answer = "¿Quieres que lo envíe?"))
        assertTrue(shouldListen(answer = "Listo, pero ¿lo envío ahora?"))
        assertTrue(shouldListen(answer = "Puedo mandarlo ahora?"))
        assertTrue(shouldListen(answer = "  ¿Te lo leo?  "))
    }

    @Test
    fun `a typed request answered with a question stays quiet`() {
        assertFalse(shouldListen(origin = BubbleRequestOrigin.TYPED))
    }

    @Test
    fun `a dictated request answered with a statement stays quiet`() {
        assertFalse(shouldListen(answer = "Listo, ya abrí WhatsApp."))
        assertFalse(shouldListen(answer = ""))
        assertFalse(shouldListen(answer = "   "))
        assertFalse(shouldListen(answer = null))
    }

    @Test
    fun `a failed turn never listens`() {
        assertFalse(
            shouldListen(phase = BubbleChatPhase.FAILED, answer = "¿Quieres que lo intente otra vez?"),
        )
        assertFalse(
            shouldListen(phase = BubbleChatPhase.FAILED, answer = "El agente no pudo completar el pedido."),
        )
    }

    @Test
    fun `a turn that has not finished does not listen`() {
        assertFalse(shouldListen(phase = BubbleChatPhase.SENDING))
        assertFalse(shouldListen(phase = BubbleChatPhase.RUNNING))
        assertFalse(shouldListen(phase = BubbleChatPhase.IDLE))
    }

    @Test
    fun `question detection uses the two markers the task names`() {
        assertTrue(BubbleVoiceFollowUp.isQuestion("¿Lo envío?"))
        assertTrue(BubbleVoiceFollowUp.isQuestion("Ya está. ¿Lo mando?"))
        assertTrue(BubbleVoiceFollowUp.isQuestion("Puedo enviarlo?"))
        assertFalse(BubbleVoiceFollowUp.isQuestion("Ya está enviado."))
        assertFalse(BubbleVoiceFollowUp.isQuestion("   "))
        assertFalse(BubbleVoiceFollowUp.isQuestion(""))
    }
}
