package com.idupi.app.viewmodel

import com.idupi.app.domain.model.PtyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the interactive terminal: the C0 transform mirrored by
 * terminal.js, the ordered keystroke coalescer, and the reconnect decision.
 * Keeping them here means the wire behaviour is pinned without a device.
 */
class PtyTerminalLogicTest {

    // --- control codes (parity with assets/terminal/terminal.js) ---

    @Test
    fun `ctrl maps lowercase letters to C0 control codes`() {
        assertEquals('\u0001', ptyControlCode('a'))
        assertEquals('\u0003', ptyControlCode('c'))
        assertEquals('\u001a', ptyControlCode('z'))
    }

    @Test
    fun `ctrl mapping is case insensitive`() {
        assertEquals('\u0001', ptyControlCode('A'))
        assertEquals('\u001a', ptyControlCode('Z'))
    }

    @Test
    fun `ctrl maps the punctuation set and space`() {
        assertEquals('\u0000', ptyControlCode(' '))
        assertEquals('\u0000', ptyControlCode('@'))
        assertEquals('\u001b', ptyControlCode('['))
        assertEquals('\u001c', ptyControlCode('\\'))
        assertEquals('\u001d', ptyControlCode(']'))
        assertEquals('\u001e', ptyControlCode('^'))
        assertEquals('\u001f', ptyControlCode('_'))
    }

    @Test
    fun `digits and symbols without a control code are left alone`() {
        assertNull(ptyControlCode('1'))
        assertNull(ptyControlCode('/'))
        assertNull(ptyControlCode('é'))
    }

    @Test
    fun `alt prefixes escape and combines with ctrl`() {
        assertEquals("\u001bx", applyPtyModifiers("x", ctrl = false, alt = true))
        assertEquals("\u0001", applyPtyModifiers("a", ctrl = true, alt = false))
        assertEquals("\u001b\u0001", applyPtyModifiers("a", ctrl = true, alt = true))
    }

    @Test
    fun `pasted text is never rewritten by ctrl`() {
        assertEquals("paste", applyPtyModifiers("paste", ctrl = true, alt = false))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", applyPtyModifiers("", ctrl = true, alt = true))
    }

    // --- input batching ---

    @Test
    fun `input queue preserves arrival order in one batch`() {
        val queue = PtyInputQueue()
        queue.offer("l")
        queue.offer("s")
        queue.offer("\r")
        assertEquals("ls\r", queue.drain())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `draining an empty queue yields nothing and keeps working afterwards`() {
        val queue = PtyInputQueue()
        assertEquals("", queue.drain())
        queue.offer("echo")
        assertEquals("echo", queue.drain())
        assertEquals("", queue.drain())
    }

    @Test
    fun `oversized input splits on UTF-8 code point boundaries and reassembles`() {
        val text = "a".repeat(10) + "€".repeat(10) + "😀".repeat(4)
        val parts = splitPtyInput(text, maxBytes = 12)
        assertTrue("expected several parts, got ${parts.size}", parts.size > 1)
        parts.forEach { part ->
            assertTrue(
                "part exceeds the byte budget: ${part.toByteArray(Charsets.UTF_8).size}",
                part.toByteArray(Charsets.UTF_8).size <= 12
            )
        }
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun `input under the cap stays a single request`() {
        val text = "echo hola\n"
        assertEquals(listOf(text), splitPtyInput(text, maxBytes = 64))
    }

    // --- reconnect decision ---

    @Test
    fun `backoff grows from 500 ms and caps at four seconds`() {
        assertEquals(500L, ptyReconnectDelay(0))
        assertEquals(1_000L, ptyReconnectDelay(1))
        assertEquals(2_000L, ptyReconnectDelay(2))
        assertEquals(4_000L, ptyReconnectDelay(3))
        assertEquals(4_000L, ptyReconnectDelay(50))
        assertEquals(500L, ptyReconnectDelay(-3))
    }

    @Test
    fun `alive terminal reconnects with the attempt backoff`() {
        val outcome = decideAfterStreamEnd(
            id = "pty-1",
            attempt = 2,
            ptys = listOf(PtyInfo(id = "pty-1", alive = true))
        )
        assertEquals(PtyStreamOutcome.Reconnect(2_000L), outcome)
    }

    @Test
    fun `dead terminal reports its exit code`() {
        val outcome = decideAfterStreamEnd(
            id = "pty-1",
            attempt = 0,
            ptys = listOf(PtyInfo(id = "pty-1", alive = false, exitCode = 7))
        )
        assertEquals(PtyStreamOutcome.Exited(7), outcome)
    }

    @Test
    fun `terminal missing from the server list counts as exited without a code`() {
        val outcome = decideAfterStreamEnd(
            id = "pty-gone",
            attempt = 0,
            ptys = listOf(PtyInfo(id = "pty-other", alive = true))
        )
        assertEquals(PtyStreamOutcome.Exited(null), outcome)
    }

    @Test
    fun `a failed liveness check reconnects instead of inventing an exit`() {
        val outcome = decideAfterStreamEnd(id = "pty-1", attempt = 0, ptys = null)
        assertEquals(PtyStreamOutcome.Reconnect(500L), outcome)
    }

    @Test
    fun `auth rejection is fatal and never retried`() {
        val rejected = decideAfterStreamEnd(
            id = "pty-1",
            attempt = 0,
            httpStatus = 401,
            failure = "token inválido"
        )
        assertEquals(PtyStreamOutcome.Failed("token inválido"), rejected)
    }

    @Test
    fun `404 on the stream is an exit, not a retry`() {
        val gone = decideAfterStreamEnd(id = "pty-1", attempt = 3, httpStatus = 404)
        assertEquals(PtyStreamOutcome.Exited(null), gone)
    }

    @Test
    fun `a stream socket drop with an alive terminal reconnects`() {
        val outcome = decideAfterStreamEnd(
            id = "pty-1",
            attempt = 0,
            ptys = listOf(PtyInfo(id = "pty-1", alive = true)),
            failure = "SocketTimeoutException"
        )
        assertEquals(PtyStreamOutcome.Reconnect(500L), outcome)
    }
}
