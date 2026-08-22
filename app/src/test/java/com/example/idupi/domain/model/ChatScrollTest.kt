package com.example.idupi.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a session scrolled from the top to the last message with an
 * ANIMATED scroll, so a 50-message session visibly travelled through all 50 --
 * and a long one took long enough to be useless.
 *
 * The animation is worth keeping for the case it was written for: one new
 * message arriving while the user is reading, where the movement shows that
 * something was added. Loading a whole history is not that case.
 */
class ChatScrollTest {

    @Test
    fun `opening a session jumps instead of travelling through it`() {
        assertFalse(shouldAnimateScroll(previousSize = 1, newSize = 50))
        assertFalse(shouldAnimateScroll(previousSize = 1, newSize = 1000))
    }

    @Test
    fun `the first paint jumps`() {
        assertFalse(shouldAnimateScroll(previousSize = 0, newSize = 12))
    }

    @Test
    fun `one new message still animates`() {
        // This is what the animation was for: something arrived while reading.
        assertTrue(shouldAnimateScroll(previousSize = 30, newSize = 31))
    }

    @Test
    fun `a turn's few frames still animate`() {
        // A turn appends its parts one at a time -- the message, a tool card,
        // the answer -- so each step is a small delta, not a bulk load.
        assertTrue(shouldAnimateScroll(previousSize = 30, newSize = 32))
        assertTrue(shouldAnimateScroll(previousSize = 30, newSize = 33))
    }

    @Test
    fun `a bulk arrival jumps`() {
        assertFalse(shouldAnimateScroll(previousSize = 30, newSize = 34))
    }

    @Test
    fun `a shorter list jumps`() {
        // Clearing the chat, or switching to a smaller session: there is no
        // continuity to show, so animating it is just a delay.
        assertFalse(shouldAnimateScroll(previousSize = 50, newSize = 3))
    }

    @Test
    fun `an empty list asks for no scroll at all`() {
        assertFalse(shouldAnimateScroll(previousSize = 10, newSize = 0))
    }
}
