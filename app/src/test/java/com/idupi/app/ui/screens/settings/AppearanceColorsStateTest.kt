package com.idupi.app.ui.screens.settings

import com.idupi.app.domain.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personalizado colour-editor expansion (settings redesign T3): the owner can
 * collapse the editor without changing the theme, and choosing Personalizado
 * opens it once so the colours are visible right away.
 */
class AppearanceColorsStateTest {

    @Test
    fun `the editor opens collapsed even when the stored mode is already custom`() {
        assertFalse(COLORS_EXPANDED_ON_ENTRY)
    }

    @Test
    fun `choosing custom from another mode expands the editor`() {
        assertTrue(nextColorsExpanded(ThemeMode.DARK, ThemeMode.CUSTOM, expanded = false))
        assertTrue(nextColorsExpanded(ThemeMode.LIGHT, ThemeMode.CUSTOM, expanded = false))
        // Even a collapsed editor reopens when the owner asks for custom again.
        assertTrue(nextColorsExpanded(ThemeMode.DARK, ThemeMode.CUSTOM, expanded = true))
    }

    @Test
    fun `tapping custom again keeps the owner's own collapse`() {
        assertTrue(nextColorsExpanded(ThemeMode.CUSTOM, ThemeMode.CUSTOM, expanded = true))
        assertFalse(nextColorsExpanded(ThemeMode.CUSTOM, ThemeMode.CUSTOM, expanded = false))
    }

    @Test
    fun `leaving custom collapses the editor for the next visit`() {
        assertFalse(nextColorsExpanded(ThemeMode.CUSTOM, ThemeMode.DARK, expanded = true))
        assertFalse(nextColorsExpanded(ThemeMode.CUSTOM, ThemeMode.LIGHT, expanded = true))
        assertFalse(nextColorsExpanded(ThemeMode.CUSTOM, ThemeMode.LIGHT, expanded = false))
    }
}
