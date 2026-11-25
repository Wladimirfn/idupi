package com.idupi.app.data.inbox

import java.util.concurrent.ConcurrentHashMap

/** Stable key for "this conversation in this app", shared by the reply registry and the session. */
fun inboxConversationKey(app: String, conversation: String): String = "$app|$conversation"

/**
 * In-memory record of what this process captured (phone-inbox T3), kept for
 * the reminder/auto-reply policy of T4 and for T5's list actions.
 *
 * It is deliberately NOT persisted: server items are the durable truth, and a
 * process restart must not resurrect a "last seen" time that would silence a
 * reminder. Losing it only means the policy falls back to the server state.
 */
object InboxSession {

    data class Entry(
        val notificationKey: String,
        val app: String,
        val conversation: String,
        val conversationKey: String,
        val sender: String,
        val capturedAtMillis: Long,
        val lastSeenMillis: Long,
        /** Server item id, null until the upload succeeds. */
        val itemId: String?
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Records (or refreshes) a captured notification; a re-post keeps the first capture time. */
    fun remember(captured: CapturedMessage, atMillis: Long = System.currentTimeMillis()) {
        entries.compute(captured.notificationKey) { _, existing ->
            Entry(
                notificationKey = captured.notificationKey,
                app = captured.app,
                conversation = captured.conversation,
                conversationKey = inboxConversationKey(captured.app, captured.conversation),
                sender = captured.sender,
                capturedAtMillis = existing?.capturedAtMillis ?: atMillis,
                lastSeenMillis = atMillis,
                itemId = existing?.itemId
            )
        }
    }

    /** Called after `POST /inbox/messages` returns the stored item. */
    fun attachItemId(notificationKey: String, itemId: String) {
        entries.computeIfPresent(notificationKey) { _, entry -> entry.copy(itemId = itemId) }
    }

    fun touch(notificationKey: String, atMillis: Long = System.currentTimeMillis()) {
        entries.computeIfPresent(notificationKey) { _, entry -> entry.copy(lastSeenMillis = atMillis) }
    }

    fun find(notificationKey: String): Entry? = entries[notificationKey]

    /** Oldest first, so a policy can walk a conversation in arrival order. */
    fun forConversation(conversationKey: String): List<Entry> =
        entries.values
            .filter { it.conversationKey == conversationKey }
            .sortedBy { it.capturedAtMillis }

    fun remove(notificationKey: String): Entry? = entries.remove(notificationKey)

    fun removeConversation(conversationKey: String): List<Entry> {
        val removed = entries.values.filter { it.conversationKey == conversationKey }
        removed.forEach { entries.remove(it.notificationKey, it) }
        return removed
    }

    fun all(): List<Entry> = entries.values.sortedBy { it.capturedAtMillis }

    /** Test isolation only: production clears entries one conversation at a time. */
    fun clear() = entries.clear()
}
