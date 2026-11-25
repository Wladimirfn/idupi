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
        promotion: Boolean = false,
    ) = InboxItem(
        id = id,
        app = app,
        appLabel = appLabel,
        sender = sender,
        conversation = conversation,
        text = "Hola",
        receivedAt = receivedAt,
        notificationKey = "key-$id",
        promotion = promotion
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

    @Test
    fun `open promotions from every app collapse into one group at the end`() {
        val groups = InboxConversationGroups.group(
            listOf(
                item("promo-gmail", app = "gmail", conversation = "Ofertas", receivedAt = "2026-11-24T13:00:00Z", promotion = true),
                item("normal", conversation = "Equipo", receivedAt = "2026-11-24T09:00:00Z"),
                item("promo-telegram", app = "telegram", conversation = "Canal", receivedAt = "2026-11-24T12:00:00Z", promotion = true)
            )
        )

        assertEquals(2, groups.size)
        assertEquals("whatsapp|Equipo", groups.first().conversationKey)
        val promotions = groups.last()
        assertEquals(InboxConversationGroups.PROMOTIONS_KEY, promotions.conversationKey)
        assertEquals(listOf("promo-gmail", "promo-telegram"), promotions.items.map { it.id })
        assertEquals(2, promotions.count)
    }

    @Test
    fun `a lone promotion item is not a single-item conversation card`() {
        val groups = InboxConversationGroups.group(
            listOf(item("promo", app = "instagram", conversation = "Anuncios", promotion = true))
        )

        assertEquals(1, groups.size)
        assertEquals(InboxConversationGroups.PROMOTIONS_KEY, groups.single().conversationKey)
    }

    @Test
    fun `promotionItems lists only promotions, newest first`() {
        val items = listOf(
            item("old", receivedAt = "2026-11-24T08:00:00Z", promotion = true),
            item("normal", receivedAt = "2026-11-24T09:00:00Z"),
            item("new", app = "gmail", conversation = "Ofertas", receivedAt = "2026-11-24T11:00:00Z", promotion = true)
        )

        assertEquals(listOf("new", "old"), InboxConversationGroups.promotionItems(items).map { it.id })
    }
}
