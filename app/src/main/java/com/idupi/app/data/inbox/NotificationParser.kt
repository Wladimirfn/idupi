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
 * plain values. Keeping the input this small is what lets the capture rules be
 * unit-tested on the JVM without Robolectric: the listener is the only place
 * that touches the Android notification objects.
 */
data class NotificationSnapshot(
    val packageName: String,
    val key: String,
    val postTime: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    /** `Notification.getCategory()`, e.g. "msg", "call", "progress". */
    val category: String? = null,
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
 * [app] is the wire app id: a preset [InboxApp.id] or the package name of any
 * other followed app (see [InboxApp.appIdFor]); [postedAt] is ISO-8601 because
 * that is what `POST /api/v1/inbox/messages` accepts. [appLabel] is filled by
 * the listener, which owns the PackageManager lookup, so the parser stays pure.
 */
data class CapturedMessage(
    val app: String,
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val postedAt: String,
    val notificationKey: String,
    val appLabel: String = "",
    /**
     * inbox-management T4: the conversation is silenced inside IDUPI, so the
     * upload asks the server to skip the per-message agent and keep the item
     * for the conversation digest.
     */
    val digestOnly: Boolean = false
)

/**
 * Pure capture rules (phone-inbox T2/T3): which notifications become inbox
 * items and what each field means for messaging vs mail.
 *
 * Skipped on purpose:
 * - any package whose follow key is not in the owner's followed set, including
 *   our own package;
 * - ongoing notifications (calls, music, navigation): they are not messages;
 * - group summaries, which only repeat messages already captured;
 * - call, progress, transport, service and system categories, which are never
 *   a conversation the owner can answer;
 * - blank text;
 * - messaging aggregation rows ("3 mensajes nuevos" / "3 new messages"),
 *   which are summaries of a summary and carry no sender.
 */
object NotificationParser {

    /** Our own package is never followed; kept explicit so it can never leak in. */
    const val OWN_PACKAGE = "com.idupi.app"

    /**
     * Categories that never describe a message. These mirror the string values
     * of `android.app.Notification.CATEGORY_*` so this file stays Android-free.
     */
    private val SKIPPED_CATEGORIES = setOf("call", "progress", "transport", "service", "system")

    /** A messaging app's Android 13+ aggregation row, in the phone's language. */
    private val MESSAGING_SUMMARY = Regex(
        "^\\s*\\d+\\s+(mensajes nuevos|new messages)(\\s+de\\s+.*)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param followedApps preset ids or package names the owner follows.
     */
    fun parse(
        snapshot: NotificationSnapshot,
        followedApps: Set<String>,
        ownPackageName: String = OWN_PACKAGE
    ): CapturedMessage? {
        if (snapshot.packageName == ownPackageName) return null
        if (snapshot.isOngoing) return null
        if (snapshot.isGroupSummary) return null
        if (snapshot.category != null && snapshot.category in SKIPPED_CATEGORIES) return null

        val appId = InboxApp.appIdFor(snapshot.packageName)
        // A preset is followed by its stable id; any other app by its package.
        if (appId !in followedApps && snapshot.packageName !in followedApps) return null

        val postedAt = Instant.ofEpochMilli(snapshot.postTime).toString()
        val preset = InboxApp.fromPackageName(snapshot.packageName)
        return if (preset?.isMail == true) {
            mail(snapshot, appId, postedAt)
        } else {
            messaging(snapshot, appId, postedAt)
        }
    }

    /**
     * Messaging (WhatsApp, Messenger, Telegram, and any other followed app):
     * prefer the last MessagingStyle message, because a conversation
     * notification repeats the recent ones and only the newest matters.
     * Without a MessagingStyle the notification text is the message and the
     * title is the contact (1:1) or the group (group chat).
     */
    private fun messaging(
        snapshot: NotificationSnapshot,
        appId: String,
        postedAt: String
    ): CapturedMessage? {
        val lastLine = snapshot.messagingLines.lastOrNull { !it.text.isNullOrBlank() }
        val text = (
            lastLine?.text
                ?: snapshot.bigText
                ?: snapshot.text
            )?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (MESSAGING_SUMMARY.matches(text)) return null

        val groupName = snapshot.conversationTitle?.trim().orEmpty()
        val sender = lastLine?.sender?.trim().takeUnless { it.isNullOrEmpty() }
            ?: snapshot.title?.trim().orEmpty()
        val conversation = groupName.ifEmpty { sender }

        return CapturedMessage(
            app = appId,
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
        appId: String,
        postedAt: String
    ): CapturedMessage? {
        val sender = snapshot.title?.trim().orEmpty()
        val text = (snapshot.bigText ?: snapshot.text)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val conversation = snapshot.conversationTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sender

        return CapturedMessage(
            app = appId,
            packageName = snapshot.packageName,
            sender = sender,
            conversation = conversation,
            text = text,
            postedAt = postedAt,
            notificationKey = snapshot.key
        )
    }
}

