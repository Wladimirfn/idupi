package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import java.time.Instant

/** One MessagingStyle entry, reduced to the two fields capture cares about. */
data class MessagingLine(
    val sender: String?,
    val text: String?
)

/**
 * Everything [NotificationParser] needs from a `StatusBarNotification`, as
 * plain values. Keeping the input this small is what lets the WhatsApp, Gmail
 * and Outlook rules be unit-tested on the JVM without Robolectric: the
 * listener is the only place that touches the Android notification objects.
 */
data class NotificationSnapshot(
    val packageName: String,
    val key: String,
    val postTime: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    /** Group name from MessagingStyle (or EXTRA_CONVERSATION_TITLE). */
    val conversationTitle: String? = null,
    val messagingLines: List<MessagingLine> = emptyList()
)

/**
 * A notification that should become an inbox item.
 *
 * [app] is the server's wire id ([InboxApp.id]); [postedAt] is ISO-8601
 * because that is what `POST /api/v1/inbox/messages` accepts.
 */
data class CapturedMessage(
    val app: String,
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val postedAt: String,
    val notificationKey: String
)

/**
 * Pure capture rules (phone-inbox T3): which notifications become inbox items
 * and what each field means for WhatsApp vs mail.
 *
 * Skipped on purpose:
 * - any package that is not one of the four supported apps (including our own);
 * - ongoing notifications (calls, music, navigation): they are not messages;
 * - group summaries, which only repeat messages already captured;
 * - blank text;
 * - WhatsApp's aggregation rows ("3 mensajes nuevos" / "3 new messages"),
 *   which are summaries of a summary and carry no sender.
 */
object NotificationParser {

    /** Our own package is not an `InboxApp`; kept explicit so it can never leak in. */
    const val OWN_PACKAGE = "com.idupi.app"

    /** WhatsApp's Android 13+ aggregation row, in the owner's phone language. */
    private val WHATSAPP_SUMMARY = Regex(
        "^\\s*\\d+\\s+(mensajes nuevos|new messages)(\\s+de\\s+.*)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun parse(
        snapshot: NotificationSnapshot,
        ownPackageName: String = OWN_PACKAGE
    ): CapturedMessage? {
        if (snapshot.packageName == ownPackageName) return null
        val app = InboxApp.fromPackageName(snapshot.packageName) ?: return null
        if (snapshot.isOngoing) return null
        if (snapshot.isGroupSummary) return null

        val postedAt = Instant.ofEpochMilli(snapshot.postTime).toString()
        return when (app) {
            InboxApp.WHATSAPP, InboxApp.WHATSAPP_BUSINESS -> messaging(snapshot, app, postedAt)
            InboxApp.GMAIL, InboxApp.OUTLOOK -> mail(snapshot, app, postedAt)
        }
    }

    /**
     * WhatsApp/Business: prefer the last MessagingStyle message, because a
     * conversation notification repeats the recent ones and only the newest
     * matters. Without a MessagingStyle the notification text is the message
     * and the title is the contact (1:1) or the group (group chat).
     */
    private fun messaging(
        snapshot: NotificationSnapshot,
        app: InboxApp,
        postedAt: String
    ): CapturedMessage? {
        val lastLine = snapshot.messagingLines.lastOrNull { !it.text.isNullOrBlank() }
        val text = (
            lastLine?.text
                ?: snapshot.bigText
                ?: snapshot.text
            )?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (WHATSAPP_SUMMARY.matches(text)) return null

        val groupName = snapshot.conversationTitle?.trim().orEmpty()
        val sender = lastLine?.sender?.trim().takeUnless { it.isNullOrEmpty() }
            ?: snapshot.title?.trim().orEmpty()
        val conversation = groupName.ifEmpty { sender }

        return CapturedMessage(
            app = app.id,
            packageName = snapshot.packageName,
            sender = sender,
            conversation = conversation,
            text = text,
            postedAt = postedAt,
            notificationKey = snapshot.key
        )
    }

    /**
     * Mail: the title is the sender, `bigText` is subject + snippet and is the
     * text the agent must analyze. Gmail sets a group summary for threaded
     * mail, so those are already filtered above.
     */
    private fun mail(
        snapshot: NotificationSnapshot,
        app: InboxApp,
        postedAt: String
    ): CapturedMessage? {
        val sender = snapshot.title?.trim().orEmpty()
        val text = (snapshot.bigText ?: snapshot.text)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val conversation = snapshot.conversationTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sender

        return CapturedMessage(
            app = app.id,
            packageName = snapshot.packageName,
            sender = sender,
            conversation = conversation,
            text = text,
            postedAt = postedAt,
            notificationKey = snapshot.key
        )
    }
}
