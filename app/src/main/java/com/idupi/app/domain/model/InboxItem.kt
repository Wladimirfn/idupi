package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * The apps the phone captures for Alertas (phone-inbox T2/T3).
 *
 * [id] is the wire value the server accepts (`INBOX_APPS` in
 * idupi-server/lib/inbox-store.mjs). [packageName] is what the notification
 * listener matches; a package that is not listed here is never captured.
 */
enum class InboxApp(val id: String, val packageName: String) {
    WHATSAPP("whatsapp", "com.whatsapp"),
    WHATSAPP_BUSINESS("whatsapp_business", "com.whatsapp.w4b"),
    GMAIL("gmail", "com.google.android.gm"),
    OUTLOOK("outlook", "com.microsoft.office.outlook");

    companion object {
        /** Every capturable app id; the default for `InboxSettings.enabledApps`. */
        val allIds: Set<String> = entries.map { it.id }.toSet()

        fun fromPackageName(packageName: String?): InboxApp? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

/** Item lifecycle on the server (`INBOX_STATES`). */
object InboxItemState {
    const val OPEN = "open"
    const val HANDLED = "handled"
    const val REPLIED = "replied"
    const val AUTO_REPLIED = "auto_replied"
    const val DISMISSED = "dismissed"

    val ALL = listOf(OPEN, HANDLED, REPLIED, AUTO_REPLIED, DISMISSED)
}

/** Agent analysis status (`INBOX_STATUSES`). */
object InboxItemStatus {
    const val ANALYZING = "analyzing"
    const val READY = "ready"
    const val FAILED = "failed"
}

/** Importance levels the agent may return (`INBOX_IMPORTANCES`). */
object InboxImportance {
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
}

/**
 * One captured notification plus the agent's analysis of it.
 *
 * Every field has a default and the analysis fields stay nullable/blank until
 * the server's runner finishes, so a document from an older or newer server
 * decodes instead of failing: the phones this app runs on are updated
 * independently from the PC.
 */
@Serializable
data class InboxItem(
    val id: String = "",
    val app: String = "",
    val packageName: String = "",
    val sender: String = "",
    val conversation: String = "",
    val text: String = "",
    /** ISO-8601 as posted by the source notification; null when unknown. */
    val postedAt: String? = null,
    val receivedAt: String = "",
    val notificationKey: String = "",
    val status: String = InboxItemStatus.ANALYZING,
    val importance: String? = null,
    val category: String = "",
    val summary: String = "",
    val suggestedReply: String = "",
    val error: String? = null,
    val state: String = InboxItemState.OPEN,
    val updatedAt: String = ""
)

/**
 * `POST /api/v1/inbox/messages` body. The server requires `app`, `text` and
 * `notificationKey`; the rest may be empty but is always sent so the item is
 * usable in the inbox without a second lookup.
 */
@Serializable
data class InboxMessageRequest(
    val app: String,
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    /** ISO-8601 string (decided in the feature's Decisions section). */
    val postedAt: String,
    val notificationKey: String
)

/**
 * `GET|PUT /api/v1/inbox/config`: the agent/model/effort the PC uses for
 * analysis. `agents` and `efforts` are only populated by the server.
 */
@Serializable
data class InboxConfig(
    val agent: String = "",
    val model: String = "",
    val effort: String = "",
    val agents: List<String> = emptyList(),
    val efforts: List<String> = emptyList()
)
