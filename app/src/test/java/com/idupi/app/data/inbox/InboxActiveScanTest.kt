package com.idupi.app.data.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Active-notification selection (inbox capture fix T1): the same parser as live
 * posts, applied to the list the system already has in the shade.
 */
class InboxActiveScanTest {

    private fun snapshot(
        packageName: String = "com.whatsapp",
        key: String = "key-1",
        title: String = "Ana",
        text: String = "hola",
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = NotificationSnapshot(
        packageName = packageName,
        key = key,
        postTime = 1_700_000_000_000L,
        isOngoing = ongoing,
        isGroupSummary = groupSummary,
        title = title,
        text = text
    )

    @Test
    fun `selects WhatsApp Gmail and Outlook through the parser`() {
        val selected = InboxActiveScan.select(
            listOf(
                snapshot("com.whatsapp", "wa", title = "Ana", text = "hola"),
                snapshot("com.google.android.gm", "gm", title = "Banco", text = "Resumen"),
                snapshot("com.microsoft.office.outlook", "ol", title = "Jefe", text = "Reunión")
            )
        )

        assertEquals(listOf("whatsapp", "gmail", "outlook"), selected.map { it.app })
        assertEquals(listOf("wa", "gm", "ol"), selected.map { it.notificationKey })
    }

    @Test
    fun `skips own package ongoing summaries and unknown apps`() {
        val selected = InboxActiveScan.select(
            listOf(
                snapshot(NotificationParser.OWN_PACKAGE, "own"),
                snapshot("com.whatsapp", "ongoing", ongoing = true),
                snapshot("com.google.android.gm", "summary", groupSummary = true),
                snapshot("org.telegram.messenger", "tg")
            )
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a repeated notification key is captured once`() {
        val selected = InboxActiveScan.select(
            listOf(
                snapshot("com.whatsapp", "same", text = "primero"),
                snapshot("com.whatsapp", "same", text = "segundo")
            )
        )

        assertEquals(1, selected.size)
        assertEquals("primero", selected.single().text)
    }

    @Test
    fun `blank keys are not treated as duplicates`() {
        val selected = InboxActiveScan.select(
            listOf(
                snapshot("com.whatsapp", key = "", text = "uno"),
                snapshot("com.whatsapp", key = "", text = "dos")
            )
        )

        assertEquals(2, selected.size)
    }
}
