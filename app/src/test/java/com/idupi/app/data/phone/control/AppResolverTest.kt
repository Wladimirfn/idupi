package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `open` app resolution (phone-agent T3), pure half. */
class AppResolverTest {

    private val apps = listOf(
        LaunchableApp("com.whatsapp", "WhatsApp"),
        LaunchableApp("com.android.chrome", "Chrome"),
        LaunchableApp("com.google.android.gm", "Gmail"),
        LaunchableApp("com.example.whatsappclone", "WhatsApp Clone"),
    )

    @Test
    fun `matches an exact package name`() {
        assertEquals(
            LaunchableApp("com.android.chrome", "Chrome"),
            AppResolver.resolve("com.android.chrome", apps),
        )
    }

    @Test
    fun `matches a visible label ignoring case`() {
        assertEquals(
            LaunchableApp("com.whatsapp", "WhatsApp"),
            AppResolver.resolve("WHATSAPP", apps),
        )
    }

    @Test
    fun `falls back to a unique partial label or package`() {
        assertEquals(
            LaunchableApp("com.google.android.gm", "Gmail"),
            AppResolver.resolve("gmai", apps),
        )
        assertEquals(
            LaunchableApp("com.android.chrome", "Chrome"),
            AppResolver.resolve("chrome", apps),
        )
    }

    @Test
    fun `an exact label wins over a partial one`() {
        assertEquals(
            LaunchableApp("com.whatsapp", "WhatsApp"),
            AppResolver.resolve("WhatsApp", apps),
        )
    }

    @Test
    fun `refuses ambiguous partial matches`() {
        // "whatsapp" equals the first label, so the exact-label rule wins.
        assertEquals(
            LaunchableApp("com.whatsapp", "WhatsApp"),
            AppResolver.resolve("whatsapp", apps),
        )
        // "whats" has no exact match and matches two apps: refuse.
        assertNull(AppResolver.resolve("whats", apps))
    }

    @Test
    fun `reports nothing for unknown or blank queries`() {
        assertNull(AppResolver.resolve("telegram", apps))
        assertNull(AppResolver.resolve("   ", apps))
        assertNull(AppResolver.resolve("", apps))
    }
}
