package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Respuesta lista para revisar" (alerts-flow A4): the local ping that tells
 * the owner a high-importance message already has a draft waiting. The rule is
 * pure so every combination is testable without Android; the notification text
 * never carries third-party alert text beyond the summary the owner sees on
 * the card anyway.
 */
class InboxReadyReplyTest {

    private fun item(
        id: String = "i1",
        status: String = InboxItemStatus.READY,
        importance: String? = InboxImportance.HIGH,
        suggestedReply: String = "Sí, ya lo reviso.",
        promotion: Boolean = false,
        state: String = InboxItemState.OPEN,
        summary: String = "El banco pide confirmar un movimiento.",
    ) = InboxItem(
        id = id,
        app = "gmail",
        text = "texto original",
        status = status,
        importance = importance,
        summary = summary,
        suggestedReply = suggestedReply,
        promotion = promotion,
        state = state
    )

    @Test
    fun `title is the fixed owner-facing line`() {
        assertEquals("Respuesta lista para revisar", InboxReadyReply.TITLE)
    }

    @Test
    fun `a ready high item with a draft and a live reply action qualifies`() {
        assertTrue(InboxReadyReply.qualifies(item(), hasReplyAction = true))
    }

    @Test
    fun `items without a usable reply action never qualify`() {
        assertFalse(InboxReadyReply.qualifies(item(), hasReplyAction = false))
    }

    @Test
    fun `medium and low importance never qualify`() {
        assertFalse(
            InboxReadyReply.qualifies(
                item(importance = InboxImportance.MEDIUM),
                hasReplyAction = true
            )
        )
        assertFalse(
            InboxReadyReply.qualifies(
                item(importance = InboxImportance.LOW),
                hasReplyAction = true
            )
        )
        assertFalse(InboxReadyReply.qualifies(item(importance = null), hasReplyAction = true))
    }

    @Test
    fun `promotions never qualify even when marked high`() {
        assertFalse(InboxReadyReply.qualifies(item(promotion = true), hasReplyAction = true))
    }

    @Test
    fun `only a ready item qualifies`() {
        assertFalse(
            InboxReadyReply.qualifies(
                item(status = InboxItemStatus.ANALYZING),
                hasReplyAction = true
            )
        )
        assertFalse(
            InboxReadyReply.qualifies(
                item(status = InboxItemStatus.FAILED),
                hasReplyAction = true
            )
        )
    }

    @Test
    fun `a closed item never qualifies`() {
        assertFalse(
            InboxReadyReply.qualifies(
                item(state = InboxItemState.HANDLED),
                hasReplyAction = true
            )
        )
    }

    @Test
    fun `an empty or blank draft never qualifies`() {
        assertFalse(InboxReadyReply.qualifies(item(suggestedReply = ""), hasReplyAction = true))
        assertFalse(InboxReadyReply.qualifies(item(suggestedReply = "   "), hasReplyAction = true))
    }

    @Test
    fun `the text is the summary clipped to the shade with an ellipsis`() {
        val text = InboxReadyReply.notificationText(item())
        assertEquals("El banco pide confirmar un movimiento.", text)

        val long = InboxReadyReply.notificationText(item(summary = "s".repeat(400)))
        assertEquals(InboxReadyReply.MAX_TEXT_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `the text falls back to the item text and then to the fixed line`() {
        assertEquals(
            "texto original",
            InboxReadyReply.notificationText(item(summary = ""))
        )
        assertEquals(
            InboxReadyReply.FALLBACK_TEXT,
            InboxReadyReply.notificationText(item(summary = "  ", suggestedReply = "x").copy(text = " "))
        )
    }
}
