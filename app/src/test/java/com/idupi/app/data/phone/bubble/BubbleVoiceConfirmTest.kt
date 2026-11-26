package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice confirmation loop and reply classifier (phone-bubble T21).
 *
 * The owner's ask, "como Android Auto con Gemini": after a dictated request the
 * bubble reads it back on screen and out loud ("¿Lo envío o quieres agregar
 * algo?") and her spoken answer decides what happens next. The classifier and
 * the loop live in one Android-free file so the whole conversation is pinned
 * here; the overlay only starts/stops the recognizer, TextToSpeech and the
 * field writes.
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
        // verb it stays Other so the loop appends it instead of dropping it.
        assertEquals(ConfirmReply.Other("para Juan"), classify("para Juan"))
    }

    @Test
    fun `a silent answer has no meaning`() {
        assertEquals(ConfirmReply.Other(""), classify(""))
        assertEquals(ConfirmReply.Other(""), classify("   "))
    }

    @Test
    fun `plain no is the short reply the follow-up sends directly`() {
        assertTrue(BubbleVoiceConfirm.isPlainNo("no"))
        assertTrue(BubbleVoiceConfirm.isPlainNo("No."))
        assertTrue(BubbleVoiceConfirm.isPlainNo(" NO "))
        assertFalse(BubbleVoiceConfirm.isPlainNo("no lo envíes"))
        assertFalse(BubbleVoiceConfirm.isPlainNo("sí"))
    }

    @Test
    fun `the read-back copy is neutral Spanish`() {
        assertEquals("¿Lo envío o quieres agregar algo?", BubbleVoiceConfirm.PROMPT_CONFIRM)
        assertEquals("Dime qué agrego", BubbleVoiceConfirm.PROMPT_ADD)
    }

    // --- loop: read-back --------------------------------------------------

    @Test
    fun `a dictated final asks on screen and out loud`() {
        val decision = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.FinalTranscript("revisa mi correo"),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_CONFIRM,
                ConfirmListenTarget.ANSWER,
            ),
            decision.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_CONFIRM),
                BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_CONFIRM),
            ),
            decision.actions,
        )
    }

    @Test
    fun `a blank final transcript never starts the loop`() {
        val decision = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.FinalTranscript("   "),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `finished speech starts listening for the answer`() {
        val decision = reduce(
            speakingAnswer(),
            BubbleConfirmEvent.TtsDone,
        )

        assertEquals(BubbleConfirmState.AwaitingAnswer, decision.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), decision.actions)
    }

    @Test
    fun `TTS unavailable starts listening right away`() {
        val decision = reduce(
            speakingAnswer(),
            BubbleConfirmEvent.TtsUnavailable,
        )

        assertEquals(BubbleConfirmState.AwaitingAnswer, decision.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), decision.actions)
    }

    // --- loop: answers ----------------------------------------------------

    @Test
    fun `an affirmative answer sends and clears the prompt`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("Sí, envíalo."),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Send),
            decision.actions,
        )
    }

    @Test
    fun `an answer with an addition appends it and asks again`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("agrega que mañana es feriado"),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_CONFIRM,
                ConfirmListenTarget.ANSWER,
            ),
            decision.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.AppendText("que mañana es feriado"),
                BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_CONFIRM),
                BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_CONFIRM),
            ),
            decision.actions,
        )
    }

    @Test
    fun `a bare add intent asks what to add`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("agregar algo"),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_ADD,
                ConfirmListenTarget.ADDITION,
            ),
            decision.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_ADD),
                BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_ADD),
            ),
            decision.actions,
        )
    }

    @Test
    fun `the addition prompt listens for the addition once spoken`() {
        val decision = reduce(
            speakingAddition(),
            BubbleConfirmEvent.TtsDone,
        )

        assertEquals(BubbleConfirmState.AwaitingAddition, decision.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), decision.actions)
    }

    @Test
    fun `a heard addition is appended as-is and the request is read back`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmEvent.Answer("que mañana es feriado"),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_CONFIRM,
                ConfirmListenTarget.ANSWER,
            ),
            decision.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.AppendText("que mañana es feriado"),
                BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_CONFIRM),
                BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_CONFIRM),
            ),
            decision.actions,
        )
    }

    @Test
    fun `an unclassified answer is appended whole and asked again`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("a Juan"),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_CONFIRM,
                ConfirmListenTarget.ANSWER,
            ),
            decision.state,
        )
        assertEquals(BubbleConfirmAction.AppendText("a Juan"), decision.actions.first())
    }

    @Test
    fun `a cancel stops the loop and keeps the text`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("no lo envíes"),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(listOf(BubbleConfirmAction.HidePrompt), decision.actions)
    }

    // --- loop: stopping ---------------------------------------------------

    @Test
    fun `silence and recognizer errors stop the loop`() {
        listOf(BubbleConfirmState.AwaitingAnswer, BubbleConfirmState.AwaitingAddition)
            .forEach { state ->
                val decision = reduce(state, BubbleConfirmEvent.NoSpeech)
                assertEquals(BubbleConfirmState.Idle, decision.state)
                assertEquals(listOf(BubbleConfirmAction.HidePrompt), decision.actions)
            }
    }

    @Test
    fun `typing while the loop runs stops it and silences the prompt`() {
        listOf(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
            speakingAnswer(),
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.UserTyped)
            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(BubbleConfirmAction.StopTts, BubbleConfirmAction.HidePrompt),
                decision.actions,
            )
        }
    }

    @Test
    fun `tapping send while the loop runs stops it`() {
        val decision = reduce(BubbleConfirmState.AwaitingAnswer, BubbleConfirmEvent.SendTapped)

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.StopTts, BubbleConfirmAction.HidePrompt),
            decision.actions,
        )
    }

    @Test
    fun `the owner can cancel while the prompt is being spoken`() {
        val decision = reduce(speakingAnswer(), BubbleConfirmEvent.Cancelled)

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.StopTts, BubbleConfirmAction.HidePrompt),
            decision.actions,
        )
    }

    @Test
    fun `callbacks after the loop stopped change nothing`() {
        listOf(
            BubbleConfirmEvent.TtsDone,
            BubbleConfirmEvent.TtsUnavailable,
            BubbleConfirmEvent.Answer("sí"),
            BubbleConfirmEvent.NoSpeech,
            BubbleConfirmEvent.UserTyped,
            BubbleConfirmEvent.SendTapped,
            BubbleConfirmEvent.Cancelled,
        ).forEach { event ->
            val decision = reduce(BubbleConfirmState.Idle, event)
            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }

    @Test
    fun `a late answer while the prompt is spoken is ignored`() {
        val decision = reduce(speakingAnswer(), BubbleConfirmEvent.Answer("sí"))

        assertEquals(speakingAnswer(), decision.state)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `a blank final while the prompt is spoken stops the speech`() {
        val decision = reduce(
            speakingAnswer(),
            BubbleConfirmEvent.FinalTranscript("   "),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.StopTts, BubbleConfirmAction.HidePrompt),
            decision.actions,
        )
    }

    // --- T17: short reply to the agent's question -------------------------

    @Test
    fun `a dictated yes or plain no to the agent is sent directly`() {
        listOf("Sí.", "sí", "dale", "No.", "no").forEach { answer ->
            val decision = reduce(
                BubbleConfirmState.Idle,
                BubbleConfirmEvent.FinalTranscript(answer, followUp = true),
            )

            assertEquals("'$answer' goes straight to the agent", BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Send),
                decision.actions,
            )
        }
    }

    @Test
    fun `a longer reply to the agent goes through the read-back`() {
        val decision = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.FinalTranscript("a Juan", followUp = true),
        )

        assertEquals(
            BubbleConfirmState.Speaking(
                BubbleVoiceConfirm.PROMPT_CONFIRM,
                ConfirmListenTarget.ANSWER,
            ),
            decision.state,
        )
        assertEquals(BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_CONFIRM), decision.actions.first())

        // Only a plain "no" is a direct answer; a longer cancel is a request.
        val longerCancel = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.FinalTranscript("no lo envíes", followUp = true),
        )
        assertTrue(longerCancel.state is BubbleConfirmState.Speaking)
    }

    @Test
    fun `a new dictated request without follow-up always reads back`() {
        listOf("sí", "no").forEach { answer ->
            val decision = reduce(
                BubbleConfirmState.Idle,
                BubbleConfirmEvent.FinalTranscript(answer, followUp = false),
            )

            assertEquals(
                BubbleConfirmState.Speaking(
                    BubbleVoiceConfirm.PROMPT_CONFIRM,
                    ConfirmListenTarget.ANSWER,
                ),
                decision.state,
            )
        }
    }

    private fun speakingAnswer(): BubbleConfirmState = BubbleConfirmState.Speaking(
        BubbleVoiceConfirm.PROMPT_CONFIRM,
        ConfirmListenTarget.ANSWER,
    )

    private fun speakingAddition(): BubbleConfirmState = BubbleConfirmState.Speaking(
        BubbleVoiceConfirm.PROMPT_ADD,
        ConfirmListenTarget.ADDITION,
    )
}
