package com.idupi.app.data.inbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bookkeeping the T4 policy relies on: which notification belongs to which
 * server item and conversation, and when it was last seen.
 */
class InboxSessionTest {

    @Before
    fun setUp() = InboxSession.clear()

    @After
    fun tearDown() = InboxSession.clear()

    private fun captured(
        key: String = "key-1",
        app: String = "whatsapp",
        sender: String = "Carlos",
        conversation: String = "Carlos",
        text: String = "hola"
    ) = CapturedMessage(
        app = app,
        packageName = "com.whatsapp",
        sender = sender,
        conversation = conversation,
        text = text,
        postedAt = "2026-11-24T12:30:00Z",
        notificationKey = key
    )

    @Test
    fun `remember stores the entry with its conversation key and no item yet`() {
        InboxSession.remember(captured(), atMillis = 1_000L)

        val entry = InboxSession.find("key-1")
        requireNotNull(entry)
        assertEquals("whatsapp|Carlos", entry.conversationKey)
        assertEquals("Carlos", entry.sender)
        assertEquals(1_000L, entry.capturedAtMillis)
        assertEquals(1_000L, entry.lastSeenMillis)
        assertNull(entry.itemId)
    }

    @Test
    fun `attachItemId links the server item after the upload succeeds`() {
        InboxSession.remember(captured(), atMillis = 1_000L)

        InboxSession.attachItemId("key-1", "itm-9")

        assertEquals("itm-9", InboxSession.find("key-1")?.itemId)
    }

    @Test
    fun `attaching to an unknown key is a no-op`() {
        InboxSession.attachItemId("missing", "itm-9")

        assertTrue(InboxSession.all().isEmpty())
    }

    @Test
    fun `touch moves lastSeen but keeps the original capture time`() {
        InboxSession.remember(captured(), atMillis = 1_000L)

        InboxSession.touch("key-1", atMillis = 5_000L)

        val entry = InboxSession.find("key-1")
        requireNotNull(entry)
        assertEquals(1_000L, entry.capturedAtMillis)
        assertEquals(5_000L, entry.lastSeenMillis)
    }

    @Test
    fun `a re-post keeps the first capture time and the known item id`() {
        InboxSession.remember(captured(), atMillis = 1_000L)
        InboxSession.attachItemId("key-1", "itm-9")

        InboxSession.remember(captured(text = "mensaje nuevo"), atMillis = 4_000L)

        val entry = InboxSession.find("key-1")
        requireNotNull(entry)
        assertEquals(1_000L, entry.capturedAtMillis)
        assertEquals(4_000L, entry.lastSeenMillis)
        assertEquals("itm-9", entry.itemId)
    }

    @Test
    fun `forConversation returns only that conversation, oldest first`() {
        InboxSession.remember(captured(key = "b", text = "segundo"), atMillis = 2_000L)
        InboxSession.remember(captured(key = "a", text = "primero"), atMillis = 1_000L)
        InboxSession.remember(
            captured(key = "other", conversation = "Ana", sender = "Ana"),
            atMillis = 3_000L
        )

        val entries = InboxSession.forConversation("whatsapp|Carlos")

        assertEquals(listOf("a", "b"), entries.map { it.notificationKey })
    }

    @Test
    fun `the same conversation in another app is a different key`() {
        InboxSession.remember(captured(key = "w", app = "whatsapp"), atMillis = 1_000L)
        InboxSession.remember(
            captured(key = "g", app = "gmail", conversation = "Carlos"),
            atMillis = 2_000L
        )

        assertEquals(1, InboxSession.forConversation("whatsapp|Carlos").size)
        assertEquals(1, InboxSession.forConversation("gmail|Carlos").size)
    }

    @Test
    fun `removeConversation clears only that conversation`() {
        InboxSession.remember(captured(key = "a"), atMillis = 1_000L)
        InboxSession.remember(captured(key = "b"), atMillis = 2_000L)
        InboxSession.remember(
            captured(key = "x", conversation = "Ana", sender = "Ana"),
            atMillis = 3_000L
        )

        val removed = InboxSession.removeConversation("whatsapp|Carlos")

        assertEquals(listOf("a", "b"), removed.map { it.notificationKey })
        assertNull(InboxSession.find("a"))
        assertEquals(1, InboxSession.all().size)
    }

    @Test
    fun `remove drops one notification`() {
        InboxSession.remember(captured(), atMillis = 1_000L)

        assertEquals("key-1", InboxSession.remove("key-1")?.notificationKey)
        assertTrue(InboxSession.all().isEmpty())
    }

    @Test
    fun `conversation key is app plus conversation`() {
        assertEquals(
            "whatsapp_business|Taller",
            inboxConversationKey("whatsapp_business", "Taller")
        )
    }
}
