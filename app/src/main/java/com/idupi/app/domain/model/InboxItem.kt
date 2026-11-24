package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A known messaging or mail app (phone-inbox T2/T3).
 *
 * [id] is the stable wire value the server stores and the reminder policy
 * matches; the four original ids never change, so items captured by an older
 * build keep decoding. [packageName] is what the notification listener maps to
 * [id]. Any other app the owner follows uses its own package name as the app
 * id (see [appIdFor]).
 */
enum class InboxApp(val id: String, val packageName: String, val label: String) {
    WHATSAPP("whatsapp", "com.whatsapp", "WhatsApp"),
    WHATSAPP_BUSINESS("whatsapp_business", "com.whatsapp.w4b", "WhatsApp Business"),
    GMAIL("gmail", "com.google.android.gm", "Gmail"),
    OUTLOOK("outlook", "com.microsoft.office.outlook", "Outlook"),
    MESSENGER("messenger", "com.facebook.orca", "Messenger"),
    TELEGRAM("telegram", "org.telegram.messenger", "Telegram"),
    INSTAGRAM("instagram", "com.instagram.android", "Instagram"),
    SIGNAL("signal", "org.thoughtcrime.securesms", "Signal");

    /** Mail notifications put the sender in the title and the body in bigText. */
    val isMail: Boolean get() = this == GMAIL || this == OUTLOOK

    companion object {
        /**
         * The apps IDUPI follows on a fresh install: the four original presets
         * plus Messenger and Telegram. Only installed apps get a switch in
         * Ajustes; an id without the package stays followed but captures
         * nothing.
         */
        val defaultFollowedIds: Set<String> = setOf(
            WHATSAPP.id,
            WHATSAPP_BUSINESS.id,
            GMAIL.id,
            OUTLOOK.id,
            MESSENGER.id,
            TELEGRAM.id
        )

        fun fromPackageName(packageName: String?): InboxApp? =
            entries.firstOrNull { it.packageName == packageName }

        fun fromId(id: String?): InboxApp? = entries.firstOrNull { it.id == id }

        /** Follow key for a package: its preset id, or the package itself. */
        fun appIdFor(packageName: String): String =
            fromPackageName(packageName)?.id ?: packageName

        /** Preset label, else the id or package name as-is. */
        fun labelFor(appId: String): String = fromId(appId)?.label ?: appId

        /**
         * Owner-facing name: the captured PackageManager label first, then the
         * preset label, then the app id or package name.
         */
        fun displayLabel(appLabel: String?, appId: String): String =
            appLabel?.trim().takeUnless { it.isNullOrEmpty() } ?: labelFor(appId)
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
    /** Preset id or package name of the source app. */
    val app: String = "",
    /** PackageManager label captured by the phone; empty on old items. */
    val appLabel: String = "",
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
    /** Preset id or package name the owner follows. */
    val app: String,
    /** Optional PackageManager label for display; empty omits it on the wire. */
    val appLabel: String = "",
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
