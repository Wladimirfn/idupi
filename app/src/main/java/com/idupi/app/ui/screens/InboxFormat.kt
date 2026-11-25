package com.idupi.app.ui.screens

import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import java.time.Duration
import java.time.Instant

/**
 * Pure formatting helpers for the Alertas inbox (phone-inbox T5). UI copy is
 * Spanish because the owner and their contacts are; code stays English.
 */

/** Owner-facing name of the source app. */
fun inboxAppLabel(app: String): String = when (app) {
    InboxApp.WHATSAPP.id -> "WhatsApp"
    InboxApp.WHATSAPP_BUSINESS.id -> "WhatsApp Business"
    InboxApp.GMAIL.id -> "Gmail"
    InboxApp.OUTLOOK.id -> "Outlook"
    else -> app
}

/** Alta / Media / Baja, or null while the agent has not returned a level. */
fun importanceLabel(importance: String?): String? = when (importance) {
    InboxImportance.HIGH -> "Alta"
    InboxImportance.MEDIUM -> "Media"
    InboxImportance.LOW -> "Baja"
    else -> null
}

/** Label for a closed item; null for `open`. */
fun inboxItemStateLabel(state: String): String? = when (state) {
    InboxItemState.HANDLED -> "Atendida"
    InboxItemState.REPLIED -> "Respondida"
    InboxItemState.AUTO_REPLIED -> "Respondida automáticamente"
    InboxItemState.DISMISSED -> "Descartada"
    else -> null
}

/** Spanish label for the agent effort values the server accepts. */
fun effortLabel(effort: String): String = when (effort) {
    "low" -> "Baja"
    "medium" -> "Media"
    "high" -> "Alta"
    "max" -> "Máxima"
    else -> effort
}

/** Display name for the agent ids the server accepts. */
fun agentLabel(agent: String): String = when (agent) {
    "opencode" -> "OpenCode"
    "claude" -> "Claude"
    "codex" -> "Codex"
    "pi" -> "Pi"
    else -> agent
}

/**
 * "ahora", "hace 5 min", "hace 2 h" or "hace 3 d"; empty when the item has no
 * parseable timestamp.
 */
fun relativeTimeLabel(item: InboxItem, nowMillis: Long): String {
    val receivedAt = parseInstantMillis(item.receivedAt)
        ?: parseInstantMillis(item.postedAt)
        ?: return ""
    return relativeTimeLabel(receivedAt, nowMillis)
}

/**
 * Same wording for a raw epoch-millis timestamp, used by the capture status
 * block (inbox capture fix T1). Null means "never", which the caller renders.
 */
fun relativeTimeLabel(atMillis: Long?, nowMillis: Long): String {
    if (atMillis == null) return ""
    val minutes = Duration.ofMillis((nowMillis - atMillis).coerceAtLeast(0)).toMinutes()
    return when {
        minutes < 1 -> "ahora"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (24 * 60)} d"
    }
}

fun isInboxItemAnalyzing(item: InboxItem): Boolean =
    item.status == InboxItemStatus.ANALYZING

fun isInboxItemFailed(item: InboxItem): Boolean =
    item.status == InboxItemStatus.FAILED

private fun parseInstantMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
