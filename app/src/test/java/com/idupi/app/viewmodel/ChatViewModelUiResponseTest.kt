package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.remote.IduPiHttpException
import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.model.MessageSender
import com.idupi.app.domain.model.UiRequest
import com.idupi.app.domain.model.UiRequestMethod
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 6.4 — RED-first coverage for `ChatViewModel.respondToUiRequest`.
 *
 * Behaviour under test (mirrors `specs/ui-request-selection/spec.md`):
 *
 *   200 → dialog closes (`_activeUiRequest = null`, `_uiRequestError = null`)
 *   400/409 → dialog STAYS open (invalid answer re-prompts / stale token —
 *         the user must be able to retry); the rejection is surfaced via
 *         `_uiRequestError`
 *   other 4xx / 5xx / network → dialog closes; an ERROR chat message is
 *         appended so the user knows why
 *
 * The token/sessionId on the wire come from the same `UiRequest` the SSE
 * `ui_request` frame delivered; the ViewModel is the SOLE owner of that wiring.
 */
class ChatViewModelUiResponseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun pendingRequest(
        id: String = "uir_pending",
        token: Long = 42L,
        sessionId: String = "sess-pending",
    ): UiRequest = UiRequest(
        id = id,
        method = UiRequestMethod.CONFIRM,
        title = "Approve",
        message = "Apply patch?",
        options = emptyList(),
        deadlineAt = 0L,
        token = token,
        sessionId = sessionId,
    )

    private suspend fun deliverAndOpenDialog(
        viewModel: ChatViewModel,
        request: UiRequest = pendingRequest(),
    ) {
        // Drive a UiRequestReceived through the chat flow so the ViewModel
        // has an active dialog to answer.
        fake.emitChatEvent(ChatEvent.UiRequestReceived(request))
        advanceUntilIdle()
        assertEquals(
            "the dialog must be open before we test the answer path",
            request, viewModel.activeUiRequest.value,
        )
        assertNull(
            "no prior rejection should be hanging over a fresh dialog",
            viewModel.uiRequestError.value,
        )
    }

    // -- 200: success path ---------------------------------------------------

    @Test
    fun `respondToUiRequest with 200 clears activeUiRequest and any prior uiRequestError`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // Seed an error to prove the success path also clears it.
        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale seed")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, false)
        advanceUntilIdle()
        assertNotNull(
            "sanity: the seeded 409 must have surfaced in uiRequestError",
            viewModel.uiRequestError.value,
        )

        // Reset and try the success path.
        fake.sendUiResponseFailure = null
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, true)
        advanceUntilIdle()

        assertNull("200 MUST clear activeUiRequest", viewModel.activeUiRequest.value)
        assertNull("200 MUST clear uiRequestError", viewModel.uiRequestError.value)
        assertEquals("uir_pending", fake.lastSentUiRequestId)
    }

    @Test
    fun `respondToUiRequest forwards the exact requestId value token and sessionId to the client`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        val request = pendingRequest(id = "uir_wire", token = 99L, sessionId = "sess_wire")
        deliverAndOpenDialog(viewModel, request)

        viewModel.respondToUiRequest(request, true)
        advanceUntilIdle()

        assertEquals("uir_wire", fake.lastSentUiRequestId)
        // The wire value matches what the user picked.
        assertEquals(true, fake.lastSentUiValue)
        // Token is sent as the String form of the Long (registry coerces with
        // Number(); either form round-trips).
        assertEquals("99", fake.lastSentUiToken)
        assertEquals("sess_wire", fake.lastSentUiSessionId)
    }

    // -- 409: stale-token / out-of-date value path --------------------------

    @Test
    fun `respondToUiRequest with 409 KEEPS activeUiRequest so the user can retry`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale token")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "B")
        advanceUntilIdle()

        assertNotNull(
            "409 MUST NOT clear activeUiRequest (spec scenario: dialog stays open)",
            viewModel.activeUiRequest.value,
        )
        assertEquals(
            "the SAME pending request must still be live so the user can retry",
            pendingRequest(), viewModel.activeUiRequest.value,
        )
    }

    @Test
    fun `respondToUiRequest with 409 surfaces the rejection in uiRequestError`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, false)
        advanceUntilIdle()

        assertNotNull(
            "409 MUST populate uiRequestError so the rejection is visible",
            viewModel.uiRequestError.value,
        )
        assertTrue(
            "the surfaced message MUST mention the rejection -- not be empty",
            viewModel.uiRequestError.value!!.isNotBlank(),
        )
    }

    @Test
    fun `respondToUiRequest with 400 KEEPS activeUiRequest so the user can retry`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.sendUiResponseFailure = IduPiHttpException(400, "fake://test", "C is not a valid option")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "C")
        advanceUntilIdle()

        assertNotNull(
            "400 MUST NOT clear activeUiRequest (spec scenario: invalid answer re-prompts)",
            viewModel.activeUiRequest.value,
        )
        assertEquals(
            "the SAME pending request must still be live so the user can retry",
            pendingRequest(), viewModel.activeUiRequest.value,
        )
    }

    @Test
    fun `respondToUiRequest with 400 surfaces the rejection in uiRequestError`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.sendUiResponseFailure = IduPiHttpException(400, "fake://test", "C is not a valid option")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "C")
        advanceUntilIdle()

        assertNotNull(
            "400 MUST populate uiRequestError so the rejection is visible",
            viewModel.uiRequestError.value,
        )
        assertTrue(
            "the surfaced message MUST mention the rejection -- not be empty",
            viewModel.uiRequestError.value!!.isNotBlank(),
        )
    }

    @Test
    fun `respondToUiRequest with a new UiRequestReceived clears the prior 409 error`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // First, hold the dialog open with a 409.
        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "old")
        advanceUntilIdle()
        assertNotNull(viewModel.uiRequestError.value)

        // Then a new request arrives from the server.
        val next = pendingRequest(id = "uir_next", token = 43L, sessionId = "sess_pending")
        fake.emitChatEvent(ChatEvent.UiRequestReceived(next))
        advanceUntilIdle()

        assertNull(
            "a new request MUST supersede the prior 409 error (so the user sees a clean dialog)",
            viewModel.uiRequestError.value,
        )
        assertEquals(next, viewModel.activeUiRequest.value)
    }

    // -- unrelated failures: other 4xx / 5xx / network ----------------------

    @Test
    fun `respondToUiRequest with an unrelated 5xx IduPiHttpException closes the dialog and appends an ERROR message`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.sendUiResponseFailure = IduPiHttpException(500, "fake://test", "boom")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "B")
        advanceUntilIdle()

        assertNull(
            "an unrelated 5xx HTTP failure MUST close the dialog (user must start over)",
            viewModel.activeUiRequest.value,
        )
        val last = viewModel.messages.value.last()
        assertEquals(MessageSender.ERROR, last.sender)
        assertTrue(
            "the user MUST see why the dialog closed -- the ERROR message must explain",
            last.text.contains("boom"),
        )
    }

    @Test
    fun `respondToUiRequest with a non-HTTP network exception closes the dialog and appends an ERROR message`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // Simulate a network failure via the existing generic failWith hook.
        fake.failWith = RuntimeException("connection reset")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, true)
        advanceUntilIdle()

        assertNull(
            "a network failure MUST close the dialog",
            viewModel.activeUiRequest.value,
        )
        val last = viewModel.messages.value.last()
        assertEquals(MessageSender.ERROR, last.sender)
        assertTrue(
            "the user MUST see the network failure reason",
            last.text.contains("connection reset"),
        )
    }

    // -- 200-after-409 retry flow -------------------------------------------

    @Test
    fun `a successful retry after a 409 closes the dialog (the held-open state is not permanent)`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // First attempt: server says stale.
        fake.sendUiResponseFailuresRemaining = 1
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "old")
        advanceUntilIdle()
        assertNotNull(
            "sanity: first attempt was rejected; dialog must still be open",
            viewModel.activeUiRequest.value,
        )
        assertNotNull(viewModel.uiRequestError.value)

        // Second attempt: server accepts. The retry uses the SAME token, so
        // the registry accepts it on the same entry (no supersede).
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "new")
        advanceUntilIdle()

        assertNull("the retry succeeded; dialog must close", viewModel.activeUiRequest.value)
        assertNull("the retry succeeded; the prior 409 error must clear", viewModel.uiRequestError.value)
        assertEquals("new", fake.lastSentUiValue)
    }

    // -- ui_request_resolved: server-side terminal acknowledgement ----------
    //
    // The server emits `ui_request_resolved` when a request becomes terminal:
    // the client's answer POST returned 200 OR the 120s registry timer
    // auto-approved it. The card MUST close in BOTH cases -- otherwise the
    // 120s expiry leaves the card showing "Expirado" forever (pre-test audit).
    // A STALE resolve (an id superseded by a newer request) must be a no-op:
    // it must neither close the newer card nor wipe its 409 rejection.

    @Test
    fun `ui_request_resolved for the pending id closes the card and clears any rejection`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        fake.emitChatEvent(ChatEvent.UiRequestResolved(requestId = "uir_pending"))
        advanceUntilIdle()

        assertNull(
            "a server-side resolve for the pending id MUST close the card",
            viewModel.activeUiRequest.value,
        )
        assertNull(
            "a server-side resolve for the pending id MUST clear any rejection",
            viewModel.uiRequestError.value,
        )
    }

    @Test
    fun `ui_request_resolved for the pending id after a 409 closes the card and clears the error`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // Hold the dialog open with a 409 rejection first.
        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "old")
        advanceUntilIdle()
        assertNotNull(
            "sanity: the 409 must have held the dialog open",
            viewModel.activeUiRequest.value,
        )

        // Then the server resolves it (e.g. a newer answer won elsewhere, or
        // the timer fired). The card must close and the rejection must clear.
        fake.emitChatEvent(ChatEvent.UiRequestResolved(requestId = "uir_pending"))
        advanceUntilIdle()

        assertNull("the resolve MUST close the held-open card", viewModel.activeUiRequest.value)
        assertNull("the resolve MUST clear the 409 rejection", viewModel.uiRequestError.value)
    }

    @Test
    fun `ui_request_resolved for a STALE id keeps the current card open`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        deliverAndOpenDialog(viewModel)

        // Seed a rejection so we can prove the stale resolve does not wipe it.
        fake.sendUiResponseFailure = IduPiHttpException(409, "fake://test", "stale")
        viewModel.respondToUiRequest(viewModel.activeUiRequest.value!!, "old")
        advanceUntilIdle()
        assertNotNull(viewModel.uiRequestError.value)

        // A resolve for an already-superseded request arrives late.
        fake.emitChatEvent(ChatEvent.UiRequestResolved(requestId = "uir_superseded"))
        advanceUntilIdle()

        assertEquals(
            "the STALE resolve must NOT close the newer card",
            pendingRequest(), viewModel.activeUiRequest.value,
        )
        assertNotNull(
            "the STALE resolve must NOT wipe the 409 rejection of the newer card",
            viewModel.uiRequestError.value,
        )
    }

    @Test
    fun `ui_request_resolved with no pending card is a no-op`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNull("sanity: no card is open", viewModel.activeUiRequest.value)

        // A resolve for a request the app never saw (e.g. it arrived while the
        // app was on another screen) must not crash or invent state.
        fake.emitChatEvent(ChatEvent.UiRequestResolved(requestId = "uir_unknown"))
        advanceUntilIdle()

        assertNull(viewModel.activeUiRequest.value)
        assertNull(viewModel.uiRequestError.value)
    }
}
