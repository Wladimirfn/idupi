package com.example.idupi.domain.model

import kotlin.math.roundToInt

/**
 * Frame size to REQUEST for a stream: fits the monitor's aspect into the
 * on-screen box (device pixels), never exceeding the monitor's native
 * resolution -- the single largest saving in the system, since the server
 * scales at capture time and never sends more pixels than the receiver shows.
 *
 * The monitor's ORIGIN never enters this maths (the user's left monitor starts
 * at x=-1920); only the aspect ratio does.
 */
fun viewportFor(monitor: ScreenMonitor?, boxW: Int, boxH: Int): Pair<Int, Int> {
    // No monitor chosen yet, or a degenerate box/native size: a small default
    // keeps the stream usable while the picker settles.
    if (monitor == null || boxW < 1 || boxH < 1 || monitor.width < 1 || monitor.height < 1) {
        return DEFAULT_VIEWPORT
    }
    val scale = minOf(boxW.toFloat() / monitor.width, boxH.toFloat() / monitor.height)
    if (scale >= 1f) return monitor.width to monitor.height
    val w = (monitor.width * scale).roundToInt().coerceAtLeast(1)
    val h = (monitor.height * scale).roundToInt().coerceAtLeast(1)
    return w to h
}

/** Sane fallback when nothing better is known. */
val DEFAULT_VIEWPORT: Pair<Int, Int> = 800 to 450
