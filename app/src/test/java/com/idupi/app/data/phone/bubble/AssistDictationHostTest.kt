package com.idupi.app.data.phone.bubble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process-wide bridge between the system assistant entry point and the bubble
 * (assistant-key T1), following the ApprovalVoiceHost pattern: only a listener
 * attached by the accessibility service receives the "dictate now" request, and
 * a listener that cannot take it refuses instead of lying.
 */
class AssistDictationHostTest {

    @After
    fun tearDown() {
        AssistDictationHost.detach()
        AssistDictationHost.consumeDeferred(nowMillis = 0)
    }

    @Test
    fun `requests only reach an attached overlay`() {
        var requests = 0
        assertFalse(AssistDictationHost.isAttached())
        assertFalse(AssistDictationHost.requestDictation())

        AssistDictationHost.attach {
            requests += 1
            true
        }
        assertTrue(AssistDictationHost.isAttached())
        assertTrue(AssistDictationHost.requestDictation())
        assertEquals(1, requests)

        AssistDictationHost.detach()
        assertFalse(AssistDictationHost.isAttached())
        assertFalse(AssistDictationHost.requestDictation())
        assertEquals(1, requests)
    }

    @Test
    fun `a locked press opens the dictation only on an unlock inside the window`() {
        assertFalse(AssistDictationHost.consumeDeferred(nowMillis = 1_000))

        AssistDictationHost.deferUntilUnlock(nowMillis = 1_000)
        assertTrue(AssistDictationHost.consumeDeferred(nowMillis = 1_000 + AssistDictationHost.UNLOCK_WINDOW_MS))
        // Consumed: a second unlock does not reopen it.
        assertFalse(AssistDictationHost.consumeDeferred(nowMillis = 2_000))

        AssistDictationHost.deferUntilUnlock(nowMillis = 1_000)
        assertFalse(AssistDictationHost.consumeDeferred(nowMillis = 1_001 + AssistDictationHost.UNLOCK_WINDOW_MS))
    }

    @Test
    fun `an overlay that cannot take the request refuses it`() {
        AssistDictationHost.attach { false }

        assertFalse(AssistDictationHost.requestDictation())
    }
}
