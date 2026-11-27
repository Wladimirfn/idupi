package com.idupi.app.data.phone.bubble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Dashboard "Optimizar teléfono" button reaches the bubble through this
 * process-wide host (phone-dashboard T2). Detached means accessibility is off,
 * so the request must be refused instead of starting a turn on its own.
 */
class BubbleRequestHostTest {

    @Before
    fun setUp() {
        BubbleRequestHost.detach()
    }

    @After
    fun tearDown() {
        BubbleRequestHost.detach()
    }

    @Test
    fun `send without an attached overlay is refused`() {
        assertFalse(BubbleRequestHost.isAttached())

        assertFalse(BubbleRequestHost.send("Optimiza mi teléfono"))
    }

    @Test
    fun `send reaches the attached overlay with the same text`() {
        val received = mutableListOf<String>()
        BubbleRequestHost.attach { text ->
            received += text
            true
        }

        assertTrue(BubbleRequestHost.isAttached())
        assertTrue(BubbleRequestHost.send("Optimiza mi teléfono"))
        assertEquals(listOf("Optimiza mi teléfono"), received)
    }

    @Test
    fun `detach stops delivery`() {
        var calls = 0
        BubbleRequestHost.attach {
            calls += 1
            true
        }

        BubbleRequestHost.detach()

        assertFalse(BubbleRequestHost.isAttached())
        assertFalse(BubbleRequestHost.send("hola"))
        assertEquals(0, calls)
    }

    @Test
    fun `an overlay that cannot open the panel reports failure`() {
        BubbleRequestHost.attach { false }

        assertFalse(BubbleRequestHost.send("Optimiza mi teléfono"))
    }
}
