package com.idupi.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Applies the resolved palette to both IDUPI's own colours ([LocalAppColors])
 * and Material 3.
 *
 * [colors] comes from [ThemePalettes], so a Settings change re-renders every
 * screen immediately; nothing here reads a global mutable palette.
 */
@Composable
fun IDUPITheme(
    colors: AppColors = DarkAppColors,
    content: @Composable () -> Unit
) {
    val surfaceVariant = lerp(colors.card, colors.bg, 0.5f)
    // A light accent (amber, emerald in light mode) needs dark ink on top of
    // filled buttons; a dark accent needs white. Hard-coding textPrimary used
    // to make amber buttons unreadable.
    val onAccent = if (colors.primary.luminance() > 0.5f) Color(0xFF0F172A) else Color.White
    val onError = if (colors.statusError.luminance() > 0.5f) Color(0xFF0F172A) else Color.White

    val m3ColorScheme = if (colors.isLight) {
        lightColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            background = colors.bg,
            surface = colors.card,
            surfaceVariant = surfaceVariant,
            onPrimary = onAccent,
            onSecondary = onAccent,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.statusError,
            onError = onError
        )
    } else {
        darkColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            background = colors.bg,
            surface = colors.card,
            surfaceVariant = surfaceVariant,
            onPrimary = onAccent,
            onSecondary = onAccent,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.statusError,
            onError = onError
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides colors
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            content = content
        )
    }
}
