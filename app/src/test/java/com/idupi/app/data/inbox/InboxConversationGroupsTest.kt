package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure conversation grouping for the Alertas list (inbox-management T4): one
 * card per app + chat, newest conversation first, messages newest first.
 */
class InboxConversationGroupsTest {

    private fun item(
        id: String,
        app: String = "whatsapp",
        conversation: String = "Equipo",
        receivedAt: String = "2026-11-24T10:00:00Z",
        appLabel: String = "",
        sender: String = "Ana",
    ) = InboxItem(
        id = id,
        app = app,
        appLabel = appLabel,
        sender = sender,
        conversation = conversation,
        text = "Hola",
        receivedAt = receivedAt,
        notificationKey = "key-$id"
    )

    @Test
    fun `items of the same app and conversation become one group`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("a", receivedAt = "2026-11-24T10:00:00Z"),
                item("b", receivedAt = "2026-11-24T11:00:00Z"),
                item("c", conversation = "Otro", receivedAt = "2026-11-24T12:00:00Z")
            )
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("c"), groups[0].items.map { it.id })
        assertEquals("whatsapp|Otro", groups[0].conversationKey)
        assertEquals(listOf("b", "a"), groups[1].items.map { it.id })
        assertEquals(2, groups[1].count)
    }

    @Test
    fun `different apps with the same conversation name stay apart`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("w", app = "whatsapp"),
                item("t", app = "telegram")
            )
        )

        assertEquals(2, groups.size)
        assertEquals(setOf("whatsapp|Equipo", "telegram|Equipo"), groups.map { it.conversationKey }.toSet())
    }

    @Test
    fun `groups order newest conversation first and items newest first`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("old", conversation = "Viejo", receivedAt = "2026-11-24T08:00:00Z"),
                item("new-1", conversation = "Nuevo", receivedAt = "2026-11-24T12:00:00Z"),
                item("new-2", conversation = "Nuevo", receivedAt = "2026-11-24T13:00:00Z")
            )
        )

        assertEquals(listOf("whatsapp|Nuevo", "whatsapp|Viejo"), groups.map { it.conversationKey })
        assertEquals(listOf("new-2", "new-1"), groups[0].items.map { it.id })
        assertEquals("new-2", groups[0].newestItem.id)
    }

    @Test
    fun `the newest non-blank package label names the group`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("a", appLabel = "WhatsApp", receivedAt = "2026-11-24T10:00:00Z"),
                item("b", appLabel = "", receivedAt = "2026-11-24T11:00:00Z")
            )
        )

        assertEquals("WhatsApp", groups.single().appLabel)
    }

    @Test
    fun `a malformed timestamp sorts last but is never dropped`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("bad", receivedAt = "not-a-date"),
                item("good", receivedAt = "2026-11-24T10:00:00Z")
            )
        )

        assertEquals(listOf("good", "bad"), groups.single().items.map { it.id })
        assertEquals(0L, InboxConversationGroups.newestMillis(groups.single().items.last()))
    }

    @Test
    fun `postedAt is the fallback when receivedAt is missing`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("a", receivedAt = "").copy(postedAt = "2026-11-24T10:00:00Z")
            )
        )

        assertEquals(1, groups.size)
        assertEquals(
            java.time.Instant.parse("2026-11-24T10:00:00Z").toEpochMilli(),
            groups.single().newestAtMillis
        )
    }
}
