package com.idupi.app.domain.model

/**
 * Fullscreen floating bubble (owner design, YouTube/Messenger style): the
 * user drags it anywhere and on release it SNAPS to the nearest vertical
 * edge, keeping whatever height it was dropped at.
 *
 * Pure maths so the gesture code stays declarative: the caller passes the
 * bubble's top-left position in an area whose width/height already exclude
 * the bubble's own size, and gets back the clamped, edge-snapped position.
 */
fun snapBubbleToEdge(x: Float, y: Float, innerW: Float, innerH: Float): Pair<Float, Float> {
    val cx = x.coerceIn(0f, innerW)
    val cy = y.coerceIn(0f, innerH)
    return if (cx <= innerW / 2f) 0f to cy else innerW to cy
}
