package com.idupi.app.data.phone.bubble

import com.idupi.app.domain.model.snapBubbleToEdge
import kotlin.math.roundToInt

/** The two shapes of the bubble window. */
enum class BubbleMode { COLLAPSED, EXPANDED }

/**
 * Whether the accessibility service may draw the bubble (phone-bubble T4).
 * All three are required: the owner's setting, the phone-control master switch
 * and the system accessibility grant the service itself represents.
 */
object BubbleGate {
    fun shouldShow(
        bubbleEnabled: Boolean,
        phoneControlEnabled: Boolean,
        accessibilityConnected: Boolean,
    ): Boolean = bubbleEnabled && phoneControlEnabled && accessibilityConnected
}

/**
 * One window configuration. [flags] is the full `WindowManager.LayoutParams`
 * flags value; [focusable] mirrors whether the keyboard can reach the panel,
 * which is the one thing the collapsed/expanded switch must get right.
 */
data class BubbleWindowSpec(
    val width: Int,
    val height: Int,
    val flags: Int,
    val focusable: Boolean,
)

/**
 * Sizes and focus flags for the bubble window.
 *
 * The collapsed pill is a small non-focusable target so the app underneath
 * keeps the IME; the expanded panel drops `FLAG_NOT_FOCUSABLE` so the text
 * field can take input. The panel never grows past the screen: a 320 dp card
 * on a 360 dp phone would otherwise hang off the right edge.
 */
object BubbleWindowSpecs {

    const val COLLAPSED_DP = 56
    const val PANEL_WIDTH_DP = 320
    const val PANEL_HEIGHT_DP = 440

    /** Breathing room kept between the expanded panel and the screen edges. */
    const val SCREEN_MARGIN_DP = 12

    /**
     * `WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE`. Declared here so the
     * pure model (and its JVM tests) never touches the platform class; the
     * value is part of the public API and does not change.
     */
    const val FLAG_NOT_FOCUSABLE = 0x00000008

    fun forMode(
        mode: BubbleMode,
        baseFlags: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
    ): BubbleWindowSpec = when (mode) {
        BubbleMode.COLLAPSED -> {
            val size = dp(COLLAPSED_DP, density)
            BubbleWindowSpec(
                width = size,
                height = size,
                flags = baseFlags or FLAG_NOT_FOCUSABLE,
                focusable = false,
            )
        }
        BubbleMode.EXPANDED -> {
            val margin = dp(SCREEN_MARGIN_DP, density)
            BubbleWindowSpec(
                width = minOf(dp(PANEL_WIDTH_DP, density), (screenWidthPx - margin).coerceAtLeast(1)),
                height = minOf(dp(PANEL_HEIGHT_DP, density), (screenHeightPx - margin).coerceAtLeast(1)),
                flags = baseFlags and FLAG_NOT_FOCUSABLE.inv(),
                focusable = true,
            )
        }
    }

    private fun dp(value: Int, density: Float): Int =
        (value * density).roundToInt().coerceAtLeast(1)
}

/**
 * Window position maths. The pill is dragged freely and snaps to the nearest
 * vertical edge on release, reusing the same rule as the fullscreen bubble
 * (`snapBubbleToEdge`); the expanded panel is centered horizontally.
 */
object BubbleOverlayPosition {

    fun clamped(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    fun snapped(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val (clampedX, clampedY) = clamped(x, y, width, height, screenWidth, screenHeight)
        val innerW = (screenWidth - width).coerceAtLeast(0).toFloat()
        val innerH = (screenHeight - height).coerceAtLeast(0).toFloat()
        val (snappedX, snappedY) = snapBubbleToEdge(
            clampedX.toFloat(),
            clampedY.toFloat(),
            innerW,
            innerH,
        )
        return snappedX.roundToInt() to snappedY.roundToInt()
    }

    fun centeredX(width: Int, screenWidth: Int): Int =
        ((screenWidth - width) / 2).coerceAtLeast(0)
}
