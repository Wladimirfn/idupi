package com.idupi.app.data.phone.bubble

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.domain.model.PhoneAgentTurn
import com.idupi.app.domain.model.PhoneAgentTurnStartResult
import com.idupi.app.domain.model.PhoneAgentTurnStatus
import com.idupi.app.domain.model.PhoneAgentTurnStep
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The bubble chat reducer (phone-bubble T6): one owner request becomes a POST,
 * then a long poll until a terminal status, rendered as owner text, step lines,
 * the answer, or an error the owner can retry.
 *
 * The controller takes its scope from the test, so `delay()` in the poll retry
 * runs on virtual time and the assertions are deterministic.
 */
class BubbleChatControllerTest {

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun TestScope.controller(): BubbleChatController =
        BubbleChatController(
            clientSource = FakeClientSource(fake),
            scope = this,
            pollWaitSeconds = 25,
            retryDelayMillis = 2_000L,
        )

    private fun step(kind: String, summary: String) =
        PhoneAgentTurnStep(at = "2026-11-26T12:00:00.000Z", kind = kind, summary = summary)

    private fun turn(
        id: String = "pat-1a2b3c4d5e6f",
        status: String = PhoneAgentTurnStatus.RUNNING,
        text: String = "revisa mi correo",
        steps: List<PhoneAgentTurnStep> = emptyList(),
        answer: String = "",
        error: String? = null,
    ) = PhoneAgentTurn(
        id = id,
        text = text,
        status = status,
        answer = answer,
        steps = steps,
        error = error,
    )

    @Test
    fun `send posts the request and follows it to the answer`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(status = PhoneAgentTurnStatus.RUNNING, steps = listOf(step("started", "Pedido iniciado")))
        }
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(
                status = PhoneAgentTurnStatus.RUNNING,
                steps = listOf(
                    step("started", "Pedido iniciado"),
                    step("cli", "screen"),
                ),
            )
        }
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(
                status = PhoneAgentTurnStatus.DONE,
                steps = listOf(
                    step("started", "Pedido iniciado"),
                    step("cli", "screen"),
                    step("answer", "Respuesta lista"),
                ),
                answer = "Tienes 3 correos sin leer.",
            )
        }

        controller.send("  revisa mi correo  ")
        advanceUntilIdle()

        assertEquals(listOf("revisa mi correo"), fake.startedPhoneTurns)
        assertEquals(
            listOf(
                "pat-1a2b3c4d5e6f" to 25,
                "pat-1a2b3c4d5e6f" to 25,
                "pat-1a2b3c4d5e6f" to 25,
            ),
            fake.phoneTurnPolls,
        )
        val state = controller.state.value
        assertEquals(BubbleChatPhase.DONE, state.phase)
        assertEquals(
            listOf(
                BubbleChatMessage(BubbleMessageRole.OWNER, "revisa mi correo"),
                BubbleChatMessage(BubbleMessageRole.STEP, "Pedido iniciado"),
                BubbleChatMessage(BubbleMessageRole.STEP, "screen"),
                BubbleChatMessage(BubbleMessageRole.ANSWER, "Tienes 3 correos sin leer."),
            ),
            state.messages,
        )
        assertFalse(state.canRetry)
        assertNull(state.statusLine())
    }

    @Test
    fun `the thinking line is shown only while the turn runs`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        val gate = CompletableDeferred<Unit>()
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            gate.await()
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Listo")
        }

        controller.send("hola")
        runCurrent()

        assertEquals(BubbleChatPhase.RUNNING, controller.state.value.phase)
        assertEquals(BubbleChatController.MESSAGE_THINKING, controller.state.value.statusLine())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(BubbleChatPhase.DONE, controller.state.value.phase)
        assertNull(controller.state.value.statusLine())
    }

    @Test
    fun `an unavailable agent shows the 503 copy and allows retry`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Unavailable(PhoneAgentTurnStartResult.MESSAGE_UNAVAILABLE)
        )

        controller.send("revisa mi correo")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(BubbleChatPhase.FAILED, state.phase)
        assertEquals(
            listOf(
                BubbleChatMessage(BubbleMessageRole.OWNER, "revisa mi correo"),
                BubbleChatMessage(
                    BubbleMessageRole.ERROR,
                    "El agente del teléfono no está disponible en la PC.",
                ),
            ),
            state.messages,
        )
        assertTrue(state.canRetry)
    }

    @Test
    fun `invalid text maps to the invalid copy`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Invalid(PhoneAgentTurnStartResult.MESSAGE_INVALID)
        )

        controller.send("x")
        advanceUntilIdle()

        assertEquals(
            BubbleChatMessage(
                BubbleMessageRole.ERROR,
                "Escribe un pedido de hasta 2000 caracteres.",
            ),
            controller.state.value.messages.last(),
        )
        assertTrue(controller.state.value.canRetry)
    }

    @Test
    fun `a transport failure while posting maps to the connection copy`() = runTest {
        fake.failPhoneTurnStartWith = IOException("pc off")
        val controller = controller()

        controller.send("hola")
        advanceUntilIdle()

        assertEquals(BubbleChatPhase.FAILED, controller.state.value.phase)
        assertEquals(
            BubbleChatMessage(BubbleMessageRole.ERROR, PhoneAgentTurnStartResult.MESSAGE_CONNECTION),
            controller.state.value.messages.last(),
        )
        assertTrue(controller.state.value.canRetry)
    }

    @Test
    fun `a failed turn shows the failure copy and no answer`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(
                status = PhoneAgentTurnStatus.FAILED,
                steps = listOf(step("started", "Pedido iniciado")),
                error = "agent failed",
            )
        }

        controller.send("hola")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(BubbleChatPhase.FAILED, state.phase)
        assertEquals(
            listOf(
                BubbleChatMessage(BubbleMessageRole.OWNER, "hola"),
                BubbleChatMessage(BubbleMessageRole.STEP, "Pedido iniciado"),
                BubbleChatMessage(BubbleMessageRole.ERROR, BubbleChatController.MESSAGE_FAILED),
            ),
            state.messages,
        )
        assertTrue(state.canRetry)
        assertEquals(1, fake.phoneTurnPolls.size)
    }

    @Test
    fun `a cancelled turn stops polling and explains it`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(status = PhoneAgentTurnStatus.CANCELLED)
        }

        controller.send("hola")
        advanceUntilIdle()

        assertEquals(BubbleChatPhase.FAILED, controller.state.value.phase)
        assertEquals(
            BubbleChatMessage(BubbleMessageRole.ERROR, BubbleChatController.MESSAGE_CANCELLED),
            controller.state.value.messages.last(),
        )
        assertEquals(1, fake.phoneTurnPolls.size)
    }

    @Test
    fun `a transient poll failure retries instead of failing the turn`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        fake.phoneTurnPollHandlers.addLast { _, _ -> throw IOException("wifi blip") }
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Listo")
        }

        controller.send("hola")
        advanceUntilIdle()

        assertEquals(2, fake.phoneTurnPolls.size)
        assertEquals(BubbleChatPhase.DONE, controller.state.value.phase)
        assertEquals(BubbleChatMessage(BubbleMessageRole.ANSWER, "Listo"), controller.state.value.messages.last())
    }

    @Test
    fun `polling gives up after the retry budget`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        repeat(BubbleChatController.MAX_POLL_FAILURES) {
            fake.phoneTurnPollHandlers.addLast { _, _ -> throw IOException("pc off") }
        }

        controller.send("hola")
        advanceUntilIdle()

        assertEquals(BubbleChatController.MAX_POLL_FAILURES, fake.phoneTurnPolls.size)
        assertEquals(BubbleChatPhase.FAILED, controller.state.value.phase)
        assertEquals(
            BubbleChatMessage(BubbleMessageRole.ERROR, BubbleChatController.MESSAGE_POLL_FAILED),
            controller.state.value.messages.last(),
        )
        assertTrue(controller.state.value.canRetry)
    }

    @Test
    fun `close cancels polling and resume follows the same turn`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        val firstPoll = CompletableDeferred<Unit>()
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            firstPoll.await()
            turn(status = PhoneAgentTurnStatus.RUNNING)
        }
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Listo")
        }

        controller.send("hola")
        runCurrent()
        assertEquals(1, fake.phoneTurnPolls.size)

        controller.close()
        runCurrent()
        assertEquals(BubbleChatPhase.IDLE, controller.state.value.phase)
        assertEquals(1, fake.phoneTurnPolls.size)

        firstPoll.complete(Unit)
        advanceUntilIdle()
        assertEquals("closed polling must not ask again", 1, fake.phoneTurnPolls.size)

        controller.resume()
        advanceUntilIdle()

        assertEquals(2, fake.phoneTurnPolls.size)
        assertEquals(BubbleChatPhase.DONE, controller.state.value.phase)
        assertEquals(BubbleChatMessage(BubbleMessageRole.ANSWER, "Listo"), controller.state.value.messages.last())
    }

    @Test
    fun `blank text is ignored and never reaches the client`() = runTest {
        val controller = controller()

        controller.send("   ")
        advanceUntilIdle()

        assertTrue(fake.startedPhoneTurns.isEmpty())
        assertEquals(BubbleChatPhase.IDLE, controller.state.value.phase)
        assertTrue(controller.state.value.messages.isEmpty())
    }

    @Test
    fun `a second send while busy is ignored`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        val gate = CompletableDeferred<Unit>()
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            gate.await()
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Listo")
        }

        controller.send("uno")
        runCurrent()
        controller.send("dos")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("uno"), fake.startedPhoneTurns)
    }

    @Test
    fun `retry resends the last request`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Unavailable(PhoneAgentTurnStartResult.MESSAGE_UNAVAILABLE)
        )
        controller.send("revisa mi correo")
        advanceUntilIdle()
        assertTrue(controller.state.value.canRetry)

        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Tienes 2 correos.")
        }

        controller.retry()
        advanceUntilIdle()

        assertEquals(listOf("revisa mi correo", "revisa mi correo"), fake.startedPhoneTurns)
        assertEquals(BubbleChatPhase.DONE, controller.state.value.phase)
        assertEquals(
            BubbleChatMessage(BubbleMessageRole.ANSWER, "Tienes 2 correos."),
            controller.state.value.messages.last(),
        )
    }

    @Test
    fun `a retry is ignored while a turn is running`() = runTest {
        val controller = controller()
        fake.phoneTurnStartResults.addLast(
            PhoneAgentTurnStartResult.Started(turn(status = PhoneAgentTurnStatus.QUEUED))
        )
        val gate = CompletableDeferred<Unit>()
        fake.phoneTurnPollHandlers.addLast { _, _ ->
            gate.await()
            turn(status = PhoneAgentTurnStatus.DONE, answer = "Listo")
        }

        controller.send("uno")
        runCurrent()
        controller.retry()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("uno"), fake.startedPhoneTurns)
    }
}
