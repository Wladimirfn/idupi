package com.idupi.app.domain.model

/**
 * Maps a touch inside the rendered-frame node to normalised monitor
 * coordinates (0..1). The frame fills its node exactly (ContentScale
 * .FillBounds), so this is a plain division with clamping; degenerate nodes
 * yield null.
 */
fun touchToMonitorFraction(
    touchXPx: Float,
    touchYPx: Float,
    nodeWPx: Float,
    nodeHPx: Float
): Pair<Double, Double>? {
    if (nodeWPx <= 0f || nodeHPx <= 0f) return null
    val nx = (touchXPx / nodeWPx).toDouble().coerceIn(0.0, 1.0)
    val ny = (touchYPx / nodeHPx).toDouble().coerceIn(0.0, 1.0)
    return nx to ny
}

/**
 * Fullscreen crop-fill variant (owner's "fill every phone edge" mode):
 * ContentScale.Crop scales the captured frame so it COVERs the phone node,
 * centring it. A portion of the monitor falls off the visible area; tapping
 * THERE must return null rather than clamping, so the server never receives
 * a fake edge tap for a region the user could not see.
 *
 * Maths mirrors what Compose draws: display = frame * s centred on the node,
 * offset = (display - node)/2, and fractions come from the inner rectangle
 * display - offset.
 */
fun touchToMonitorFractionCropped(
    touchXPx: Float,
    touchYPx: Float,
    nodeWPx: Float,
    nodeHPx: Float,
    frameWPx: Float,
    frameHPx: Float
): Pair<Double, Double>? {
    if (nodeWPx <= 0f || nodeHPx <= 0f || frameWPx <= 0f || frameHPx <= 0f) return null
    val s = maxOf(nodeWPx / frameWPx, nodeHPx / frameHPx)
    val dispW = frameWPx * s
    val dispH = frameHPx * s
    val offsetX = (dispW - nodeWPx) / 2f
    val offsetY = (dispH - nodeHPx) / 2f
    // Image content starts at (-offset), so adding offset compensates.
    val ix = touchXPx + offsetX
    val iy = touchYPx + offsetY
    // Outside the cropped-in window: nothing to send.
    if (ix < 0f || ix > dispW || iy < 0f || iy > dispH) return null
    return (ix / dispW).toDouble() to (iy / dispH).toDouble()
}
