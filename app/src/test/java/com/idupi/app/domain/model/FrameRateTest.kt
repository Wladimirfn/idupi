package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stats line showed a frame COUNTER, which only ever grows and says
 * nothing: 400 frames is the same number whether they arrived over ten
 * seconds or ten minutes. What the viewer needs to judge the stream is its
 * rate right now, so a drop from 18 to 4 is visible the moment it happens.
 *
 * Measured over the span between the arrivals themselves rather than over the
 * whole window, so the reading is honest from the second frame instead of
 * ramping up while the window fills.
 */
class FrameRateTest {

    @Test
    fun `no rate can be known from a single frame`() {
        assertEquals(0, frameRate(listOf(1_000L), nowMs = 1_000L))
        assertEquals(0, frameRate(emptyList(), nowMs = 1_000L))
    }

    @Test
    fun `two frames a tenth of a second apart read as ten per second`() {
        assertEquals(10, frameRate(listOf(1_000L, 1_100L), nowMs = 1_100L))
    }

    @Test
    fun `an even stream reads as its true rate`() {
        // Five arrivals, 50ms apart: four gaps over 200ms is 20 fps.
        val arrivals = listOf(0L, 50L, 100L, 150L, 200L)
        assertEquals(20, frameRate(arrivals, nowMs = 200L))
    }

    @Test
    fun `arrivals older than the window are ignored`() {
        // The old burst was fast; the recent pair is slow. The reading must
        // follow the present, not be dragged up by history.
        val arrivals = listOf(0L, 10L, 20L, 5_000L, 5_500L)
        assertEquals(2, frameRate(arrivals, nowMs = 5_500L, windowMs = 2_000L))
    }

    @Test
    fun `a stream that stopped reads as zero, not as its last good rate`() {
        // Nothing arrived for four seconds: the picture is frozen and the
        // number has to say so.
        val arrivals = listOf(0L, 50L, 100L)
        assertEquals(0, frameRate(arrivals, nowMs = 4_000L, windowMs = 2_000L))
    }

    @Test
    fun `arrivals are kept bounded so a long session cannot grow without limit`() {
        val arrivals = (0L until 500L).map { it * 10 }
        val kept = recentArrivals(arrivals, nowMs = 4_990L, windowMs = 2_000L)

        assertEquals(200, kept.size)
        assertEquals(3_000L, kept.first())
    }
}
