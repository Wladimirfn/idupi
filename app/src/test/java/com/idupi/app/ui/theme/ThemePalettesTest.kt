package com.idupi.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palette resolution for the three theme modes: presets only change the accent
 * over the existing base palettes; custom mode uses the four ARGB colours and
 * derives a readable border/secondary ink.
 */
class ThemePalettesTest {

    @Test
    fun `dark mode keeps the dark base and applies the preset accent`() {
        val colors = ThemePalettes.resolve(
            ThemeSettings(mode = ThemeMode.DARK, preset = ThemePreset.EMERALD)
        )

        assertEquals(DarkAppColors.bg, colors.bg)
        assertEquals(DarkAppColors.card, colors.card)
        assertEquals(Color(0xFF10B981), colors.primary)
        assertEquals(Color(0xFF059669), colors.secondary)
        assertFalse(colors.isLight)
    }

    @Test
    fun `light mode keeps the light base and applies the preset accent`() {
        val colors = ThemePalettes.resolve(
            ThemeSettings(mode = ThemeMode.LIGHT, preset = ThemePreset.AMBER)
        )

        assertEquals(LightAppColors.bg, colors.bg)
        assertEquals(LightAppColors.card, colors.card)
        assertEquals(Color(0xFFF59E0B), colors.primary)
        assertTrue(colors.isLight)
    }

    @Test
    fun `custom mode uses the chosen background, card, text and accent`() {
        val custom = ThemeColors(
            accentArgb = 0xFF10B981.toInt(),
            backgroundArgb = 0xFFF8FAFC.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            textArgb = 0xFF0F172A.toInt(),
        )

        val colors = ThemePalettes.resolve(
            ThemeSettings(mode = ThemeMode.CUSTOM, custom = custom)
        )

        assertEquals(Color(0xFFF8FAFC), colors.bg)
        assertEquals(Color(0xFFFFFFFF), colors.card)
        assertEquals(Color(0xFF0F172A), colors.textPrimary)
        assertEquals(Color(0xFF10B981), colors.primary)
        assertEquals(Color(0xFF10B981), colors.secondary)
        assertTrue("a near-white background must pick the light M3 scheme", colors.isLight)
    }

    @Test
    fun `custom dark background selects the dark M3 scheme and a derived border`() {
        val colors = ThemePalettes.resolve(
            ThemeSettings(
                mode = ThemeMode.CUSTOM,
                custom = ThemeColors(
                    backgroundArgb = 0xFF0B0F19.toInt(),
                    cardArgb = 0xFF161E2E.toInt(),
                    textArgb = 0xFFF8FAFC.toInt(),
                ),
            )
        )

        assertFalse(colors.isLight)
        assertEquals(Color(0xFF0B0F19), colors.bg)
        // Border and secondary text are derived, never identical to card/text.
        assertTrue(colors.border != colors.card)
        assertTrue(colors.textSecondary != colors.textPrimary)
    }

    @Test
    fun `every preset has a label and a distinct accent`() {
        assertEquals(3, ThemePalettes.presets.size)
        assertEquals(3, ThemePalettes.presets.map { it.accent }.toSet().size)
        assertEquals("Índigo", ThemePalettes.labelFor(ThemePreset.INDIGO))
        assertEquals("Esmeralda", ThemePalettes.labelFor(ThemePreset.EMERALD))
        assertEquals("Ámbar", ThemePalettes.labelFor(ThemePreset.AMBER))
    }

    @Test
    fun `the custom editor offers twelve distinct swatches`() {
        assertEquals(12, ThemePalettes.customSwatchArgb.size)
        assertEquals(12, ThemePalettes.customSwatchArgb.toSet().size)
    }
}
