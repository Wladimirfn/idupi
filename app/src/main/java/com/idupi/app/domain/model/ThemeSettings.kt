package com.idupi.app.domain.model

import kotlin.math.pow

/**
 * Persisted app-theme choice (T9).
 *
 * DARK and LIGHT apply [preset]'s accent over the existing dark/light base
 * palettes; CUSTOM uses the four ARGB colours the owner picked. The model is
 * pure Kotlin (no Compose types) so the DataStore layer and the JVM tests can
 * use it without an Android runtime.
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.DARK,
    val preset: ThemePreset = ThemePreset.INDIGO,
    val custom: ThemeColors = ThemeColors(),
)

enum class ThemeMode { DARK, LIGHT, CUSTOM }

enum class ThemePreset { INDIGO, EMERALD, AMBER }

/** Custom-mode colours, stored as ARGB ints. */
data class ThemeColors(
    val accentArgb: Int = ThemeDefaults.accentArgb,
    val backgroundArgb: Int = ThemeDefaults.backgroundArgb,
    val cardArgb: Int = ThemeDefaults.cardArgb,
    val textArgb: Int = ThemeDefaults.textArgb,
)

/**
 * Defaults for the custom editor: the current dark palette, so opening the
 * editor never starts from an unreadable combination.
 */
object ThemeDefaults {
    val accentArgb: Int = 0xFF6366F1.toInt()
    val backgroundArgb: Int = 0xFF0B0F19.toInt()
    val cardArgb: Int = 0xFF161E2E.toInt()
    val textArgb: Int = 0xFFF8FAFC.toInt()
}

/** WCAG 2.1 minimum contrast for normal-size body text. */
const val MIN_READABLE_CONTRAST_RATIO: Double = 4.5

/**
 * WCAG relative luminance of an opaque ARGB colour. Alpha is ignored: the
 * settings editor only offers opaque swatches.
 */
fun relativeLuminance(argb: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    val red = linear((argb shr 16) and 0xFF)
    val green = linear((argb shr 8) and 0xFF)
    val blue = linear(argb and 0xFF)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/**
 * WCAG contrast ratio between two opaque colours, from 1.0 (identical) to 21.0
 * (black on white). The custom editor warns below
 * [MIN_READABLE_CONTRAST_RATIO] instead of silently shipping unreadable text.
 */
fun contrastRatioArgb(foregroundArgb: Int, backgroundArgb: Int): Double {
    val first = relativeLuminance(foregroundArgb)
    val second = relativeLuminance(backgroundArgb)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}

/** True when [textArgb] on [backgroundArgb] is below the readable threshold. */
fun isLowContrast(textArgb: Int, backgroundArgb: Int): Boolean =
    contrastRatioArgb(textArgb, backgroundArgb) < MIN_READABLE_CONTRAST_RATIO
