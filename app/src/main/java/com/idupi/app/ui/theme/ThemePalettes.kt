package com.idupi.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.domain.model.relativeLuminance

/**
 * Builds the [AppColors] the app renders with from the persisted
 * [ThemeSettings] (T9).
 *
 * The accents are the same in both base palettes so the three presets stay
 * recognisable; only DARK/LIGHT change the background, card, border and text
 * tones. CUSTOM derives its border and secondary text from the four colours
 * the owner chose, which keeps the rest of the UI consistent without asking
 * for ten colours.
 */
object ThemePalettes {

    data class PresetOption(
        val preset: ThemePreset,
        val label: String,
        val accent: Color,
        val secondary: Color,
    )

    val presets: List<PresetOption> = listOf(
        PresetOption(ThemePreset.INDIGO, "Índigo", Color(0xFF6366F1), Color(0xFF8B5CF6)),
        PresetOption(ThemePreset.EMERALD, "Esmeralda", Color(0xFF10B981), Color(0xFF059669)),
        PresetOption(ThemePreset.AMBER, "Ámbar", Color(0xFFF59E0B), Color(0xFFD97706)),
    )

    /**
     * Curated swatches for the custom editor. Dark tones, light tones and a few
     * saturated accents, so every field starts from sane choices instead of an
     * arbitrary colour picker.
     */
    val customSwatchArgb: List<Int> = listOf(
        0xFF0B0F19.toInt(),
        0xFF0F172A.toInt(),
        0xFF1E293B.toInt(),
        0xFF334155.toInt(),
        0xFFF8FAFC.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFE2E8F0.toInt(),
        0xFF94A3B8.toInt(),
        0xFF6366F1.toInt(),
        0xFF10B981.toInt(),
        0xFFF59E0B.toInt(),
        0xFFEF4444.toInt(),
    )

    fun resolve(settings: ThemeSettings): AppColors = when (settings.mode) {
        ThemeMode.DARK -> darkColors(settings.preset)
        ThemeMode.LIGHT -> lightColors(settings.preset)
        ThemeMode.CUSTOM -> customColors(settings.custom)
    }

    fun darkColors(preset: ThemePreset): AppColors {
        val family = familyFor(preset)
        return DarkAppColors.copy(
            primary = family.accent,
            secondary = family.secondary,
            isLight = false,
        )
    }

    fun lightColors(preset: ThemePreset): AppColors {
        val family = familyFor(preset)
        return LightAppColors.copy(
            primary = family.accent,
            secondary = family.secondary,
            isLight = true,
        )
    }

    fun customColors(colors: ThemeColors): AppColors {
        val background = Color(colors.backgroundArgb)
        val card = Color(colors.cardArgb)
        val text = Color(colors.textArgb)
        val accent = Color(colors.accentArgb)
        return AppColors(
            bg = background,
            card = card,
            // A faint blend of card and text reads as a border in both a dark
            // and a light custom palette.
            border = lerp(card, text, 0.2f),
            textPrimary = text,
            textSecondary = lerp(text, background, 0.35f),
            primary = accent,
            secondary = accent,
            isLight = relativeLuminance(colors.backgroundArgb) > 0.5,
        )
    }

    private fun familyFor(preset: ThemePreset): PresetOption =
        presets.first { it.preset == preset }

    /** Human label used by the preset chips and the summary row. */
    fun labelFor(preset: ThemePreset): String = familyFor(preset).label
}
