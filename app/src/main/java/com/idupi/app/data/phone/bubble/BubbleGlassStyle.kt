package com.idupi.app.data.phone.bubble

import com.idupi.app.domain.model.relativeLuminance

/**
 * Translucent "liquid glass" colours for the bubble, derived from the owner's
 * theme (phone-bubble T9).
 *
 * The overlay is drawn by the accessibility service with classic Views, so the
 * Compose `AppColors` accessors (`@Composable`-only) are out of reach. The
 * service resolves the palette with `ThemePalettes.resolve(themeSettings)` and
 * converts each `Color` with `toArgb()`; this object turns that opaque palette
 * into the alphas the glass surfaces need. All alphas live here, not in the
 * overlay, so the look is pinned by JVM tests instead of by eye on a phone.
 *
 * The rule for the mark: white on a dark palette, the palette's own text ink on
 * a light one. A white hedgehog over a white glass circle would vanish, so the
 * light branch follows [textPrimary] instead of hard-coding white.
 */
data class BubbleGlassStyle(
    /** The owner's accent; also tints the collapsed circle's fill. */
    val primary: Int,
    /** The owner's secondary accent, the rim's second stop. */
    val secondary: Int,
    /** App background, used for the glass input well. */
    val bg: Int,
    /** App card colour: the panel's glass body before alpha is applied. */
    val card: Int,
    /** A faint blend of card and text: the crisp edge of light glass. */
    val border: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val isLight: Boolean,
    /** Translucent circle body: the primary accent at a glassy alpha. */
    val circleFill: Int,
    /** Thin light rim around the circle. */
    val circleRim: Int,
    /** Soft top highlight over the circle's upper half. */
    val circleHighlight: Int,
    /** Translucent white (dark themes) or ink (light themes) hedgehog mark. */
    val markTint: Int,
    /** Glass card body of the expanded panel. */
    val panelFill: Int,
    /** Rim stroke around the panel. */
    val panelRim: Int,
    /** Input field body: fainter than the panel so it reads as a well. */
    val inputFill: Int,
    /** Outline stroke for glass-outline buttons and the input field. */
    val glassOutline: Int,
    /** Ink for text drawn on a [primary]-filled pill button. */
    val onPrimary: Int,
    /** Semantic warning, never tinted by the theme. */
    val warning: Int,
    /** Semantic error (also the badge dot), never tinted by the theme. */
    val error: Int,
    /** Fully opaque approval badge dot. */
    val badge: Int,
    /** Alpha the whole collapsed window fades to while docked. */
    val dockedAlpha: Float,
) {

    companion object {

        /** Circle fill alpha: translucent enough to see the app behind it. */
        const val CIRCLE_FILL_ALPHA = 0.42f

        /** Rim alpha for the collapsed circle. */
        const val CIRCLE_RIM_ALPHA = 0.35f

        /** Top highlight sits under the rim so the circle has a light source. */
        const val CIRCLE_HIGHLIGHT_ALPHA = 0.18f

        /** The hedgehog mark is barely translucent so it stays legible. */
        const val MARK_ALPHA = 0.85f

        /** Panel body alpha: opaque enough for text, translucent for glass. */
        const val PANEL_ALPHA = 0.82f

        /** Panel rim: stronger on a light theme, where a faint rim disappears. */
        const val PANEL_RIM_ALPHA_DARK = 0.28f
        const val PANEL_RIM_ALPHA_LIGHT = 0.55f

        /** Input well and outline buttons. */
        const val INPUT_ALPHA_DARK = 0.36f
        const val INPUT_ALPHA_LIGHT = 0.50f
        const val OUTLINE_ALPHA = 0.30f

        /** A docked bubble fades but stays clearly pressable. */
        const val DOCKED_ALPHA = 0.6f

        /** Semantic colours of the app's status palette (same in both themes). */
        const val WARNING = 0xFFF59E0B.toInt()
        const val ERROR = 0xFFEF4444.toInt()

        /** The classic glass rim: a barely-there white edge. */
        private const val RIM_WHITE = 0xFFFFFFFF.toInt()

        /** Near-black ink for bright accents (amber), matching the app's text. */
        private const val INK_DARK = 0xFF1A1206.toInt()

        /**
         * Builds the glass palette from the resolved opaque palette.
         * [isLight] comes from the resolved colours and decides the mark's
         * tint, not the other way around.
         */
        fun from(
            bgArgb: Int,
            cardArgb: Int,
            borderArgb: Int,
            textPrimaryArgb: Int,
            textSecondaryArgb: Int,
            primaryArgb: Int,
            secondaryArgb: Int,
            isLight: Boolean,
        ): BubbleGlassStyle = BubbleGlassStyle(
            primary = primaryArgb,
            secondary = secondaryArgb,
            bg = bgArgb,
            card = cardArgb,
            border = borderArgb,
            textPrimary = textPrimaryArgb,
            textSecondary = textSecondaryArgb,
            isLight = isLight,
            // The circle is the accent, translucent: it reads as tinted glass
            // over whatever app is behind it.
            circleFill = withAlpha(primaryArgb, CIRCLE_FILL_ALPHA),
            circleRim = withAlpha(RIM_WHITE, CIRCLE_RIM_ALPHA),
            circleHighlight = withAlpha(RIM_WHITE, CIRCLE_HIGHLIGHT_ALPHA),
            // A white hedgehog on a light glass circle would vanish.
            markTint = withAlpha(
                if (isLight) textPrimaryArgb else RIM_WHITE,
                MARK_ALPHA,
            ),
            panelFill = withAlpha(cardArgb, PANEL_ALPHA),
            // White glass needs a mid-tone edge; on a dark card the app's own
            // border is too dark to catch the light, so the rim stays white.
            panelRim = if (isLight) {
                withAlpha(borderArgb, PANEL_RIM_ALPHA_LIGHT)
            } else {
                withAlpha(RIM_WHITE, PANEL_RIM_ALPHA_DARK)
            },
            inputFill = withAlpha(bgArgb, if (isLight) INPUT_ALPHA_LIGHT else INPUT_ALPHA_DARK),
            glassOutline = withAlpha(textPrimaryArgb, OUTLINE_ALPHA),
            onPrimary = inkOn(primaryArgb),
            warning = WARNING,
            error = ERROR,
            badge = ERROR,
            dockedAlpha = DOCKED_ALPHA,
        )

        /**
         * Picks the ink for a filled accent button. Amber is bright enough that
         * white on it fails WCAG, so it takes the app's near-black ink instead.
         */
        fun inkOn(accentArgb: Int): Int =
            if (relativeLuminance(accentArgb) > 0.35) INK_DARK else RIM_WHITE

        /** Replaces the alpha byte of [argb], keeping its RGB. */
        fun withAlpha(argb: Int, alpha: Float): Int {
            val byte = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            return (byte shl 24) or (argb and 0x00FFFFFF)
        }
    }
}
