package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoken reply classifier and dictated-send rule (phone-bubble T21, updated by
 * T23).
 *
 * Owner rule, 2026-11-26: a dictated request is NOT read back any more
 * ("quita la de T21"). A final transcript goes straight to the agent through
 * the same path as the send button, and that includes a dictated reply to an
 * agent question (the T17 follow-up keeps reopening the microphone). The same
 * classifier still serves the spoken approval of an outbound message (T23),
 * which is why it stays here and is pinned by these tests.
 *
 * Copy is neutral Latin American Spanish (tú, no voseo).
 */
class BubbleVoiceConfirmTest {

    private fun classify(answer: String): ConfirmReply = BubbleVoiceConfirm.classify(answer)

    private fun reduce(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent,
    ): BubbleConfirmDecision = BubbleVoiceConfirmReducer.reduce(state, event)

    // --- classifier -------------------------------------------------------

    @Test
    fun `affirmative answers ignore case, accents and trailing punctuation`() {
        val affirmatives = listOf(
            "sí",
            "si",
            "Sí.",
            "SÍ",
            "sí, envíalo.",
            "Sí envíalo",
            "envíalo",
            "envía",
            "envialo",
            "Está bien.",
            "esta bien",
            "ok",
            "Okay",
            "dale",
            "Listo.",
            "correcto",
            "perfecto",
            "de acuerdo",
            "claro",
            "sí está bien",
            "mándalo",
            "mandalo",
        )

        affirmatives.forEach { answer ->
            assertEquals("'$answer' reads as affirmative", ConfirmReply.Affirmative, classify(answer))
        }
    }

    @Test
    fun `cancel answers are whole-answer matches only`() {
        val cancels = listOf(
            "no",
            "No.",
            "cancela",
            "Cancelar.",
            "olvídalo",
            "olvidalo",
            "déjalo",
            "dejalo",
            "no lo envíes",
            "No lo envíes.",
            "para",
            "Para.",
        )

        cancels.forEach { answer ->
            assertEquals("'$answer' reads as cancel", ConfirmReply.Cancel, classify(answer))
        }
        // A word that only contains a cancel verb is not a cancel.
        assertTrue(classify("cancela la reunión de mañana") is ConfirmReply.Other)
    }

    @Test
    fun `read requests are recognized with accents, case and punctuation`() {
        val reads = listOf(
            "léemelo",
            "leemelo",
            "léelo",
            "leelo",
            "lee",
            "Lee.",
            "lee el mensaje",
            "Lee el mensaje.",
            "qué dice",
            "Que dice",
            "¿Qué dice?",
            "repítelo",
            "repitelo",
            "repite",
            "Repite.",
        )

        reads.forEach { answer ->
            assertEquals("'$answer' asks to read it", ConfirmReply.Read, classify(answer))
        }
    }

    @Test
    fun `read words inside a longer sentence are not a read request`() {
        assertTrue(classify("lee el mensaje de Juan y cópialo") is ConfirmReply.Other)
        assertTrue(classify("repite el pedido") is ConfirmReply.Other)
    }

    @Test
    fun `add intents without content ask what to add`() {
        val addOnly = listOf(
            "agrega",
            "agregar",
            "Agrégale.",
            "añade",
            "añadir",
            "Añádele.",
            "y también",
            "también",
            "además",
            "quiero agregar algo",
            "quiero agregar",
            "sí quiero agregar",
            "Sí, quiero agregar.",
            "agregar algo",
            "Quiero añadir algo",
        )

        addOnly.forEach { answer ->
            assertEquals("'$answer' asks what to add", ConfirmReply.AddOnly, classify(answer))
        }
    }

    @Test
    fun `an add verb followed by words becomes the addition`() {
        assertEquals(
            ConfirmReply.AddWithContent("que mañana tengo reunión"),
            classify("agrega que mañana tengo reunión"),
        )
        assertEquals(
            ConfirmReply.AddWithContent("dile a Juan que llego tarde"),
            classify("Y también dile a Juan que llego tarde"),
        )
        assertEquals(
            ConfirmReply.AddWithContent("por favor, la fecha"),
            classify("Añade, por favor, la fecha."),
        )
        assertEquals(
            ConfirmReply.AddWithContent("avísale a Pedro"),
            classify("Además avísale a Pedro"),
        )
        assertEquals(
            ConfirmReply.AddWithContent("la leche"),
            classify("agrégale la leche"),
        )
        assertEquals(
            ConfirmReply.AddWithContent("una nota"),
            classify("añádele una nota"),
        )
        assertEquals(
            ConfirmReply.AddWithContent("que salí antes"),
            classify("también que salí antes"),
        )
    }

    @Test
    fun `anything else non-empty is Other with the whole answer`() {
        assertEquals(ConfirmReply.Other("a Juan"), classify("a Juan"))
        assertEquals(ConfirmReply.Other("mañana a las 10"), classify("  mañana a las 10  "))
        assertEquals(ConfirmReply.Other("¿lo envío?"), classify("¿lo envío?"))
        // "para Juan" starts with a cancel word but is content, and with no add
        // verb it stays Other so the approval loop appends it instead of dropping it.
        assertEquals(ConfirmReply.Other("para Juan"), classify("para Juan"))
    }

    @Test
    fun `a silent answer has no meaning`() {
        assertEquals(ConfirmReply.Other(""), classify(""))
        assertEquals(ConfirmReply.Other(""), classify("   "))
    }

    // --- T23: a dictated final is sent straight to the agent --------------

    @Test
    fun `a dictated request is sent straight to the agent without a read-back`() {
        listOf(
            "revisa mi correo",
            "sí",
            "no",
            "abre WhatsApp y dime qué me escribió Juan",
        ).forEach { text ->
            val decision = reduce(
                BubbleConfirmState.Idle,
                BubbleConfirmEvent.FinalTranscript(text),
            )

            assertEquals("'$text' goes straight to the agent", BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Send),
                decision.actions,
            )
        }
    }

    @Test
    fun `a blank final transcript sends nothing`() {
        val decision = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.FinalTranscript("   "),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `a final transcript while the approval loop runs is not a request`() {
        listOf(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmState.Reading,
            BubbleConfirmState.Writing,
            BubbleConfirmState.Speaking(
                ApprovalVoiceCopy.sendPrompt("WhatsApp"),
                ConfirmListenTarget.ANSWER,
            ),
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.FinalTranscript("sí"))

            assertEquals(state, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }

    @Test
    fun `loop events outside the approval flow change nothing`() {
        listOf(
            BubbleConfirmEvent.TtsDone,
            BubbleConfirmEvent.TtsUnavailable,
            BubbleConfirmEvent.Answer("sí"),
            BubbleConfirmEvent.NoSpeech,
            BubbleConfirmEvent.Cancelled,
            BubbleConfirmEvent.UserTyped,
            BubbleConfirmEvent.SendTapped,
            BubbleConfirmEvent.Expired,
            BubbleConfirmEvent.DecidedElsewhere,
        ).forEach { event ->
            val decision = reduce(BubbleConfirmState.Idle, event)

            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }
}
