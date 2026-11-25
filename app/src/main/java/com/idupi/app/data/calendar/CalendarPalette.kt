package com.idupi.app.data.calendar

/**
 * Pure `#RRGGBB` parsing for the provider's calendar colors (phone-calendar
 * T5). The UI cannot call `android.graphics.Color.parseColor` from a JVM test,
 * so the conversion lives here and malformed values fall back to the theme's
 * accent.
 */
object CalendarPalette {

    /**
     * Opaque ARGB int for a provider color, or null when [hex] is not a
     * `#RRGGBB` value.
     */
    fun argb(hex: String?): Int? {
        val raw = hex?.trim()?.removePrefix("#") ?: return null
        if (raw.length != HEX_CHARS) return null
        val rgb = raw.toLongOrNull(16) ?: return null
        return (rgb or OPAQUE_ALPHA).toInt()
    }

    private const val HEX_CHARS = 6
    private const val OPAQUE_ALPHA = 0xFF000000L
}
