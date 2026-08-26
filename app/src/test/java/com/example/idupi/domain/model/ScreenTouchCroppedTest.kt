package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fullscreen crop-fill: the image is shown with ContentScale.Crop so it
 * FILLBounds the phone screen edge to edge, which means a portion of the
 * stream falls off the visible area (and tapping there must NOT send a
 * touch event for the part of the PC the user can't actually see). The
 * visible window is the centred rectangle of the same aspect as the node,
 * sized to cover the node from inside the captured frame.
 */
class ScreenTouchCroppedTest {

    @Test
    fun `tap at the centre maps to the centre of the monitor`() {
        // 1600x738 phone showing a 1920x1080 monitor crop-filled:
        // scale s = max(1600/1920, 738/1080) = max(0.833, 0.683) = 0.833
        // displayed = 1600x900, offsetY = (900-738)/2 = 81
        val r = touchToMonitorFractionCropped(800f, 369f, 1600f, 738f, 1920f, 1080f)
        assertNotNull(r)
        val (x, y) = r!!
        assertEquals(0.5, x, 0.005)
        assertEquals(0.5, y, 0.005)
    }

    @Test
    fun `tap at the right phone edge still maps inside the monitor when width fits`() {
        // Phone 1600x738 on 1920x1080: width fits exactly (s by width), so
        // dispW == nodeW. Right edge of phone IS inside the displayed image.
        val r = touchToMonitorFractionCropped(1599f, 369f, 1600f, 738f, 1920f, 1080f)
        assertNotNull(r)
        val (x, _) = r!!
        assertEquals(0.999, x, 0.01)
    }

    @Test
    fun `tap at the top phone edge maps near the top of the monitor when height overflows`() {
        // Crop covers height: top phone pixel shows ~9% down on the monitor
        // (81px of 900 displayed height are above the phone, but phone y=0
        // is 81px into the image, still 9% on monitor -- valid, not off-screen).
        val r = touchToMonitorFractionCropped(800f, 0f, 1600f, 738f, 1920f, 1080f)
        assertNotNull(r)
        val (_, y) = r!!
        assertEquals(0.09, y, 0.01)
    }

    @Test
    fun `tap inside the visible band maps to a valid monitor fraction`() {
        // The visible band is rows 81..819 in phone space (y=81 is the top
        // of the monitor in the image); pick a point inside it.
        val r = touchToMonitorFractionCropped(400f, 400f, 1600f, 738f, 1920f, 1080f)
        assertNotNull(r)
        val (x, y) = r!!
        // x: (400 + 0)/1600 = 0.25
        // y: (400 + 81)/900 = 0.534
        assertEquals(0.25, x, 0.005)
        assertEquals(0.534, y, 0.005)
    }
}
