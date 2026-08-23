package com.example.idupi.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating trackpad ("modo pad") turns finger travel into cursor travel
 * so the user can aim precisely without a finger covering the picture they
 * are watching. Everything decidable without a screen lives here; the
 * composable only plumbs pointer events in and input events out.
 */

/** Cursor pixels travelled by a full-width one-finger sweep of the pad. */
const val PAD_CURSOR_TRAVEL_PX = 1600.0

/** Two-finger vertical travel that counts as ONE wheel notch. */
const val PAD_SCROLL_PX_PER_NOTCH = 48f

/** Finger-distance growth/shrink that flips two fingers from scroll to pinch. */
const val PAD_PINCH_RATIO_THRESHOLD = 0.12f

/** Parallel two-finger travel that latches scroll mode over an ambiguous start. */
const val PAD_SCROLL_LATCH_PX = 20f

/** What the two fingers are doing once a second one lands on the pad. */
enum class PadMode { MOVE, SCROLL, PINCH }

/**
 * Relative cursor deltas for one-finger pad drags: the pad's width maps to
 * PAD_CURSOR_TRAVEL_PX of screen travel regardless of how wide the pad is
 * rendered, so sensitivity feels the same on any phone. Signed -- dragging
 * left moves the cursor left.
 */
fun padCursorDelta(dxPx: Float, dyPx: Float, padWPx: Float): Pair<Double, Double> {
    if (padWPx <= 0f) return 0.0 to 0.0
    val scale = PAD_CURSOR_TRAVEL_PX / padWPx
    return (dxPx * scale) to (dyPx * scale)
}

/**
 * Wheel notches emitted by accumulated two-finger travel. Positive means
 * scroll UP (Windows wheel convention). Rounds rather than truncates so slow
 * drags still emit their first notch before the finger lifts.
 */
fun padScrollNotches(dyUpPx: Float): Int = (dyUpPx / PAD_SCROLL_PX_PER_NOTCH).roundToInt()

/** Windows wheel units for N notches -- one physical notch is 120. */
fun padWheelDelta(notches: Int): Int = notches * 120

/**
 * Mode arbitration between two fingers: distance growth or shrink beyond the
 * threshold is a pinch-zoom and latches PINCH; parallel travel past the
 * latch distance latches SCROLL; anything more ambiguous keeps the mode the
 * pair already had so a sloppy second-finger landing does not jump modes.
 */
fun padTwoFingerMode(current: PadMode, distanceRatio: Float, travelledPx: Float): PadMode =
    when {
        abs(distanceRatio - 1f) >= PAD_PINCH_RATIO_THRESHOLD -> PadMode.PINCH
        abs(travelledPx) >= PAD_SCROLL_LATCH_PX -> PadMode.SCROLL
        else -> current
    }
