package com.idupi.app.data.phone.bubble

import com.idupi.app.data.phone.approval.ApprovalVoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice approval loop of the outbound message (phone-bubble T23).
 *
 * The owner hears and reads "Voy a enviar el mensaje por WhatsApp. ¿Está bien?"
 * and answers out loud: yes approves through the notification's own approve
 * path, "léemelo" reads the recipient and the composed text from the screen,
 * "agrégale ..." appends to the compose field, no rejects through the
 * notification's reject path. Silence, a recognizer error or the owner closing
 * the bubble fall back to the usual approval notification so nothing hangs.
 *
 * Copy is neutral Latin American Spanish (tú, no voseo). Screen text is data
 * only: it shapes the spoken read-back and never changes the decision.
 */
class BubbleVoiceApprovalTest {

    @Test
    fun `the sending app comes from the command's resource id`() {
        assertEquals("com.whatsapp", ApprovalVoiceCopy.packageFromResourceId("com.whatsapp:id/send"))
        assertEquals(
            "org.telegram.messenger",
            ApprovalVoiceCopy.packageFromResourceId("org.telegram.messenger:id/chat_send_button"),
        )
        assertEquals(null, ApprovalVoiceCopy.packageFromResourceId("Enviar"))
        assertEquals(null, ApprovalVoiceCopy.packageFromResourceId(":id/send"))
    }

    private val sendPrompt = ApprovalVoiceCopy.sendPrompt("WhatsApp")

    private fun reduce(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent,
        kind: ApprovalVoiceKind = ApprovalVoiceKind.MESSAGE,
    ): BubbleConfirmDecision = BubbleVoiceApprovalReducer.reduce(state, event, kind)

    private fun awaitingAnswer(): BubbleConfirmState =
        BubbleConfirmState.AwaitingAnswer

    private fun speakingAnswer(): BubbleConfirmState =
        BubbleConfirmState.Speaking(sendPrompt, ConfirmListenTarget.ANSWER)

    // --- prompt -----------------------------------------------------------

    @Test
    fun `the approval asks on screen and out loud`() {
        val decision = reduce(BubbleConfirmState.Idle, BubbleConfirmEvent.StartApproval(sendPrompt))

        assertEquals(speakingAnswer(), decision.state)
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(sendPrompt),
                BubbleConfirmAction.Speak(sendPrompt),
            ),
            decision.actions,
        )
    }

    @Test
    fun `a second approval never replaces the active one`() {
        listOf(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmState.Reading,
            BubbleConfirmState.Writing,
            speakingAnswer(),
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.StartApproval("otra"))

            assertEquals(state, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }

    @Test
    fun `finished speech starts listening for the answer`() {
        val decision = reduce(speakingAnswer(), BubbleConfirmEvent.TtsDone)

        assertEquals(BubbleConfirmState.AwaitingAnswer, decision.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), decision.actions)
    }

    @Test
    fun `TTS unavailable starts listening right away`() {
        val decision = reduce(speakingAnswer(), BubbleConfirmEvent.TtsUnavailable)

        assertEquals(BubbleConfirmState.AwaitingAnswer, decision.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), decision.actions)
    }

    // --- answers ----------------------------------------------------------

    @Test
    fun `an affirmative approves and speaks nothing more`() {
        val decision = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("Sí, está bien."))

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
            decision.actions,
        )
    }

    @Test
    fun `a read request reads the screen and speaks the read-back`() {
        val reading = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("léemelo"))
        assertEquals(BubbleConfirmState.Reading, reading.state)
        assertEquals(listOf(BubbleConfirmAction.ReadScreen), reading.actions)

        val spoken = reduce(
            BubbleConfirmState.Reading,
            BubbleConfirmEvent.ReadDone("Juan Pérez", "nos vemos mañana"),
        )
        val prompt = "Para Juan Pérez: nos vemos mañana. ¿Está bien?"
        assertEquals(
            BubbleConfirmState.Speaking(prompt, ConfirmListenTarget.ANSWER),
            spoken.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(prompt),
                BubbleConfirmAction.Speak(prompt),
            ),
            spoken.actions,
        )
    }

    @Test
    fun `an addition with content is written to the compose field before asking again`() {
        val writing = reduce(
            awaitingAnswer(),
            BubbleConfirmEvent.Answer("agrégale que llego a las 8"),
        )

        assertEquals(BubbleConfirmState.Writing, writing.state)
        assertEquals(
            listOf(BubbleConfirmAction.AppendToCompose("que llego a las 8")),
            writing.actions,
        )

        val spoken = reduce(BubbleConfirmState.Writing, BubbleConfirmEvent.AppendDone)
        assertEquals(
            BubbleConfirmState.Speaking(
                ApprovalVoiceCopy.PROMPT_APPENDED,
                ConfirmListenTarget.ANSWER,
            ),
            spoken.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(ApprovalVoiceCopy.PROMPT_APPENDED),
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.PROMPT_APPENDED),
            ),
            spoken.actions,
        )
    }

    @Test
    fun `a failed append asks whether to send it as it is`() {
        val written = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("y una nota"))
        assertEquals(BubbleConfirmState.Writing, written.state)

        val spoken = reduce(BubbleConfirmState.Writing, BubbleConfirmEvent.AppendFailed)
        assertEquals(
            BubbleConfirmState.Speaking(
                ApprovalVoiceCopy.PROMPT_APPEND_FAILED,
                ConfirmListenTarget.ANSWER,
            ),
            spoken.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(ApprovalVoiceCopy.PROMPT_APPEND_FAILED),
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.PROMPT_APPEND_FAILED),
            ),
            spoken.actions,
        )
    }

    @Test
    fun `a bare add intent asks what to add and appends the heard words`() {
        val asking = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("agrega algo"))
        assertEquals(
            BubbleConfirmState.Speaking(BubbleVoiceConfirm.PROMPT_ADD, ConfirmListenTarget.ADDITION),
            asking.state,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_ADD),
                BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_ADD),
            ),
            asking.actions,
        )

        val listening = reduce(asking.state, BubbleConfirmEvent.TtsDone)
        assertEquals(BubbleConfirmState.AwaitingAddition, listening.state)
        assertEquals(listOf(BubbleConfirmAction.StartRecognizer), listening.actions)

        val writing = reduce(
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmEvent.Answer("que salí antes"),
        )
        assertEquals(BubbleConfirmState.Writing, writing.state)
        assertEquals(
            listOf(BubbleConfirmAction.AppendToCompose("que salí antes")),
            writing.actions,
        )
    }

    @Test
    fun `a plain no while asked what to add rejects instead of appending no`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmEvent.Answer("no"),
        )

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.MESSAGE_REJECTED),
                BubbleConfirmAction.Reject,
            ),
            decision.actions,
        )
    }

    @Test
    fun `a cancel rejects and says it did not send`() {
        val decision = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("no lo envíes"))

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.MESSAGE_REJECTED),
                BubbleConfirmAction.Reject,
            ),
            decision.actions,
        )
    }

    // --- stopping ---------------------------------------------------------

    @Test
    fun `silence falls back to the notification`() {
        listOf(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.NoSpeech)

            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.PostFallbackNotification,
                ),
                decision.actions,
            )
        }
    }

    @Test
    fun `an empty answer falls back to the notification`() {
        val decision = reduce(awaitingAnswer(), BubbleConfirmEvent.Answer("   "))

        assertEquals(BubbleConfirmState.Idle, decision.state)
        assertEquals(
            listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.PostFallbackNotification,
            ),
            decision.actions,
        )
    }

    @Test
    fun `closing the bubble falls back to the notification`() {
        listOf(
            speakingAnswer(),
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
            BubbleConfirmState.Reading,
            BubbleConfirmState.Writing,
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.Cancelled)

            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(
                    BubbleConfirmAction.StopTts,
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.PostFallbackNotification,
                ),
                decision.actions,
            )
        }
    }

    @Test
    fun `typing or tapping send falls back instead of approving`() {
        listOf(
            BubbleConfirmEvent.UserTyped,
            BubbleConfirmEvent.SendTapped,
        ).forEach { event ->
            val decision = reduce(awaitingAnswer(), event)

            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertEquals(
                listOf(
                    BubbleConfirmAction.StopTts,
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.PostFallbackNotification,
                ),
                decision.actions,
            )
        }
    }

    @Test
    fun `an expired or externally decided approval stops without a notification`() {
        listOf(
            BubbleConfirmEvent.Expired,
            BubbleConfirmEvent.DecidedElsewhere,
        ).forEach { event ->
            listOf(
                speakingAnswer(),
                BubbleConfirmState.AwaitingAnswer,
                BubbleConfirmState.Reading,
            ).forEach { state ->
                val decision = reduce(state, event)

                assertEquals(BubbleConfirmState.Idle, decision.state)
                assertEquals(
                    listOf(BubbleConfirmAction.StopTts, BubbleConfirmAction.HidePrompt),
                    decision.actions,
                )
            }
        }
    }

    @Test
    fun `stale callbacks change nothing`() {
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
            BubbleConfirmEvent.AppendDone,
            BubbleConfirmEvent.AppendFailed,
            BubbleConfirmEvent.ReadDone("Juan", "hola"),
            BubbleConfirmEvent.FinalTranscript("revisa mi correo"),
        ).forEach { event ->
            val decision = reduce(BubbleConfirmState.Idle, event)

            assertEquals("'$event' on idle", BubbleConfirmState.Idle, decision.state)
            assertTrue("'$event' on idle", decision.actions.isEmpty())
        }
    }

    @Test
    fun `a late answer while the prompt is spoken is ignored`() {
        val decision = reduce(speakingAnswer(), BubbleConfirmEvent.Answer("sí"))

        assertEquals(speakingAnswer(), decision.state)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `a heard answer while the field is being written is ignored`() {
        listOf(
            BubbleConfirmState.Reading,
            BubbleConfirmState.Writing,
        ).forEach { state ->
            val decision = reduce(state, BubbleConfirmEvent.Answer("sí"))

            assertEquals(state, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }

    // --- copy -------------------------------------------------------------

    @Test
    fun `the approval copy is neutral Spanish`() {
        assertEquals(
            "Voy a enviar el mensaje por WhatsApp. ¿Está bien?",
            ApprovalVoiceCopy.sendPrompt("WhatsApp"),
        )
        assertEquals(
            "Voy a enviar el mensaje por esta app. ¿Está bien?",
            ApprovalVoiceCopy.sendPrompt(null),
        )
        assertEquals(
            "Voy a enviar el mensaje por esta app. ¿Está bien?",
            ApprovalVoiceCopy.sendPrompt("   "),
        )
        assertEquals("Listo, agregué eso. ¿Está bien?", ApprovalVoiceCopy.PROMPT_APPENDED)
        assertEquals(
            "No pude agregar el texto. ¿Lo envío así?",
            ApprovalVoiceCopy.PROMPT_APPEND_FAILED,
        )
        assertEquals("No lo envié.", ApprovalVoiceCopy.MESSAGE_REJECTED)
        assertEquals("Dime qué agrego", BubbleVoiceConfirm.PROMPT_ADD)
    }

    @Test
    fun `the read-back keeps the parts it has and omits the rest`() {
        assertEquals(
            "Para Juan Pérez: nos vemos mañana. ¿Está bien?",
            ApprovalVoiceCopy.readPrompt("Juan Pérez", "nos vemos mañana"),
        )
        assertEquals(
            "Para Juan Pérez. ¿Está bien?",
            ApprovalVoiceCopy.readPrompt("Juan Pérez", null),
        )
        assertEquals(
            "nos vemos mañana. ¿Está bien?",
            ApprovalVoiceCopy.readPrompt("   ", "nos vemos mañana"),
        )
        assertEquals(
            "¿Está bien?",
            ApprovalVoiceCopy.readPrompt(null, ""),
        )
        assertEquals(
            "Para Juan Pérez: hola mundo. ¿Está bien?",
            ApprovalVoiceCopy.readPrompt("  Juan   Pérez ", " hola\nmundo "),
        )
    }

    @Test
    fun `the read-back truncates a very long message`() {
        val long = "a".repeat(ApprovalVoiceCopy.MAX_READ_CHARS + 50)

        assertEquals(
            "Para Juan: " + "a".repeat(ApprovalVoiceCopy.MAX_READ_CHARS) + "…. ¿Está bien?",
            ApprovalVoiceCopy.readPrompt("Juan", long),
        )
    }

    // --- cache and uninstall prompts (phone-dashboard T4) -----------------

    private val cachePrompt = ApprovalVoiceCopy.cachePrompt("WhatsApp")
    private val uninstallPrompt = ApprovalVoiceCopy.uninstallPrompt("WhatsApp")

    @Test
    fun `cache and uninstall copy names the app and falls back to esta app`() {
        assertEquals(
            "Voy a borrar la caché de WhatsApp. ¿Está bien?",
            ApprovalVoiceCopy.cachePrompt("WhatsApp"),
        )
        assertEquals(
            "Voy a borrar la caché de esta app. ¿Está bien?",
            ApprovalVoiceCopy.cachePrompt(null),
        )
        assertEquals(
            "Voy a borrar la caché de esta app. ¿Está bien?",
            ApprovalVoiceCopy.cachePrompt("   "),
        )
        assertEquals(
            "Voy a desinstalar WhatsApp. ¿Está bien?",
            ApprovalVoiceCopy.uninstallPrompt("WhatsApp"),
        )
        assertEquals(
            "Voy a desinstalar esta app. ¿Está bien?",
            ApprovalVoiceCopy.uninstallPrompt(" "),
        )
        assertEquals("Responde sí o no.", ApprovalVoiceCopy.PROMPT_YES_NO)
        assertEquals("No borré la caché.", ApprovalVoiceCopy.REJECTED_CACHE)
        assertEquals("No desinstalé la app.", ApprovalVoiceCopy.REJECTED_UNINSTALL)
    }

    // --- data wipe and any other destructive step (phone-dashboard T6) ----

    private val dataWipePrompt = ApprovalVoiceCopy.dataWipePrompt("WhatsApp")

    @Test
    fun `the data wipe copy names the app and what is lost`() {
        assertEquals(
            "Voy a borrar los datos de WhatsApp. Se cierra la sesión y se pierden sus archivos en el teléfono. ¿Estás seguro?",
            ApprovalVoiceCopy.dataWipePrompt("WhatsApp"),
        )
        assertEquals(
            "Voy a borrar los datos de esta app. Se cierra la sesión y se pierden sus archivos en el teléfono. ¿Estás seguro?",
            ApprovalVoiceCopy.dataWipePrompt(null),
        )
        assertEquals(
            "Voy a borrar los datos de esta app. Se cierra la sesión y se pierden sus archivos en el teléfono. ¿Estás seguro?",
            ApprovalVoiceCopy.dataWipePrompt("   "),
        )
        assertEquals("No borré los datos.", ApprovalVoiceCopy.REJECTED_DATA_WIPE)
    }

    @Test
    fun `the generic destructive copy names the action and the screen title`() {
        assertEquals(
            "Voy a eliminar la foto. No se puede deshacer. ¿Estás seguro?",
            ApprovalVoiceCopy.destructivePrompt("Eliminar", "la foto"),
        )
        assertEquals(
            "Voy a borrar descargas. No se puede deshacer. ¿Estás seguro?",
            ApprovalVoiceCopy.destructivePrompt("Borrar", "Borrar descargas"),
        )
        assertEquals(
            "Voy a eliminar esto. No se puede deshacer. ¿Estás seguro?",
            ApprovalVoiceCopy.destructivePrompt("Delete", null),
        )
        assertEquals(
            "Voy a eliminar esto. No se puede deshacer. ¿Estás seguro?",
            ApprovalVoiceCopy.destructivePrompt("   ", "   "),
        )
        assertEquals("No borré nada.", ApprovalVoiceCopy.REJECTED_DESTRUCTIVE)
    }

    @Test
    fun `the prompt for every destructive family is chosen by kind`() {
        assertEquals(cachePrompt, ApprovalVoiceCopy.promptFor(ApprovalVoiceKind.CACHE, "WhatsApp"))
        assertEquals(uninstallPrompt, ApprovalVoiceCopy.promptFor(ApprovalVoiceKind.UNINSTALL, "WhatsApp"))
        assertEquals(dataWipePrompt, ApprovalVoiceCopy.promptFor(ApprovalVoiceKind.DATA_WIPE, "WhatsApp"))
        assertEquals(
            "Voy a eliminar la foto. No se puede deshacer. ¿Estás seguro?",
            ApprovalVoiceCopy.promptFor(
                ApprovalVoiceKind.DESTRUCTIVE,
                null,
                subject = "la foto",
                action = "Eliminar",
            ),
        )
    }

    @Test
    fun `a data wipe approves on yes and rejects with its own copy`() {
        val start = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.StartApproval(dataWipePrompt),
            ApprovalVoiceKind.DATA_WIPE,
        )
        assertEquals(
            BubbleConfirmState.Speaking(dataWipePrompt, ConfirmListenTarget.ANSWER),
            start.state,
        )

        assertEquals(
            listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
            reduce(
                BubbleConfirmState.AwaitingAnswer,
                BubbleConfirmEvent.Answer("sí"),
                ApprovalVoiceKind.DATA_WIPE,
            ).actions,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.REJECTED_DATA_WIPE),
                BubbleConfirmAction.Reject,
            ),
            reduce(
                BubbleConfirmState.AwaitingAnswer,
                BubbleConfirmEvent.Answer("no"),
                ApprovalVoiceKind.DATA_WIPE,
            ).actions,
        )
    }

    @Test
    fun `the destructive read-back speaks the size line or the title`() {
        val reading = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("léemelo"),
            ApprovalVoiceKind.CACHE,
        )
        assertEquals(BubbleConfirmState.Reading, reading.state)
        assertEquals(listOf(BubbleConfirmAction.ReadScreen), reading.actions)

        val withSize = reduce(
            BubbleConfirmState.Reading,
            BubbleConfirmEvent.ReadDone("WhatsApp", null, "Caché 1,2 GB"),
            ApprovalVoiceKind.CACHE,
        )
        assertEquals(
            "Caché 1,2 GB. ¿Está bien?",
            (withSize.state as BubbleConfirmState.Speaking).prompt,
        )

        val withTitle = reduce(
            BubbleConfirmState.Reading,
            BubbleConfirmEvent.ReadDone("WhatsApp", null, null),
            ApprovalVoiceKind.UNINSTALL,
        )
        assertEquals(
            "WhatsApp. ¿Está bien?",
            (withTitle.state as BubbleConfirmState.Speaking).prompt,
        )
    }

    @Test
    fun `a cache approval approves on yes and rejects with its own copy`() {
        val start = reduce(
            BubbleConfirmState.Idle,
            BubbleConfirmEvent.StartApproval(cachePrompt),
            ApprovalVoiceKind.CACHE,
        )
        assertEquals(
            BubbleConfirmState.Speaking(cachePrompt, ConfirmListenTarget.ANSWER),
            start.state,
        )

        val listening = reduce(start.state, BubbleConfirmEvent.TtsDone, ApprovalVoiceKind.CACHE)
        assertEquals(BubbleConfirmState.AwaitingAnswer, listening.state)

        assertEquals(
            listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
            reduce(
                BubbleConfirmState.AwaitingAnswer,
                BubbleConfirmEvent.Answer("sí"),
                ApprovalVoiceKind.CACHE,
            ).actions,
        )
        assertEquals(
            listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.REJECTED_CACHE),
                BubbleConfirmAction.Reject,
            ),
            reduce(
                BubbleConfirmState.AwaitingAnswer,
                BubbleConfirmEvent.Answer("no"),
                ApprovalVoiceKind.CACHE,
            ).actions,
        )
    }

    @Test
    fun `an addition on a destructive prompt keeps the yes or no rule`() {
        val destructivePrompt = ApprovalVoiceCopy.destructivePrompt("Eliminar", "la foto")
        listOf(
            ApprovalVoiceKind.CACHE to cachePrompt,
            ApprovalVoiceKind.UNINSTALL to uninstallPrompt,
            ApprovalVoiceKind.DATA_WIPE to dataWipePrompt,
            ApprovalVoiceKind.DESTRUCTIVE to destructivePrompt,
        ).forEach { (kind, prompt) ->
            listOf("agrégale algo", "agregar", "una nota más").forEach { answer ->
                val decision = reduce(
                    BubbleConfirmState.AwaitingAnswer,
                    BubbleConfirmEvent.Answer(answer),
                    kind,
                )

                assertEquals(
                    "'$answer' on $prompt asks yes or no",
                    BubbleConfirmState.Speaking(
                        ApprovalVoiceCopy.PROMPT_YES_NO,
                        ConfirmListenTarget.ANSWER,
                    ),
                    decision.state,
                )
                assertEquals(
                    listOf(
                        BubbleConfirmAction.ShowPrompt(ApprovalVoiceCopy.PROMPT_YES_NO),
                        BubbleConfirmAction.Speak(ApprovalVoiceCopy.PROMPT_YES_NO),
                    ),
                    decision.actions,
                )
            }
        }
    }

    @Test
    fun `the messaging flow still treats an addition as content`() {
        val decision = reduce(
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmEvent.Answer("agrégale que llego a las 8"),
            ApprovalVoiceKind.MESSAGE,
        )

        assertEquals(BubbleConfirmState.Writing, decision.state)
        assertEquals(
            listOf(BubbleConfirmAction.AppendToCompose("que llego a las 8")),
            decision.actions,
        )
    }

    // --- Aprobar / Rechazar buttons (phone-dashboard T4) ------------------

    @Test
    fun `the buttons decide from any active loop state`() {
        listOf(
            speakingAnswer(),
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.Reading,
            BubbleConfirmState.Writing,
        ).forEach { state ->
            assertEquals(
                "$state approves",
                BubbleConfirmDecision(
                    state = BubbleConfirmState.Idle,
                    actions = listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
                ),
                reduce(state, BubbleConfirmEvent.ButtonApprove),
            )
            assertEquals(
                "$state rejects",
                BubbleConfirmDecision(
                    state = BubbleConfirmState.Idle,
                    actions = listOf(
                        BubbleConfirmAction.HidePrompt,
                        BubbleConfirmAction.Speak(ApprovalVoiceCopy.MESSAGE_REJECTED),
                        BubbleConfirmAction.Reject,
                    ),
                ),
                reduce(state, BubbleConfirmEvent.ButtonReject),
            )
        }
    }

    @Test
    fun `the buttons on a destructive prompt use its rejection copy`() {
        listOf(
            ApprovalVoiceKind.CACHE to ApprovalVoiceCopy.REJECTED_CACHE,
            ApprovalVoiceKind.UNINSTALL to ApprovalVoiceCopy.REJECTED_UNINSTALL,
            ApprovalVoiceKind.DATA_WIPE to ApprovalVoiceCopy.REJECTED_DATA_WIPE,
            ApprovalVoiceKind.DESTRUCTIVE to ApprovalVoiceCopy.REJECTED_DESTRUCTIVE,
        ).forEach { (kind, rejection) ->
            assertEquals(
                "$kind rejects with its own copy",
                listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.Speak(rejection),
                    BubbleConfirmAction.Reject,
                ),
                reduce(
                    BubbleConfirmState.AwaitingAnswer,
                    BubbleConfirmEvent.ButtonReject,
                    kind,
                ).actions,
            )
        }
    }

    @Test
    fun `a button tap outside the loop decides nothing`() {
        listOf(BubbleConfirmEvent.ButtonApprove, BubbleConfirmEvent.ButtonReject).forEach { event ->
            val decision = reduce(BubbleConfirmState.Idle, event)

            assertEquals(BubbleConfirmState.Idle, decision.state)
            assertTrue(decision.actions.isEmpty())
        }
    }
}
