package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fullscreen floating bubble sticks to the nearest edge after a drag. */
class ScreenBubbleTest {

    @Test
    fun `snaps to the left edge when dropped near it`() {
        // Dropped at x=40 inside a 1000-wide area: left is nearer.
        assertEquals(0f to 300f, snapBubbleToEdge(40f, 300f, 960f, 500f))
    }

    @Test
    fun `snaps to the right edge when dropped near it`() {
        // Dropped past the midpoint: flush against the right edge.
        assertEquals(960f to 120f, snapBubbleToEdge(700f, 120f, 960f, 500f))
    }

    @Test
    fun `keeps the dragged height and clamps it inside bounds`() {
        assertEquals(0f to 500f, snapBubbleToEdge(-50f, 999f, 960f, 500f))
        assertEquals(960f to 0f, snapBubbleToEdge(900f, -20f, 960f, 500f))
    }
}
