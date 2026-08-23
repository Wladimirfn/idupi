package com.example.idupi.domain.model

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
