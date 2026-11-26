package com.idupi.app.data.phone.bubble

import com.idupi.app.domain.model.snapBubbleToEdge
import kotlin.math.roundToInt

/** The two shapes of the bubble window. */
enum class BubbleMode { COLLAPSED, EXPANDED }

/**
 * Whether the accessibility service may draw the bubble (phone-bubble T4).
 * All three are required: the owner's setting, the phone-control master switch
 * and the system accessibility grant the service itself represents.
 *
 * [temporarySessionActive] is the assistant-key T1 override: a launch from the
 * system assistant shows the panel for that session even with the bubble icon
 * (and phone control) off. It never rewrites the persisted setting; the gate
 * returns to the owner's choice as soon as the session ends.
 */
object BubbleGate {
    fun shouldShow(
        bubbleEnabled: Boolean,
        phoneControlEnabled: Boolean,
        accessibilityConnected: Boolean,
        temporarySessionActive: Boolean = false,
    ): Boolean = temporarySessionActive ||
        (bubbleEnabled && phoneControlEnabled && accessibilityConnected)
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

    /**
     * `WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS` (0x00000200).
     *
     * A docked circle needs a negative `x` (or one past the screen width) and
     * the window manager otherwise clamps the window into the display. Only the
     * collapsed spec gets it: the expanded panel is always fully on screen and
     * keeps the stricter layout, which is what ClipToPadding-free windows want.
     */
    const val FLAG_LAYOUT_NO_LIMITS = 0x00000200

    /**
     * `WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH` (0x00040000).
     *
     * Only the expanded panel asks for it: a touch outside the card must reach
     * the bubble so it can hide the keyboard and release window focus (T16).
     * The collapsed pill has nothing to release.
     */
    const val FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000

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
                flags = baseFlags or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
                focusable = false,
            )
        }
        BubbleMode.EXPANDED -> {
            val margin = dp(SCREEN_MARGIN_DP, density)
            BubbleWindowSpec(
                width = minOf(dp(PANEL_WIDTH_DP, density), (screenWidthPx - margin).coerceAtLeast(1)),
                height = minOf(dp(PANEL_HEIGHT_DP, density), (screenHeightPx - margin).coerceAtLeast(1)),
                flags = ((baseFlags and FLAG_LAYOUT_NO_LIMITS.inv()) and
                    FLAG_NOT_FOCUSABLE.inv()) or FLAG_WATCH_OUTSIDE_TOUCH,
                focusable = true,
            )
        }
    }

    /**
     * Applies the owner's focus choice to an expanded spec (T16): dropping
     * `FLAG_NOT_FOCUSABLE` lets the field take the IME; re-adding it gives
     * BACK and system navigation back to the app behind. Pure so the toggle is
     * tested instead of only felt on the phone.
     */
    fun withFocus(spec: BubbleWindowSpec, focusable: Boolean): BubbleWindowSpec =
        if (focusable) {
            spec.copy(
                flags = spec.flags and FLAG_NOT_FOCUSABLE.inv(),
                focusable = true,
            )
        } else {
            spec.copy(
                flags = spec.flags or FLAG_NOT_FOCUSABLE,
                focusable = false,
            )
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

    /**
     * Where the expanded panel opens (T15). The first expansion centers it
     * horizontally at [fallbackY] (the circle's current height); afterwards the
     * remembered spot wins. Either way the result is clamped to the screen, so
     * a rotation while the bubble lives can never leave the panel off-screen.
     */
    fun panel(
        rememberedX: Int?,
        rememberedY: Int?,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        fallbackY: Int,
    ): Pair<Int, Int> = clamped(
        rememberedX ?: centeredX(width, screenWidth),
        rememberedY ?: fallbackY,
        width,
        height,
        screenWidth,
        screenHeight,
    )

    /**
     * Which vertical edge [x] belongs to. The single predicate behind snapping,
     * docking and undocking, so a dock always returns to the edge it left.
     */
    fun snapsLeft(x: Int, size: Int, screenWidth: Int): Boolean =
        x <= (screenWidth - size).coerceAtLeast(0) / 2

    /**
     * The x of a docked circle: [peekFraction] of the circle stays on screen,
     * the rest slides past the edge it is snapped to (T10).
     *
     * Left edge -> negative x; right edge -> `x + size` past the screen width.
     * The collapsed window carries `FLAG_LAYOUT_NO_LIMITS` so the window manager
     * accepts both. The edge is inferred from [x]'s half of the screen, so the
     * caller can pass any position and get the dock of its nearest edge.
     */
    fun docked(x: Int, size: Int, screenWidth: Int, peekFraction: Float): Int {
        val peek = (size * peekFraction.coerceIn(0f, 1f)).roundToInt()
        val hidden = (size - peek).coerceAtLeast(0)
        return if (snapsLeft(x, size, screenWidth)) -hidden else screenWidth - peek
    }

    /**
     * The fully visible snapped x for a docked (or any) x: undocking springs
     * back to the same edge, so a left dock returns 0 and a right dock returns
     * `screenWidth - size`.
     */
    fun undocked(x: Int, size: Int, screenWidth: Int): Int {
        val innerW = (screenWidth - size).coerceAtLeast(0)
        return if (snapsLeft(x, size, screenWidth)) 0 else innerW
    }
}

/**
 * When the circle docks itself at the edge (T10).
 *
 * The accessibility service owns the timer; the decision is pure so the "idle
 * long enough, not dragging, not expanded" rule is testable. A pending approval
 * does not prevent docking (the badge stays visible either way).
 */
object BubbleDock {

    /** How long the circle must rest untouched before it tucks itself away. */
    const val IDLE_BEFORE_DOCK_MILLIS = 2_500L

    /** Duration of the slide into and out of the dock. */
    const val ANIMATION_MILLIS = 250L

    fun shouldDock(
        nowMillis: Long,
        lastTouchMillis: Long,
        dragging: Boolean,
        expanded: Boolean,
    ): Boolean = !dragging && !expanded && nowMillis - lastTouchMillis >= IDLE_BEFORE_DOCK_MILLIS
}
