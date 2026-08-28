package com.idupi.app.data.remote

import com.idupi.app.domain.model.ChatEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseFrameParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // -- SseFrameParser.feedLine --------------------------------------------

    @Test
    fun `well-formed frame is dispatched on the blank terminator line`() {
        val parser = SseFrameParser()

        assertNull(parser.feedLine("event: thinking"))
        assertNull(parser.feedLine("data: {\"active\":true}"))
        val frame = parser.feedLine("")

        assertEquals(SseFrame("thinking", "{\"active\":true}"), frame)
    }

    @Test
    fun `heartbeat and comment lines starting with colon are ignored`() {
        val parser = SseFrameParser()

        assertNull(parser.feedLine(": connected"))
        assertNull(parser.feedLine(": ping"))
        // A pure comment stream never accumulates a frame, so the blank line
        // that would normally terminate one has nothing to dispatch.
        assertNull(parser.feedLine(""))

        // Comments interleaved with a real frame do not corrupt it.
        assertNull(parser.feedLine("event: message_end"))
        assertNull(parser.feedLine(": ping"))
        assertNull(parser.feedLine("data: {\"text\":\"done\"}"))
        val frame = parser.feedLine("")

        assertEquals(SseFrame("message_end", "{\"text\":\"done\"}"), frame)
    }

    @Test
    fun `multi-line data is joined with newlines before dispatch`() {
        val parser = SseFrameParser()

        assertNull(parser.feedLine("event: text_delta"))
        assertNull(parser.feedLine("data: line one"))
        assertNull(parser.feedLine("data: line two"))
        val frame = parser.feedLine("")

        assertEquals("line one\nline two", frame?.data)
    }

    @Test
    fun `a frame split across separate reads still assembles correctly`() {
        val parser = SseFrameParser()

        // Simulates the event line and data line arriving as two independent
        // readUTF8Line() calls on the wire, with the terminating blank line
        // arriving in a third call -- the parser's state must survive across them.
        assertNull(parser.feedLine("event: tool_start"))
        assertNull(parser.feedLine("data: {\"id\":\"t1\",\"name\":\"bash\",\"detail\":\"ls\"}"))
        val frame = parser.feedLine("")

        assertEquals("tool_start", frame?.event)
        assertEquals("{\"id\":\"t1\",\"name\":\"bash\",\"detail\":\"ls\"}", frame?.data)
    }

    // -- parseSseEvent --------------------------------------------------------

    @Test
    fun `thinking event maps to ChatEvent Thinking`() {
        val event = parseSseEvent(json, "thinking", "{\"active\":true}")
        assertEquals(ChatEvent.Thinking(true), event)
    }

    @Test
    fun `tool_start maps id name and detail onto ToolStarted`() {
        val event = parseSseEvent(json, "tool_start", "{\"id\":\"t1\",\"name\":\"bash\",\"detail\":\"ls -la\"}")
        assertEquals(ChatEvent.ToolStarted(toolName = "bash", message = "ls -la", id = "t1"), event)
    }

    @Test
    fun `tool_end with ok false maps to a failed ToolEnded`() {
        val event = parseSseEvent(json, "tool_end", "{\"id\":\"t1\",\"name\":\"bash\",\"ok\":false}")
        assertEquals(ChatEvent.ToolEnded(id = "t1", toolName = "bash", ok = false), event)
    }

    @Test
    fun `subagent_start and subagent_end map even though the server does not emit them yet`() {
        val started = parseSseEvent(json, "subagent_start", "{\"id\":\"s1\",\"name\":\"reviewer\",\"task\":\"review PR\"}")
        assertEquals(ChatEvent.SubagentStarted(id = "s1", name = "reviewer", task = "review PR"), started)

        val ended = parseSseEvent(json, "subagent_end", "{\"id\":\"s1\",\"name\":\"reviewer\",\"summary\":\"looks good\"}")
        assertEquals(ChatEvent.SubagentEnded(id = "s1", name = "reviewer", summary = "looks good"), ended)
    }

    @Test
    fun `unknown event type is ignored gracefully instead of crashing`() {
        val event = parseSseEvent(json, "some_future_event", "{\"whatever\":true}")
        assertNull(event)
    }

    @Test
    fun `malformed JSON is skipped rather than thrown`() {
        val event = parseSseEvent(json, "thinking", "{not valid json at all")
        assertNull(event)
    }

    @Test
    fun `malformed JSON on a known event does not affect a later well-formed frame`() {
        val broken = parseSseEvent(json, "tool_start", "{ this is not json")
        assertNull(broken)

        val recovered = parseSseEvent(json, "tool_start", "{\"id\":\"t2\",\"name\":\"grep\",\"detail\":null}")
        assertEquals(ChatEvent.ToolStarted(toolName = "grep", message = "", id = "t2"), recovered)
    }

    @Test
    fun `engine_changed is a known type with no mapped ChatEvent yet`() {
        val event = parseSseEvent(json, "engine_changed", "{\"engine\":\"claude\",\"model\":\"sonnet\",\"provider\":\"anthropic\"}")
        assertNull(event)
    }

    @Test
    fun `error event maps to ChatEvent ErrorOccurred`() {
        val event = parseSseEvent(json, "error", "{\"message\":\"boom\"}")
        assertEquals(ChatEvent.ErrorOccurred("boom"), event)
        assertTrue((event as ChatEvent.ErrorOccurred).error == "boom")
    }

    // -- activity_* (Change A, task 4.1) --------------------------------------
    //
    // The server filters every activity frame to the subscriber's own
    // engine/project/session before it reaches the wire (chat-events.mjs drops
    // frames whose context is missing and delivers only to the matching
    // subscriber), so the decoded events carry `id`/`streamId` for correlation
    // and not the context triple that selected them.

    @Test
    fun `activity_start decodes identity kind name and detail`() {
        val event = parseSseEvent(
            json,
            "activity_start",
            """{"id":"a1","streamId":"s1","engine":"pi-cli","project":"p","sessionId":"sess",
                "kind":"mcp","name":"playwright_browser_navigate","server":"playwright",
                "detail":"https://example.com","startedAt":1000}""",
        )
        assertEquals(
            ChatEvent.ActivityStarted(
                id = "a1", streamId = "s1", kind = "mcp",
                name = "playwright_browser_navigate", server = "playwright",
                detail = "https://example.com", startedAt = 1000L,
            ),
            event,
        )
    }

    @Test
    fun `activity_start without server or detail keeps them null`() {
        // Pi starts generic: the server enriches `server` additively only at the end.
        val event = parseSseEvent(
            json,
            "activity_start",
            """{"id":"a2","streamId":"s1","kind":"tool","name":"bash","startedAt":5}""",
        )
        val started = event as ChatEvent.ActivityStarted
        assertNull(started.server)
        assertNull(started.detail)
        assertEquals("bash", started.name)
    }

    @Test
    fun `activity_update carries additive server enrichment`() {
        val event = parseSseEvent(
            json,
            "activity_update",
            """{"id":"a1","streamId":"s1","server":"playwright","lastUpdateAt":2000}""",
        )
        assertEquals(
            ChatEvent.ActivityUpdated(
                id = "a1", streamId = "s1", server = "playwright",
                detail = null, lastUpdateAt = 2000L,
            ),
            event,
        )
    }

    @Test
    fun `activity_heartbeat decodes elapsed sinceLastUpdate and inflight`() {
        val event = parseSseEvent(
            json,
            "activity_heartbeat",
            """{"id":"a1","streamId":"s1","elapsedMs":45000,"sinceLastUpdateMs":15000,"inflight":true}""",
        )
        assertEquals(
            ChatEvent.ActivityHeartbeat(
                id = "a1", streamId = "s1",
                elapsedMs = 45000L, sinceLastUpdateMs = 15000L, inflight = true,
            ),
            event,
        )
    }

    @Test
    fun `activity_end carries the ok outcome`() {
        val ok = parseSseEvent(json, "activity_end", """{"id":"a1","streamId":"s1","ok":true}""")
        assertEquals(true, (ok as ChatEvent.ActivityEnded).ok)

        val failed = parseSseEvent(json, "activity_end", """{"id":"a1","streamId":"s1","ok":false}""")
        assertEquals(false, (failed as ChatEvent.ActivityEnded).ok)
    }

    @Test
    fun `activity_failure carries the error class`() {
        val event = parseSseEvent(
            json,
            "activity_failure",
            """{"id":"a1","streamId":"s1","errorClass":"tool"}""",
        )
        assertEquals(
            ChatEvent.ActivityFailed(id = "a1", streamId = "s1", errorClass = "tool", server = null, detail = null),
            event,
        )
    }

    @Test
    fun `activity_timeout decodes as its own terminal event`() {
        val event = parseSseEvent(json, "activity_timeout", """{"id":"a1","streamId":"s1"}""")
        assertEquals(
            ChatEvent.ActivityTimedOut(id = "a1", streamId = "s1", server = null, detail = null),
            event,
        )
    }

    @Test
    fun `an unknown activity subtype is still ignored rather than crashing`() {
        // Forward compatibility runs both ways: a NEWER server may add
        // activity_* subtypes this build has never heard of.
        assertNull(parseSseEvent(json, "activity_teleported", """{"id":"a1","streamId":"s1"}"""))
    }

    @Test
    fun `a malformed activity frame is skipped without killing the stream`() {
        assertNull(parseSseEvent(json, "activity_start", "{ not json"))

        val recovered = parseSseEvent(
            json,
            "activity_start",
            """{"id":"a9","streamId":"s1","kind":"tool","name":"read","startedAt":1}""",
        )
        assertEquals("read", (recovered as ChatEvent.ActivityStarted).name)
    }
}
