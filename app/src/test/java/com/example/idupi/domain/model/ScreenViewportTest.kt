package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The client must request its VIEWPORT size in device pixels -- the server
 * scales at capture and never sends more pixels than the receiver displays.
 * The user's left monitor starts at x=-1920, but origin does not matter here:
 * only width/height aspect does.
 */
class ScreenViewportTest {

    private val monitor = ScreenMonitor(id = 1, name = "DISPLAY2", primary = true, width = 1920, height = 1080)

    @Test
    fun `a box smaller than native scales down preserving monitor aspect`() {
        // Phone showing a 1920x1080 desktop in an 800x450 box: request 800x450.
        assertEquals(800 to 450, viewportFor(monitor, boxW = 800, boxH = 450))
    }

    @Test
    fun `a box bigger than native never asks for more than native pixels`() {
        assertEquals(1920 to 1080, viewportFor(monitor, boxW = 2400, boxH = 1400))
    }

    @Test
    fun `a tall phone box fits by width, not by height`() {
        // 400x800 box around a 16:9 desktop: height would allow 225*... no,
        // width binds: 400x(400*9/16)=225.
        assertEquals(400 to 225, viewportFor(monitor, boxW = 400, boxH = 800))
    }

    @Test
    fun `portrait monitors fit their own aspect`() {
        val rotated = monitor.copy(width = 1080, height = 1920)
        // Box 405x800: width binds (405/1080 = 0.375 < 800/1920), so the frame
        // stays portrait at 405x720 -- never squeezed into the box's aspect.
        assertEquals(405 to 720, viewportFor(rotated, boxW = 405, boxH = 800))
    }

    @Test
    fun `degenerate inputs fall back to a sane default`() {
        assertEquals(800 to 450, viewportFor(null, boxW = 800, boxH = 450))
        assertEquals(800 to 450, viewportFor(monitor, boxW = 0, boxH = 450))
        assertEquals(800 to 450, viewportFor(monitor.copy(width = 0), boxW = 800, boxH = 450))
    }
}
