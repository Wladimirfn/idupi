package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Active-notification selection (inbox capture fix T1): the same parser as live
 * posts, applied to the list the system already has in the shade (phone-inbox
 * T3 added the followed set).
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

    private fun select(
        snapshots: List<NotificationSnapshot>,
        followed: Set<String> = InboxApp.defaultFollowedIds
    ) = InboxActiveScan.select(snapshots, followed)

    @Test
    fun `selects WhatsApp Gmail and Outlook through the parser`() {
        val selected = select(
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
    fun `selects a followed Telegram notification`() {
        val selected = select(
            listOf(snapshot("org.telegram.messenger", "tg", title = "Taller", text = "hola"))
        )

        assertEquals(listOf("telegram"), selected.map { it.app })
    }

    @Test
    fun `skips own package ongoing summaries and unfollowed apps`() {
        val selected = select(
            listOf(
                snapshot(NotificationParser.OWN_PACKAGE, "own"),
                snapshot("com.whatsapp", "ongoing", ongoing = true),
                snapshot("com.google.android.gm", "summary", groupSummary = true),
                snapshot("com.spotify.music", "music"),
                snapshot("org.telegram.messenger", "unfollowed")
            ),
            followed = InboxApp.defaultFollowedIds - InboxApp.TELEGRAM.id
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a repeated notification key is captured once`() {
        val selected = select(
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
        val selected = select(
            listOf(
                snapshot("com.whatsapp", key = "", text = "uno"),
                snapshot("com.whatsapp", key = "", text = "dos")
            )
        )

        assertEquals(2, selected.size)
    }
}
