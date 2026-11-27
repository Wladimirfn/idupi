package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure selection of the storage line the bubble reads back (phone-dashboard
 * T4): "léemelo" on a cache or uninstall prompt reads the size line ("Caché
 * 1,2 GB") when the Settings screen shows one, otherwise the title.
 */
class ApprovalScreenTextTest {

    @Test
    fun `the cache size line is recognized`() {
        assertEquals(
            "Caché 1,2 GB",
            ApprovalScreenText.sizeLine(listOf("WhatsApp", "Caché 1,2 GB", "Datos 300 MB")),
        )
        assertEquals(
            "Caché 340,5 MB",
            ApprovalScreenText.sizeLine(listOf("Caché 340,5 MB")),
        )
        assertEquals(
            "Almacenamiento usado 12,4 GB",
            ApprovalScreenText.sizeLine(listOf("Almacenamiento usado 12,4 GB")),
        )
        assertEquals(
            "Storage 1.5 GB",
            ApprovalScreenText.sizeLine(listOf("Storage 1.5 GB")),
        )
    }

    @Test
    fun `text without a storage size is not a size line`() {
        listOf(
            "WhatsApp",
            "Borrar caché",
            "1,2 GB",
            "Datos",
            "Caché",
            "",
            "   ",
        ).forEach { text ->
            assertNull("'$text' is not a size line", ApprovalScreenText.sizeLine(listOf(text)))
        }
        assertNull(ApprovalScreenText.sizeLine(emptyList()))
    }

    @Test
    fun `the first matching line wins`() {
        assertEquals(
            "Caché 1,2 GB",
            ApprovalScreenText.sizeLine(
                listOf("Caché 1,2 GB", "Caché 900 MB"),
            ),
        )
    }
}
