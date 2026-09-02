package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Touches land on the rendered frame; the wire carries NORMALISED monitor
 * coordinates -- never pixels -- because the user's left monitor starts at
 * x=-1920 and the client does not know monitor geometry. The frame fills its
 * node exactly (ContentScale.FillBounds), so node fraction IS monitor
 * fraction.
 */
class ScreenTouchTest {

    @Test
    fun `the centre is half and half`() {
        assertEquals(0.5 to 0.5, touchToMonitorFraction(400f, 225f, 800f, 450f))
    }

    @Test
    fun `corners are zero and one`() {
        assertEquals(0.0 to 0.0, touchToMonitorFraction(0f, 0f, 800f, 450f))
        assertEquals(1.0 to 1.0, touchToMonitorFraction(800f, 450f, 800f, 450f))
    }

    @Test
    fun `a touch outside the frame clamps instead of aiming off-monitor`() {
        assertEquals(0.0 to 0.5, touchToMonitorFraction(-30f, 225f, 800f, 450f))
        assertEquals(1.0 to 1.0, touchToMonitorFraction(900f, 500f, 800f, 450f))
    }

    @Test
    fun `a degenerate node yields null instead of infinity`() {
        assertNull(touchToMonitorFraction(10f, 10f, 0f, 450f))
        assertNull(touchToMonitorFraction(10f, 10f, 800f, 0f))
    }
}
