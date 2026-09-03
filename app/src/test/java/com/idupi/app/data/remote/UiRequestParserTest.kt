package com.idupi.app.data.remote

import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.model.UiRequestMethod
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.1 — RED-first coverage for the `ui_request` channel added in Phase 4.
 *
 * The parser MUST:
 *   - decode `ui_request` select / confirm / input onto [ChatEvent.UiRequestReceived]
 *     carrying `id`, `method`, `title`, `message`, `options`, `token`, `sessionId`
 *     and an ABSOLUTE `deadlineAt` derived from the wire's relative `deadlineMs`
 *   - surface `ui_request_resolved` as [ChatEvent.UiRequestResolved] carrying
 *     the `requestId` so the chat layer can close the card on server-side
 *     resolution (client 200 or 120s auto-approve)
 *   - fall back to CONFIRM for an unknown method instead of dropping the frame
 *
 * Every scenario below was derived directly from
 * openspec/changes/fix-ui-request-selection/specs/ui-request-selection/spec.md.
 */
class UiRequestParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `ui_request select decodes options and method onto a SELECT UiRequest`() {
        val event = parseSseEvent(
            json,
            "ui_request",
            """
            {
              "requestId":"uir_test",
              "token":7,
              "method":"select",
              "title":"Pick one",
              "message":"Which option should I run?",
              "options":["A","B"],
              "deadlineMs":120000,
              "sessionId":"sess-1"
            }
            """.trimIndent(),
        )
        assertNotNull("ui_request must decode to a ChatEvent.UiRequestReceived", event)
        val received = event as ChatEvent.UiRequestReceived
        assertEquals("uir_test", received.request.id)
        assertEquals(UiRequestMethod.SELECT, received.request.method)
        assertEquals("Pick one", received.request.title)
        assertEquals("Which option should I run?", received.request.message)
        assertEquals(listOf("A", "B"), received.request.options)
        assertEquals(7L, received.request.token)
        assertEquals("sess-1", received.request.sessionId)
    }

    @Test
    fun `ui_request confirm decodes to UiRequestMethod CONFIRM with empty options`() {
        val event = parseSseEvent(
            json,
            "ui_request",
            """
            {
              "requestId":"uir_confirm",
              "token":1,
              "method":"confirm",
              "title":"Confirm",
              "message":"Apply patch to src/main.kt?",
              "deadlineMs":120000,
              "sessionId":"sess-1"
            }
            """.trimIndent(),
        )
        val received = event as ChatEvent.UiRequestReceived
        assertEquals(UiRequestMethod.CONFIRM, received.request.method)
        assertEquals(emptyList<String>(), received.request.options)
    }

    @Test
    fun `ui_request input decodes to UiRequestMethod INPUT`() {
        val event = parseSseEvent(
            json,
            "ui_request",
            """
            {
              "requestId":"uir_input",
              "token":2,
              "method":"input",
              "title":"Free input",
              "message":"Reply to the user with...",
              "deadlineMs":90000,
              "sessionId":"sess-2"
            }
            """.trimIndent(),
        )
        val received = event as ChatEvent.UiRequestReceived
        assertEquals(UiRequestMethod.INPUT, received.request.method)
    }

    @Test
    fun `ui_request deadlineMs is converted to an ABSOLUTE deadlineAt via System currentTimeMillis`() {
        // The parser uses `System.currentTimeMillis() + deadlineMs` so the
        // countdown survives clock skew and re-renders correctly when the user
        // returns to Chat. We assert the upper bound to keep the test stable
        // across runs; the lower bound catches off-by-one bugs (e.g. forgetting
        // to add `deadlineMs`).
        val before = System.currentTimeMillis()
        val event = parseSseEvent(
            json,
            "ui_request",
            """{"requestId":"uir_d","token":1,"method":"confirm","title":"t","message":"m","deadlineMs":120000,"sessionId":"s"}""",
        )
        val after = System.currentTimeMillis()
        val received = event as ChatEvent.UiRequestReceived
        assertTrue(
            "deadlineAt must be in [before+120000, after+120000]",
            received.request.deadlineAt in (before + 120_000)..(after + 120_000),
        )
    }

    @Test
    fun `ui_request with deadlineMs 0 maps to deadlineAt 0 (no countdown)`() {
        // A missing/zero deadline disables the countdown ("Expirado" / button
        // disable logic is gated on deadlineAt > 0). The parser MUST NOT
        // compute `0 + now` -- that would silently show a 50-year timer.
        val event = parseSseEvent(
            json,
            "ui_request",
            """{"requestId":"uir_0","token":1,"method":"confirm","title":"t","message":"m","deadlineMs":0,"sessionId":"s"}""",
        )
        val received = event as ChatEvent.UiRequestReceived
        assertEquals(0L, received.request.deadlineAt)
    }

    @Test
    fun `ui_request carries the token as Long (preserves JSON-number precision)`() {
        // The wire encodes token as a JSON number. The parser lifts it to Long
        // so the ViewModel can round-trip it through sendUiResponse (the design
        // contract documents the POST body as String -- the registry coerces
        // with Number() so either type round-trips). A naive toString() on the
        // JSON node would have lost precision for tokens > 2^31.
        val event = parseSseEvent(
            json,
            "ui_request",
            """{"requestId":"uir_big","token":9007199254740993,"method":"confirm","title":"t","message":"m","deadlineMs":120000,"sessionId":"s"}""",
        )
        val received = event as ChatEvent.UiRequestReceived
        assertEquals(9_007_199_254_740_993L, received.request.token)
    }

    @Test
    fun `ui_request with an unknown method falls back to CONFIRM rather than dropping the frame`() {
        // Forward compat: a NEWER server may add methods this build has never
        // heard of. Surfacing the frame as CONFIRM means the user still sees a
        // dialog (and a chat-message entry) instead of a silent drop.
        val event = parseSseEvent(
            json,
            "ui_request",
            """{"requestId":"uir_future","token":1,"method":"approve","title":"t","message":"m","deadlineMs":120000,"sessionId":"s"}""",
        )
        val received = event as ChatEvent.UiRequestReceived
        assertEquals(UiRequestMethod.CONFIRM, received.request.method)
    }

    @Test
    fun `ui_request_resolved surfaces as UiRequestResolved carrying the requestId`() {
        // Spec §"Authenticated Answer Transport": terminal acknowledgement from
        // the registry (200 POST or 120s auto-approve). Surfaced so the chat
        // layer can close the card when the server resolves the request
        // without a client answer (expiry). Old APKs that do not handle the
        // event simply keep the card until their own countdown logic.
        val event = parseSseEvent(
            json,
            "ui_request_resolved",
            """{"requestId":"uir_done","sessionId":"s","engine":"pi","resolution":"client","value":"A"}""",
        )
        val resolved = event as ChatEvent.UiRequestResolved
        assertEquals("ui_request_resolved must surface the resolved requestId", "uir_done", resolved.requestId)
    }

    @Test
    fun `ui_request_resolved auto-approve payload also surfaces the requestId`() {
        // The 120s registry timer emits the same frame with
        // `resolution: "auto_approve"` -- the app treats both identically.
        val event = parseSseEvent(
            json,
            "ui_request_resolved",
            """{"requestId":"uir_expired","sessionId":"s","engine":"pi","resolution":"auto_approve","value":"Todo"}""",
        )
        val resolved = event as ChatEvent.UiRequestResolved
        assertEquals("uir_expired", resolved.requestId)
    }

    @Test
    fun `ui_request_resolved with a missing requestId surfaces an empty id (stale-safe no-op)`() {
        // A frame without a requestId can never match a pending request, so
        // the ViewModel's id guard makes it a harmless no-op. It must still
        // parse (and not kill the stream).
        val event = parseSseEvent(
            json,
            "ui_request_resolved",
            """{"sessionId":"s","resolution":"auto_approve"}""",
        )
        val resolved = event as ChatEvent.UiRequestResolved
        assertEquals("", resolved.requestId)
    }

    @Test
    fun `malformed JSON on a ui_request frame does not kill the stream`() {
        // Same contract as the existing parser: a single malformed frame is
        // skipped with a WARN log, never thrown. Subsequent valid frames MUST
        // still decode.
        val broken = parseSseEvent(json, "ui_request", "{not valid json")
        assertNull(broken)

        val recovered = parseSseEvent(
            json,
            "ui_request",
            """{"requestId":"uir_ok","token":3,"method":"select","title":"t","message":"m","options":["X","Y"],"deadlineMs":60000,"sessionId":"s"}""",
        )
        val received = recovered as ChatEvent.UiRequestReceived
        assertEquals(listOf("X", "Y"), received.request.options)
    }

    @Test
    fun `full SSE wire round trip -- feedLine assembles a ui_request frame into UiRequestReceived`() {
        // The parser is line-at-a-time and the SSE substrate may split a frame
        // across reads. The whole pipeline (SseFrameParser + parseSseEvent)
        // MUST reconstruct the same event when the data: line arrives in
        // chunks, with the event:/data: lines interleaved arbitrarily.
        val parser = SseFrameParser()
        assertNull(parser.feedLine("event: ui_request"))
        // Split data across two reads to exercise the accumulator.
        assertNull(parser.feedLine("data: {\"requestId\":\"uir_split\","))
        assertNull(parser.feedLine("data: \"token\":11,\"method\":\"input\",\"title\":\"t\",\"message\":\"m\","))
        assertNull(parser.feedLine("data: \"deadlineMs\":120000,\"sessionId\":\"sess\"}"))
        val frame = parser.feedLine("")
        assertEquals("ui_request", frame?.event)

        val event = parseSseEvent(json, frame?.event, frame?.data)
        val received = event as ChatEvent.UiRequestReceived
        // deadlineAt is derived from `System.currentTimeMillis() + deadlineMs`,
        // so we cannot assert an exact value. Every other field is deterministic.
        assertEquals("uir_split", received.request.id)
        assertEquals(UiRequestMethod.INPUT, received.request.method)
        assertEquals("t", received.request.title)
        assertEquals("m", received.request.message)
        assertEquals(emptyList<String>(), received.request.options)
        assertEquals(11L, received.request.token)
        assertEquals("sess", received.request.sessionId)
        // Sanity: deadlineAt is now+120000 (the math is pinned by another test).
        val now = System.currentTimeMillis()
        assertTrue(
            "deadlineAt must be ~now+120000",
            received.request.deadlineAt in (now + 119_000)..(now + 121_000),
        )
    }
}
