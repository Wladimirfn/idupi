package com.idupi.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * IDUPI Design System — Typography scale.
 *
 * All font sizes in the app must come from here.
 * Never use raw `fontSize = XX.sp` inline.
 */
object AppTypography {

    /** Screen titles, hero text. */
    val displayLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp
    )

    /** Section headers inside screens. */
    val titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    )

    /** Section labels (uppercase). */
    val titleSmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )

    /** Primary body text. */
    val bodyLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    )

    /** Secondary/description text. */
    val bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal
    )

    /** Badges, labels, meta info. */
    val labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )

    /** Monospace for code and terminal. */
    val codeMono = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.sp
    )

    /** Chat bubble text. */
    val chatBody = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp
    )

    /** Top app bar title. */
    val appBarTitle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
}
