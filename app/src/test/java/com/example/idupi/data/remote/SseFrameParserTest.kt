package com.example.idupi.data.remote

import com.example.idupi.domain.model.ChatEvent
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
}
