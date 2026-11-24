package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast maths used by the custom-theme editor's readability warning.
 */
class ThemeSettingsTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val indigo = 0xFF6366F1.toInt()
    private val darkBackground = 0xFF0B0F19.toInt()
    private val lightText = 0xFFF8FAFC.toInt()

    @Test
    fun `black on white is the maximum 21 to 1`() {
        assertEquals(21.0, contrastRatioArgb(black, white), 0.01)
        assertEquals(21.0, contrastRatioArgb(white, black), 0.01)
    }

    @Test
    fun `identical colours have a ratio of one`() {
        assertEquals(1.0, contrastRatioArgb(indigo, indigo), 0.0001)
    }

    @Test
    fun `the default light text on the default dark background is readable`() {
        val ratio = contrastRatioArgb(lightText, darkBackground)
        assertTrue("ratio was $ratio", ratio >= MIN_READABLE_CONTRAST_RATIO)
        assertFalse(isLowContrast(lightText, darkBackground))
    }

    @Test
    fun `mid grey text on its own background is flagged as low contrast`() {
        val grey = 0xFF808080.toInt()
        assertTrue(isLowContrast(grey, grey))
        assertEquals(1.0, contrastRatioArgb(grey, grey), 0.0001)
    }

    @Test
    fun `luminance ignores the alpha channel`() {
        val opaque = 0xFF6366F1.toInt()
        val sameRgbWithAlpha = 0x806366F1.toInt()
        assertEquals(relativeLuminance(opaque), relativeLuminance(sameRgbWithAlpha), 0.0001)
    }
}
