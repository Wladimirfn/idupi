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

/** Interactuar job states the server persists with the item (`INTERACTION_STATUSES`). */
object InboxInteractionStatus {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"

    val ALL = listOf(RUNNING, DONE, FAILED)
}

/**
 * alerts-flow A3: one owner instruction handed to the PC agent, with the
 * answer it produced. The server keeps it on the item (`interaction`), so the
 * card can show progress and the result across polls and app restarts.
 *
 * Every field has a default: an item written by an older server has no block
 * at all, and one written by a newer server may carry fields this build does
 * not know. Nothing here is sent back to the server.
 */
@Serializable
data class InboxInteraction(
    /** One of [InboxInteractionStatus]; empty when the server sent no status. */
    val status: String = "",
    /** The owner's own instruction, trimmed by the server. */
    val instruction: String = "",
    /** Linked project the agent worked in; null when the owner picked none. */
    val projectId: String? = null,
    val projectName: String? = null,
    /** The agent's answer; empty while running and after a failure. */
    val answer: String = "",
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null
)

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
    val updatedAt: String = "",
    /**
     * phone-calendar T3: event the PC extracted from the message, already
     * checked against the reported snapshot. Null when the message has no date.
     */
    val event: InboxEvent? = null,
    /** Existing events that overlap [event]; the card shows them as a warning. */
    val eventConflicts: List<InboxEventConflict> = emptyList(),
    /**
     * alerts-flow A2: the agent classified this as marketing, an offer, a
     * newsletter or a bulk notification. Promotions never render as an
     * individual pending card and never trigger reminders. Missing on items
     * from an older server, which decode as false.
     */
    val promotion: Boolean = false,
    /**
     * alerts-flow A3: the latest owner instruction and the agent's answer.
     * Null on items that were never handed to the agent (and on items from an
     * older server, which decode as null).
     */
    val interaction: InboxInteraction? = null,
)

/**
 * `POST /api/v1/inbox/{id}/interact` body (alerts-flow A3). The instruction is
 * the owner's trusted text; [projectId] names a linked project the read-only
 * agent works in, or is omitted when the owner picked "Ninguno".
 */
@Serializable
data class InboxInteractionRequest(
    val instruction: String,
    val projectId: String? = null
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
    val notificationKey: String,
    /**
     * inbox-management T4: silenced conversation. The server stores the item
     * already analyzed (low importance, category "Silenciado") and never runs
     * the per-message agent for it. False is omitted by the default serializer.
     */
    val digestOnly: Boolean = false
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
