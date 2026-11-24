package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inventory rules for "Apps a seguir" (phone-inbox T3): dedupe, own-package
 * exclusion, preset-first ordering, label sorting and the search filter. Pure
 * over (packageName, label) pairs, so no PackageManager is involved.
 */
class InstalledAppsTest {

    @Test
    fun `presets come first and every group is sorted by label`() {
        val inventory = InstalledApps.inventory(
            listOf(
                "com.spotify.music" to "Spotify",
                "org.telegram.messenger" to "Telegram",
                "com.example.bank" to "Banco Sur",
                "com.whatsapp" to "WhatsApp",
                "com.google.android.gm" to "Gmail",
                "com.facebook.orca" to "Messenger"
            )
        )

        assertEquals(
            listOf("Gmail", "Messenger", "Telegram", "WhatsApp"),
            inventory.presets.map { it.displayLabel }
        )
        assertEquals(
            listOf("Banco Sur", "Spotify"),
            inventory.others.map { it.displayLabel }
        )
        assertEquals(6, inventory.all.size)
    }

    @Test
    fun `repeated launcher activities collapse to one row per package`() {
        val inventory = InstalledApps.inventory(
            listOf(
                "com.example.chat" to "Chat",
                "com.example.chat" to "Chat",
                "com.whatsapp" to "WhatsApp",
                "com.whatsapp" to "WhatsApp Business"
            )
        )

        assertEquals(listOf("com.whatsapp"), inventory.presets.map { it.packageName })
        assertEquals("WhatsApp", inventory.presets.single().displayLabel)
        assertEquals(listOf("com.example.chat"), inventory.others.map { it.packageName })
    }

    @Test
    fun `our own package is never offered even when the launcher lists it`() {
        val inventory = InstalledApps.inventory(
            listOf(
                NotificationParser.OWN_PACKAGE to "IDUPI",
                "com.example.chat" to "Chat"
            )
        )

        assertTrue(inventory.all.none { it.packageName == NotificationParser.OWN_PACKAGE })
    }

    @Test
    fun `follow keys are preset ids or package names`() {
        val inventory = InstalledApps.inventory(
            listOf(
                "com.whatsapp" to "WhatsApp",
                "com.example.chat" to "Chat"
            )
        )

        assertEquals(InboxApp.WHATSAPP.id, inventory.presets.single().appId)
        assertEquals("com.example.chat", inventory.others.single().appId)
        assertNull(inventory.others.single().preset)
    }

    @Test
    fun `a blank label falls back to the package name`() {
        val inventory = InstalledApps.inventory(listOf("com.example.chat" to "   "))

        assertEquals("com.example.chat", inventory.others.single().displayLabel)
    }

    @Test
    fun `following count only includes apps present on the phone`() {
        val inventory = InstalledApps.inventory(
            listOf(
                "com.whatsapp" to "WhatsApp",
                "com.example.chat" to "Chat"
            )
        )

        assertEquals(1, inventory.followingCount(setOf("whatsapp")))
        assertEquals(2, inventory.followingCount(setOf("whatsapp", "com.example.chat")))
        assertEquals(
            0,
            inventory.followingCount(setOf("gmail", "org.telegram.messenger"))
        )
    }

    @Test
    fun `collapsed followed subset keeps preset-first order and drops unknown ids`() {
        val inventory = InstalledApps.inventory(
            listOf(
                "com.example.chat" to "Chat Vecinal",
                "com.whatsapp" to "WhatsApp",
                "com.spotify.music" to "Spotify"
            )
        )

        assertEquals(
            listOf("WhatsApp", "Chat Vecinal"),
            inventory.followed(setOf("com.example.chat", "whatsapp", "ya.no.existe"))
                .map { it.displayLabel },
        )
        assertTrue(inventory.followed(emptySet()).isEmpty())
    }

    @Test
    fun `search matches labels and package names case-insensitively`() {
        val apps = InstalledApps.inventory(
            listOf(
                "com.example.chat" to "Chat Vecinal",
                "com.example.notes" to "Notas",
                "com.other.app" to "Banco Sur"
            )
        ).others

        assertEquals(listOf("Chat Vecinal"), InstalledApps.matching(apps, "chat").map { it.displayLabel })
        assertEquals(listOf("Chat Vecinal"), InstalledApps.matching(apps, "EXAMPLE.CHAT").map { it.displayLabel })
        assertEquals(listOf("Banco Sur"), InstalledApps.matching(apps, " banco ").map { it.displayLabel })
        assertEquals(apps, InstalledApps.matching(apps, "   "))
        assertTrue(InstalledApps.matching(apps, "inexistente").isEmpty())
    }
}
