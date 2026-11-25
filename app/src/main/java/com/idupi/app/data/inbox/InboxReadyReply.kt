package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus

/**
 * Rule and copy for the "Respuesta lista para revisar" ping (alerts-flow A4).
 *
 * When a high-importance message finishes analysis with a draft, IDUPI tells
 * the owner once, so the reply is waiting instead of being discovered later.
 * Nothing is ever sent: the notification only opens Alertas.
 *
 * Kept pure (no Android) so every combination is testable and the ViewModel
 * stays a thin caller. The body is the summary the owner already sees on the
 * card, clipped to the shade.
 */
object InboxReadyReply {

    /** Fixed owner-facing title; the tap opens Alertas. */
    const val TITLE = "Respuesta lista para revisar"

    /** Body used when the item has neither a summary nor text to show. */
    const val FALLBACK_TEXT = "Hay un mensaje importante con una respuesta lista"

    /** Longest body kept; a longer summary is cut and marked with an ellipsis. */
    const val MAX_TEXT_CHARS = 120

    /**
     * An item qualifies when it is still open, is not a promotion, came back
     * high, finished `ready`, carries a non-blank draft and its reply action is
     * still live. [hasReplyAction] is the card's own check for
     * "Enviar sugerencia", so the ping never points at a button the owner
     * cannot press.
     */
    fun qualifies(item: InboxItem, hasReplyAction: Boolean): Boolean =
        item.state == InboxItemState.OPEN &&
            !item.promotion &&
            item.importance == InboxImportance.HIGH &&
            item.status == InboxItemStatus.READY &&
            item.suggestedReply.isNotBlank() &&
            hasReplyAction

    /**
     * Body for the shade: the summary, then the original text, trimmed and
     * capped at [MAX_TEXT_CHARS]; [FALLBACK_TEXT] when both are blank.
     */
    fun notificationText(item: InboxItem): String {
        val body = item.summary.trim().ifEmpty { item.text.trim() }
        if (body.isEmpty()) return FALLBACK_TEXT
        if (body.length <= MAX_TEXT_CHARS) return body
        // The ellipsis counts toward the cap so the shade never overflows.
        return body.take(MAX_TEXT_CHARS - 1).trimEnd() + "…"
    }
}
