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

    // --- movable panel (T15) ----------------------------------------------

    @Test
    fun `the first expansion centers the panel and keeps the circle height`() {
        assertEquals(
            (1080 - 640) / 2 to 300,
            BubbleOverlayPosition.panel(null, null, 640, 880, 1080, 1920, fallbackY = 300),
        )
    }

    @Test
    fun `the panel reopens where the owner left it`() {
        assertEquals(
            120 to 640,
            BubbleOverlayPosition.panel(120, 640, 640, 880, 1080, 1920, fallbackY = 0),
        )
    }

    @Test
    fun `a remembered panel position is clamped after a rotation`() {
        // The new display is narrower and shorter than the remembered spot.
        assertEquals(
            0 to 20,
            BubbleOverlayPosition.panel(900, 900, 640, 880, 500, 900, fallbackY = 0),
        )
        assertEquals(
            0 to 0,
            BubbleOverlayPosition.panel(-80, -120, 640, 880, 500, 900, fallbackY = 0),
        )
    }

    // --- edge docking (T10) -----------------------------------------------

    @Test
    fun `a docked circle hangs off its own edge by the peek fraction`() {
        // 112 px wide at a 55% peek: 62 px stay visible, the other 50 run off.
        assertEquals(-50, BubbleOverlayPosition.docked(0, 112, 1080, 0.55f))
        assertEquals(1018, BubbleOverlayPosition.docked(968, 112, 1080, 0.55f))
    }

    @Test
    fun `the dock keeps more than half the circle touchable`() {
        val peek = 0.55f
        val left = BubbleOverlayPosition.docked(0, 112, 1080, peek)
        val right = BubbleOverlayPosition.docked(968, 112, 1080, peek)

        val visibleLeft = left + 112
        val visibleRight = 1080 - right
        assertEquals(62, visibleLeft)
        assertEquals(62, visibleRight)
        assertTrue("a docked target must stay hittable", visibleLeft > 112 / 2)
        assertTrue("a docked target must stay hittable", visibleRight > 112 / 2)
    }

    @Test
    fun `a docked circle sits closer to its edge than the snapped position`() {
        val snappedLeft = BubbleOverlayPosition.snapped(20, 500, 112, 112, 1080, 1920).first
        val snappedRight = BubbleOverlayPosition.snapped(900, 500, 112, 112, 1080, 1920).first

        assertTrue(BubbleOverlayPosition.docked(snappedLeft, 112, 1080, 0.55f) < snappedLeft)
        assertTrue(BubbleOverlayPosition.docked(snappedRight, 112, 1080, 0.55f) > snappedRight)
    }

    @Test
    fun `undocking returns the fully visible snapped position`() {
        val dockLeft = BubbleOverlayPosition.docked(0, 112, 1080, 0.55f)
        val dockRight = BubbleOverlayPosition.docked(968, 112, 1080, 0.55f)

        assertEquals(0, BubbleOverlayPosition.undocked(dockLeft, 112, 1080))
        assertEquals(1080 - 112, BubbleOverlayPosition.undocked(dockRight, 112, 1080))
        // Undocking is idempotent: an already visible bubble snaps to itself.
        assertEquals(0, BubbleOverlayPosition.undocked(0, 112, 1080))
        assertEquals(1080 - 112, BubbleOverlayPosition.undocked(968, 112, 1080))
    }

    @Test
    fun `the dock waits for the idle window before sliding`() {
        assertEquals(2_500L, BubbleDock.IDLE_BEFORE_DOCK_MILLIS)
        assertTrue("about two and a half seconds", BubbleDock.IDLE_BEFORE_DOCK_MILLIS in 2_000..3_000)
        assertFalse("a fresh touch must not dock", BubbleDock.shouldDock(2_000, 1_000, false, false))
        assertTrue("idle with the circle resting docks it", BubbleDock.shouldDock(3_500, 1_000, false, false))
        assertFalse("a dragging finger must not dock", BubbleDock.shouldDock(9_000, 1_000, true, false))
        assertFalse("the expanded panel never docks", BubbleDock.shouldDock(9_000, 1_000, false, true))
    }

    @Test
    fun `docking marks the collapsed window as layout-no-limits`() {
        // Negative x needs FLAG_LAYOUT_NO_LIMITS; the expanded panel keeps the
        // plain flags because it is always fully on screen.
        val collapsed = BubbleWindowSpecs.forMode(BubbleMode.COLLAPSED, 0, 1080, 1920, 2f)
        val expanded = BubbleWindowSpecs.forMode(BubbleMode.EXPANDED, 0, 1080, 1920, 2f)

        assertTrue(collapsed.flags and BubbleWindowSpecs.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(0, expanded.flags and BubbleWindowSpecs.FLAG_LAYOUT_NO_LIMITS)
    }

    // --- keyboard and navigation (T16) ------------------------------------

    @Test
    fun `the expanded panel watches outside touches`() {
        val collapsed = BubbleWindowSpecs.forMode(BubbleMode.COLLAPSED, 0, 1080, 1920, 2f)
        val expanded = BubbleWindowSpecs.forMode(BubbleMode.EXPANDED, 0, 1080, 1920, 2f)

        assertTrue(
            expanded.flags and BubbleWindowSpecs.FLAG_WATCH_OUTSIDE_TOUCH != 0,
        )
        assertEquals(
            0,
            collapsed.flags and BubbleWindowSpecs.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun `releasing focus makes the expanded window non focusable`() {
        val expanded = BubbleWindowSpecs.forMode(BubbleMode.EXPANDED, 0, 1080, 1920, 2f)
        val released = BubbleWindowSpecs.withFocus(expanded, focusable = false)

        assertFalse(released.focusable)
        assertTrue(released.flags and notFocusable != 0)
    }

    @Test
    fun `taking focus back restores the expanded flags`() {
        val expanded = BubbleWindowSpecs.forMode(BubbleMode.EXPANDED, 0, 1080, 1920, 2f)
        val restored = BubbleWindowSpecs.withFocus(
            BubbleWindowSpecs.withFocus(expanded, focusable = false),
            focusable = true,
        )

        assertTrue(restored.focusable)
        assertEquals(0, restored.flags and notFocusable)
        assertEquals(expanded, restored)
    }
}
