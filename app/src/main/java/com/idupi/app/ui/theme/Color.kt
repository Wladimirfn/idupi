package com.idupi.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette the app renders with. Built per [com.idupi.app.domain.model.ThemeSettings]
 * by [ThemePalettes]; the public colour names below resolve through
 * [LocalAppColors], so screens never hold a static palette (theme changes apply
 * immediately, without recreating the screens).
 *
 * [isLight] is the M3 scheme branch. [terminalBg] stays dark on purpose: the
 * PTY/xterm surface keeps the terminal's own colours regardless of the app
 * theme (see PtyTerminalScreen), because light-on-dark ANSI output is what the
 * shells on the PC produce.
 */
data class AppColors(
    val bg: Color,
    val card: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color = Color(0xFF6366F1),
    val secondary: Color = Color(0xFF8B5CF6),
    val statusConnected: Color = Color(0xFF10B981),
    val statusWorking: Color = Color(0xFFF59E0B),
    val statusError: Color = Color(0xFFEF4444),
    val statusTerminal: Color = Color(0xFF38BDF8),
    val isLight: Boolean = false,
    // Terminal chrome (xterm background), NOT a chat wallpaper: it must stay
    // dark even when the app theme is light.
    val terminalBg: Color = Color(0xFF020617)
)

val DarkAppColors = AppColors(
    bg = Color(0xFF0B0F19),
    card = Color(0xFF161E2E),
    border = Color(0xFF242F41),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    isLight = false
)

val LightAppColors = AppColors(
    bg = Color(0xFFF1F5F9),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    isLight = true
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

object IDUPITheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Dynamic color properties resolving based on current Theme
val SlateBg: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.bg
val SlateCard: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.card
val SlateBorder: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.border
val PrimaryIndigo: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.primary
val AccentPurple: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.secondary
val TextPrimary: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.textSecondary

val StatusConnected: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.statusConnected
val StatusWorking: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.statusWorking
val StatusError: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.statusError
val StatusTerminal: Color @Composable @ReadOnlyComposable get() = IDUPITheme.colors.statusTerminal
