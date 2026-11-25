package com.idupi.app.ui.screens

import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Spanish labels and relative times used by the Alertas cards (phone-inbox T5). */
class InboxFormatTest {

    private val now = 1_800_000_000_000L

    private fun item(
        receivedAt: String = "",
        postedAt: String = "",
        status: String = InboxItemStatus.READY
    ) = InboxItem(
        id = "i1",
        app = "whatsapp",
        text = "Hola",
        receivedAt = receivedAt,
        postedAt = postedAt,
        status = status,
        state = InboxItemState.OPEN
    )

    private fun minutesAgo(minutes: Long): String =
        Instant.ofEpochMilli(now - minutes * 60_000L).toString()

    @Test
    fun `app labels cover the four supported apps`() {
        assertEquals("WhatsApp", inboxAppLabel("whatsapp"))
        assertEquals("WhatsApp Business", inboxAppLabel("whatsapp_business"))
        assertEquals("Gmail", inboxAppLabel("gmail"))
        assertEquals("Outlook", inboxAppLabel("outlook"))
        assertEquals("telegram", inboxAppLabel("telegram"))
    }

    @Test
    fun `importance labels cover the three levels and nothing else`() {
        assertEquals("Alta", importanceLabel("high"))
        assertEquals("Media", importanceLabel("medium"))
        assertEquals("Baja", importanceLabel("low"))
        assertNull(importanceLabel(null))
        assertNull(importanceLabel("critical"))
    }

    @Test
    fun `closed item labels`() {
        assertEquals("Atendida", inboxItemStateLabel(InboxItemState.HANDLED))
        assertEquals("Respondida", inboxItemStateLabel(InboxItemState.REPLIED))
        assertEquals("Respondida automáticamente", inboxItemStateLabel(InboxItemState.AUTO_REPLIED))
        assertEquals("Descartada", inboxItemStateLabel(InboxItemState.DISMISSED))
        assertNull(inboxItemStateLabel(InboxItemState.OPEN))
    }

    @Test
    fun `relative time formats minutes hours and days`() {
        assertEquals("ahora", relativeTimeLabel(item(receivedAt = minutesAgo(0)), now))
        assertEquals("hace 5 min", relativeTimeLabel(item(receivedAt = minutesAgo(5)), now))
        assertEquals("hace 2 h", relativeTimeLabel(item(receivedAt = minutesAgo(120)), now))
        assertEquals("hace 3 d", relativeTimeLabel(item(receivedAt = minutesAgo(3 * 24 * 60)), now))
    }

    @Test
    fun `relative time falls back to postedAt and tolerates garbage`() {
        assertEquals(
            "hace 10 min",
            relativeTimeLabel(item(receivedAt = "", postedAt = minutesAgo(10)), now)
        )
        assertEquals("", relativeTimeLabel(item(receivedAt = "ayer", postedAt = ""), now))
    }

    @Test
    fun `effort labels`() {
        assertEquals("Baja", effortLabel("low"))
        assertEquals("Media", effortLabel("medium"))
        assertEquals("Alta", effortLabel("high"))
        assertEquals("Máxima", effortLabel("max"))
        assertEquals("xhigh", effortLabel("xhigh"))
    }

    @Test
    fun `agent labels`() {
        assertEquals("OpenCode", agentLabel("opencode"))
        assertEquals("Claude", agentLabel("claude"))
        assertEquals("Codex", agentLabel("codex"))
        assertEquals("Pi", agentLabel("pi"))
        assertEquals("gemini", agentLabel("gemini"))
    }

    @Test
    fun `analysis state helpers`() {
        assertEquals(true, isInboxItemAnalyzing(item(status = InboxItemStatus.ANALYZING)))
        assertEquals(false, isInboxItemAnalyzing(item(status = InboxItemStatus.READY)))
        assertEquals(true, isInboxItemFailed(item(status = InboxItemStatus.FAILED)))
        assertEquals(false, isInboxItemFailed(item(status = InboxItemStatus.READY)))
    }
}
