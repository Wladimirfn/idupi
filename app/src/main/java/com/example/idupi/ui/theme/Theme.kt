package com.example.idupi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun IDUPITheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    
    val m3ColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = appColors.primary,
            secondary = appColors.secondary,
            background = appColors.bg,
            surface = appColors.card,
            onPrimary = appColors.textPrimary,
            onSecondary = appColors.textPrimary,
            onBackground = appColors.textPrimary,
            onSurface = appColors.textPrimary,
            outline = appColors.border
        )
    } else {
        lightColorScheme(
            primary = appColors.primary,
            secondary = appColors.secondary,
            background = appColors.bg,
            surface = appColors.card,
            onPrimary = appColors.textPrimary,
            onSecondary = appColors.textPrimary,
            onBackground = appColors.textPrimary,
            onSurface = appColors.textPrimary,
            outline = appColors.border
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            content = content
        )
    }
}
