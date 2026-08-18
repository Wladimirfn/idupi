package com.example.idupi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * IDUPI Design System — Spacing and Shape tokens.
 *
 * All spacing and corner radii in the app must come from here.
 * Never use raw dp values inline.
 */
object AppSpacing {

    // Spacing scale
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    // Screen padding
    val screenPadding: Dp = 20.dp

    // Card internal padding
    val cardPadding: Dp = 16.dp

    // Section gap (between major sections)
    val sectionGap: Dp = 24.dp

    // Item gap (between items in a list)
    val itemGap: Dp = 12.dp
}

object AppShapes {
    val card = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(24.dp)
    val button = RoundedCornerShape(12.dp)
    val input = RoundedCornerShape(24.dp)
    val dialog = RoundedCornerShape(16.dp)
    val bubble = RoundedCornerShape(16.dp)
    val small = RoundedCornerShape(6.dp)
}
