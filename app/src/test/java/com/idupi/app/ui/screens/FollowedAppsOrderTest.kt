package com.idupi.app.ui.screens

import com.idupi.app.data.inbox.InstalledApps
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Apps a seguir" screen ordering (settings redesign T4): followed apps first,
 * then the rest, with the search field filtering both label and package.
 */
class FollowedAppsOrderTest {

    private val inventory = InstalledApps.inventory(
        listOf(
            "com.whatsapp" to "WhatsApp",
            "com.example.chat" to "Chat Vecinal",
            "com.google.android.gm" to "Gmail",
            "com.example.board" to "Tablero",
        )
    )

    @Test
    fun `followed apps come first and keep the inventory order inside each group`() {
        val rows = followedFirstApps(
            inventory = inventory,
            followedApps = setOf("com.example.chat", "gmail"),
            query = "",
        )

        assertEquals(
            listOf("Gmail", "Chat Vecinal", "WhatsApp", "Tablero"),
            rows.map { it.displayLabel },
        )
    }

    @Test
    fun `an empty followed set keeps the inventory order`() {
        val rows = followedFirstApps(inventory, followedApps = emptySet(), query = "")

        assertEquals(
            listOf("Gmail", "WhatsApp", "Chat Vecinal", "Tablero"),
            rows.map { it.displayLabel },
        )
    }

    @Test
    fun `search filters by label before ordering`() {
        val rows = followedFirstApps(inventory, setOf("gmail"), query = "chat")

        assertEquals(listOf("Chat Vecinal"), rows.map { it.displayLabel })
    }

    @Test
    fun `search filters by package name too`() {
        val rows = followedFirstApps(inventory, followedApps = emptySet(), query = "com.example")

        assertEquals(listOf("Chat Vecinal", "Tablero"), rows.map { it.displayLabel })
    }

    @Test
    fun `no match yields an empty list`() {
        val rows = followedFirstApps(inventory, followedApps = emptySet(), query = "zzz")

        assertEquals(emptyList<String>(), rows.map { it.displayLabel })
    }
}
