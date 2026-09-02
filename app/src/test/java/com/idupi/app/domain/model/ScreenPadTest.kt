package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating trackpad turns finger travel into cursor travel without the
 * finger ever covering what the user is looking at. All of it is decidable
 * without a screen: deltas, wheel notches and two-finger mode arbitration
 * live here so RemoteScreen only plumbs events.
 */
class ScreenPadTest {

    // --- one-finger drag -> relative cursor movement ---

    @Test
    fun `a full-width sweep travels PAD_CURSOR_TRAVEL_PX`() {
        val (dx, _) = padCursorDelta(dxPx = 400f, dyPx = 0f, padWPx = 400f)
        assertEquals(PAD_CURSOR_TRAVEL_PX, dx, 0.001)
    }

    @Test
    fun `half the pad is half the travel`() {
        val (dx, dy) = padCursorDelta(dxPx = 100f, dyPx = 50f, padWPx = 200f)
        assertEquals(PAD_CURSOR_TRAVEL_PX / 2, dx, 0.001)
        assertEquals(PAD_CURSOR_TRAVEL_PX / 4, dy, 0.001)
    }

    @Test
    fun `a degenerate pad yields zero instead of infinity`() {
        val (dx, dy) = padCursorDelta(dxPx = 30f, dyPx = 40f, padWPx = 0f)
        assertEquals(0.0, dx, 0.0)
        assertEquals(0.0, dy, 0.0)
    }

    @Test
    fun `deltas are signed so moving left goes left`() {
        val (dx, _) = padCursorDelta(dxPx = -50f, dyPx = 0f, padWPx = 100f)
        assertTrue("expected negative delta, got $dx", dx < 0)
    }

    // --- two-finger travel -> wheel notches ---

    @Test
    fun `one notch of travel equals one notch of scroll`() {
        assertEquals(1, padScrollNotches(dyUpPx = PAD_SCROLL_PX_PER_NOTCH))
        assertEquals(-1, padScrollNotches(dyUpPx = -PAD_SCROLL_PX_PER_NOTCH))
    }

    @Test
    fun `sub-notch travel stays at zero until it accumulates`() {
        // Less than HALF a notch never rounds up; the half-notch boundary
        // itself rounds away from zero by design.
        assertEquals(0, padScrollNotches(dyUpPx = PAD_SCROLL_PX_PER_NOTCH * 0.4f))
        assertEquals(0, padScrollNotches(dyUpPx = -PAD_SCROLL_PX_PER_NOTCH * 0.4f))
    }

    @Test
    fun `fractional accumulation rounds instead of truncating to zero forever`() {
        // 1.6 notches must already have emitted its first notch by rounding.
        assertEquals(2, padScrollNotches(dyUpPx = PAD_SCROLL_PX_PER_NOTCH * 1.6f))
    }

    @Test
    fun `wheel deltas ride the Windows 120-per-notch convention`() {
        assertEquals(120, padWheelDelta(1))
        assertEquals(-240, padWheelDelta(-2))
    }

    // --- notebook-touchpad two-finger behaviour (owner model) ---

    @Test
    fun `a pinch step is distance change over the absolute threshold`() {
        // 10px closer on a 300px base is a deliberate pinch.
        assertEquals(true, padIsPinchStep(lastDist = 300f, distNow = 290f))
        // 3px of jitter is fingers resting, not pinching.
        assertEquals(false, padIsPinchStep(lastDist = 300f, distNow = 297f))
        assertEquals(false, padIsPinchStep(lastDist = 300f, distNow = 303f))
    }

    // --- edge scroll zones (owner request): right = vertical, bottom = horiz ---

    @Test
    fun `the right edge zone scrolls vertically`() {
        assertEquals(
            PadZone.SCROLL_V,
            padZoneAt(x = 340f, y = 100f, w = 360f, h = 150f, edgePx = 44f),
        )
    }

    @Test
    fun `the bottom edge zone scrolls horizontally`() {
        assertEquals(
            PadZone.SCROLL_H,
            padZoneAt(x = 180f, y = 140f, w = 360f, h = 150f, edgePx = 44f),
        )
    }

    @Test
    fun `the centre is cursor territory`() {
        assertEquals(
            PadZone.CURSOR,
            padZoneAt(x = 180f, y = 75f, w = 360f, h = 150f, edgePx = 44f),
        )
    }

    @Test
    fun `bottom-right corner resolves to the vertical zone`() {
        assertEquals(
            PadZone.SCROLL_V,
            padZoneAt(x = 350f, y = 145f, w = 360f, h = 150f, edgePx = 44f),
        )
    }

    // --- two-finger mode arbitration ---

    @Test
    fun `distance growth latches PINCH regardless of previous mode`() {
        assertEquals(
            PadMode.PINCH,
            padTwoFingerMode(current = PadMode.SCROLL, distanceRatio = 1f + PAD_PINCH_RATIO_THRESHOLD, travelledPx = 0f),
        )
    }

    @Test
    fun `distance shrink latches PINCH too`() {
        assertEquals(
            PadMode.PINCH,
            padTwoFingerMode(current = PadMode.MOVE, distanceRatio = 1f - PAD_PINCH_RATIO_THRESHOLD, travelledPx = 0f),
        )
    }

    @Test
    fun `parallel travel latches SCROLL`() {
        assertEquals(
            PadMode.SCROLL,
            padTwoFingerMode(current = PadMode.PINCH, distanceRatio = 1f, travelledPx = PAD_SCROLL_LATCH_PX + 1),
        )
    }

    @Test
    fun `ambiguous small movement keeps the previous mode`() {
        assertEquals(
            PadMode.SCROLL,
            padTwoFingerMode(current = PadMode.SCROLL, distanceRatio = 1.01f, travelledPx = 3f),
        )
        assertEquals(
            PadMode.MOVE,
            padTwoFingerMode(current = PadMode.MOVE, distanceRatio = 1.02f, travelledPx = 2f),
        )
    }
}
