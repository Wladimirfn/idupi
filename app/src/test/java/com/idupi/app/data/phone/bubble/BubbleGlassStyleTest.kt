package com.idupi.app.data.phone.bubble

import com.idupi.app.domain.model.contrastRatioArgb
import com.idupi.app.domain.model.relativeLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glass colours derived from the owner's palette (phone-bubble T9).
 *
 * The overlay cannot use Compose colour accessors (they are `@Composable`-only
 * and the bubble lives in a Service), so the mapping from the resolved palette
 * to translucent ARGB values is pure Kotlin and pinned here. The things that
 * break silently on a phone are the alphas (a "glass" fill that is too opaque
 * looks solid; one that is too faint is unreadable) and the ink colour on a
 * light palette.
 */
class BubbleGlassStyleTest {

    private fun style(
        bg: Int = 0xFF0B0F19.toInt(),
        card: Int = 0xFF161E2E.toInt(),
        border: Int = 0xFF242F41.toInt(),
        textPrimary: Int = 0xFFF8FAFC.toInt(),
        textSecondary: Int = 0xFF94A3B8.toInt(),
        primary: Int = 0xFF6366F1.toInt(),
        secondary: Int = 0xFF8B5CF6.toInt(),
        isLight: Boolean = false,
    ): BubbleGlassStyle = BubbleGlassStyle.from(
        bgArgb = bg,
        cardArgb = card,
        borderArgb = border,
        textPrimaryArgb = textPrimary,
        textSecondaryArgb = textSecondary,
        primaryArgb = primary,
        secondaryArgb = secondary,
        isLight = isLight,
    )

    private val dark = style()

    private val light = style(
        bg = 0xFFF1F5F9.toInt(),
        card = 0xFFFFFFFF.toInt(),
        border = 0xFFCBD5E1.toInt(),
        textPrimary = 0xFF0F172A.toInt(),
        textSecondary = 0xFF475569.toInt(),
        isLight = true,
    )

    @Test
    fun `the dark circle is a translucent primary fill with a light rim`() {
        assertEquals(0xFF6366F1.toInt() and 0x00FFFFFF, dark.circleFill and 0x00FFFFFF)
        assertTrue(
            "the circle fill must read as glass, not solid",
            alphaFraction(dark.circleFill) in 0.35f..0.5f,
        )
        assertTrue(
            "the rim must be a light stroke",
            alphaFraction(dark.circleRim) in 0.25f..0.5f &&
                relativeLuminance(dark.circleRim) > 0.05,
        )
        assertTrue(alphaFraction(dark.circleHighlight) < alphaFraction(dark.circleRim))
    }

    @Test
    fun `the mark is translucent white on a dark palette`() {
        assertEquals(0xFFFFFF, dark.markTint and 0x00FFFFFF)
        assertTrue("a translucent white mark", alphaFraction(dark.markTint) in 0.8f..0.92f)
    }

    @Test
    fun `the mark follows the text ink on a light palette`() {
        assertEquals(light.textPrimary and 0x00FFFFFF, light.markTint and 0x00FFFFFF)
        assertTrue(alphaFraction(light.markTint) in 0.8f..0.92f)
        assertTrue("a light theme must say so", light.isLight)
    }

    @Test
    fun `the panel is a translucent card with a rim`() {
        assertEquals(dark.card and 0x00FFFFFF, dark.panelFill and 0x00FFFFFF)
        assertTrue(
            "the panel must stay translucent",
            alphaFraction(dark.panelFill) in 0.78f..0.86f,
        )
        assertTrue(alphaFraction(dark.panelRim) > 0f)
        assertTrue(alphaFraction(light.panelRim) > alphaFraction(dark.panelRim))
    }

    @Test
    fun `the input field is fainter than the panel`() {
        assertTrue(alphaFraction(dark.inputFill) < alphaFraction(dark.panelFill))
        assertTrue(alphaFraction(light.inputFill) < alphaFraction(light.panelFill))
    }

    @Test
    fun `the filled primary button picks a readable ink for every preset accent`() {
        val accents = listOf(0xFF6366F1.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt())
        accents.forEach { accent ->
            val palette = style(primary = accent)
            assertEquals("the button keeps the owner's accent", accent, palette.primary)
            // 4.4 instead of 4.5: the app's own indigo primary is 4.46:1 with
            // white, so the glass button matches the existing app buttons
            // instead of inventing a different accent for the bubble.
            assertTrue(
                "ink on $accent must be readable, was " +
                    contrastRatioArgb(palette.onPrimary, palette.primary),
                contrastRatioArgb(palette.onPrimary, palette.primary) >= 4.4,
            )
        }
    }

    @Test
    fun `text stays readable over the composited glass panel`() {
        listOf(dark, light).forEach { palette ->
            val composited = compositeOver(palette.panelFill, palette.bg)
            assertTrue(
                "primary ink over the panel",
                contrastRatioArgb(palette.textPrimary, composited) >= 4.5,
            )
            assertTrue(
                "secondary ink over the panel",
                contrastRatioArgb(palette.textSecondary, composited) >= 3.0,
            )
        }
    }

    @Test
    fun `a custom light background keeps the ink dark and readable`() {
        val custom = style(
            bg = 0xFFF8FAFC.toInt(),
            card = 0xFFFFFFFF.toInt(),
            textPrimary = 0xFF0F172A.toInt(),
            textSecondary = 0xFF475569.toInt(),
            primary = 0xFF10B981.toInt(),
            isLight = true,
        )

        val composited = compositeOver(custom.panelFill, custom.bg)
        assertTrue(relativeLuminance(custom.textPrimary) < 0.2)
        assertTrue(contrastRatioArgb(custom.textPrimary, composited) >= 4.5)
        assertEquals(custom.textPrimary and 0x00FFFFFF, custom.markTint and 0x00FFFFFF)
    }

    @Test
    fun `the glass outline is a faint ink stroke`() {
        assertEquals(dark.textPrimary and 0x00FFFFFF, dark.glassOutline and 0x00FFFFFF)
        assertTrue(alphaFraction(dark.glassOutline) in 0.2f..0.4f)
    }

    @Test
    fun `the primary-filled button carries its own readable ink`() {
        assertEquals("dark ink on the amber accent", 0xFF1A1206.toInt(), style(primary = 0xFFF59E0B.toInt()).onPrimary)
        assertEquals("light ink on the indigo accent", 0xFFFFFFFF.toInt(), style(primary = 0xFF6366F1.toInt()).onPrimary)
    }

    @Test
    fun `the warning surfaces the semantic colours and never the glass`() {
        val warm = style(primary = 0xFFF59E0B.toInt(), isLight = true)
        assertEquals("the semantic warning stays untouched", 0xFFF59E0B.toInt(), warm.warning)
        assertEquals("the semantic error stays untouched", 0xFFEF4444.toInt(), warm.error)
        assertEquals("the badge dot is fully opaque", 1f, alphaFraction(warm.badge), 0f)
    }

    // --- helpers ----------------------------------------------------------

    private fun alphaFraction(argb: Int): Float =
        ((argb ushr 24) and 0xFF) / 255f

    private fun compositeOver(argb: Int, backgroundArgb: Int): Int {
        val alpha = alphaFraction(argb)
        fun channel(shift: Int): Int {
            val fg = (argb shr shift) and 0xFF
            val bg = (backgroundArgb shr shift) and 0xFF
            return (fg * alpha + bg * (1f - alpha)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
