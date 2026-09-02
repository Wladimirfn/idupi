package com.idupi.app.domain.model

/** How far back a rate reading looks. Long enough to be steady, short enough to react. */
private const val DEFAULT_WINDOW_MS = 2_000L

/**
 * Arrival timestamps still inside the window, oldest first.
 *
 * Pruning is what keeps a session that runs for hours from accumulating a
 * timestamp per frame forever.
 */
fun recentArrivals(
    arrivalsMs: List<Long>,
    nowMs: Long,
    windowMs: Long = DEFAULT_WINDOW_MS
): List<Long> = arrivalsMs.filter { it > nowMs - windowMs }

/**
 * Frames per second over the last [windowMs], or 0 when no rate can be known.
 *
 * The stats line used to show a frame COUNTER, which only grows and answers
 * nothing: 400 frames is the same number over ten seconds or ten minutes. What
 * tells you whether the stream is healthy is its rate right now.
 *
 * Measured across the span between the arrivals themselves rather than across
 * the whole window, so the reading is honest from the second frame on instead
 * of ramping up while the window fills. A stream that stopped reads 0 rather
 * than holding its last good number, because a frozen picture that still
 * claims 18 fps is worse than no number at all.
 */
fun frameRate(
    arrivalsMs: List<Long>,
    nowMs: Long,
    windowMs: Long = DEFAULT_WINDOW_MS
): Int {
    val recent = recentArrivals(arrivalsMs, nowMs, windowMs)
    if (recent.size < 2) return 0
    val span = recent.last() - recent.first()
    if (span <= 0) return 0
    return Math.round((recent.size - 1) * 1000.0 / span).toInt()
}
