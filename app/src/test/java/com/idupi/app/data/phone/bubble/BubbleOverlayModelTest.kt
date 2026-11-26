package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble's pure geometry and gating (phone-bubble T4).
 *
 * The window math lives outside the service on purpose: sizes, focus flags and
 * the collapsed/expanded switch are the parts that break silently on a phone,
 * so they are pinned here instead of only on device.
 */
class BubbleOverlayModelTest {

    private val notFocusable = BubbleWindowSpecs.FLAG_NOT_FOCUSABLE

    /** `WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN`, a flag we never toggle. */
    private val layoutInScreen = 0x00000100

    // --- gate -------------------------------------------------------------

    @Test
    fun `the bubble needs the setting, phone control and the accessibility grant`() {
        assertTrue(BubbleGate.shouldShow(true, true, true))
        assertFalse(BubbleGate.shouldShow(false, true, true))
        assertFalse(BubbleGate.shouldShow(true, false, true))
        assertFalse(BubbleGate.shouldShow(true, true, false))
        assertFalse(BubbleGate.shouldShow(false, false, false))
    }

    // --- window spec ------------------------------------------------------

    @Test
    fun `collapsed is a small non focusable pill`() {
        val spec = BubbleWindowSpecs.forMode(
            mode = BubbleMode.COLLAPSED,
            baseFlags = 0,
            screenWidthPx = 1080,
            screenHeightPx = 1920,
            density = 2f,
        )

        assertEquals(112, spec.width)
        assertEquals(112, spec.height)
        assertFalse(spec.focusable)
        assertTrue(spec.flags and notFocusable != 0)
    }

    @Test
    fun `expanded drops the not focusable flag so the keyboard works`() {
        val spec = BubbleWindowSpecs.forMode(
            mode = BubbleMode.EXPANDED,
            baseFlags = 0,
            screenWidthPx = 1080,
            screenHeightPx = 1920,
            density = 2f,
        )

        assertTrue(spec.focusable)
        assertEquals(0, spec.flags and notFocusable)
        assertEquals(640, spec.width)
        assertEquals(880, spec.height)
    }

    @Test
    fun `expanded never exceeds a small screen`() {
        val spec = BubbleWindowSpecs.forMode(
            mode = BubbleMode.EXPANDED,
            baseFlags = 0,
            screenWidthPx = 400,
            screenHeightPx = 500,
            density = 2f,
        )

        assertEquals(400 - 24, spec.width)
        assertEquals(500 - 24, spec.height)
    }

    @Test
    fun `the base flags survive both modes`() {
        val base = layoutInScreen
        val collapsed = BubbleWindowSpecs.forMode(BubbleMode.COLLAPSED, base, 1080, 1920, 2f)
        val expanded = BubbleWindowSpecs.forMode(BubbleMode.EXPANDED, base, 1080, 1920, 2f)

        assertTrue(collapsed.flags and base != 0)
        assertTrue(expanded.flags and base != 0)
        assertTrue(collapsed.flags and notFocusable != 0)
        assertEquals(0, expanded.flags and notFocusable)
    }

    // --- position ---------------------------------------------------------

    @Test
    fun `a dragged position is clamped inside the screen`() {
        assertEquals(0 to 0, BubbleOverlayPosition.clamped(-40, -10, 112, 112, 1080, 1920))
        assertEquals(
            1080 - 112 to 1920 - 112,
            BubbleOverlayPosition.clamped(5000, 5000, 112, 112, 1080, 1920),
        )
        assertEquals(300 to 700, BubbleOverlayPosition.clamped(300, 700, 112, 112, 1080, 1920))
    }

    @Test
    fun `a released pill snaps to the nearest vertical edge and keeps its height`() {
        assertEquals(0 to 500, BubbleOverlayPosition.snapped(20, 500, 112, 112, 1080, 1920))
        assertEquals(
            1080 - 112 to 500,
            BubbleOverlayPosition.snapped(900, 500, 112, 112, 1080, 1920),
        )
        assertEquals(0 to 500, BubbleOverlayPosition.snapped(-50, 500, 112, 112, 1080, 1920))
    }

    @Test
    fun `the expanded panel is centered horizontally`() {
        assertEquals((1080 - 640) / 2, BubbleOverlayPosition.centeredX(640, 1080))
        assertEquals(0, BubbleOverlayPosition.centeredX(640, 400))
    }
}
