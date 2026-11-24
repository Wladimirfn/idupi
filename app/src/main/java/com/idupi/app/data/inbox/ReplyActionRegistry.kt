package com.idupi.app.data.inbox

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * One notification's reply action, as captured when it was posted.
 *
 * The interface exists so the registry's decision logic can be tested on the
 * JVM: [RemoteInputReplyAction] is the only Android-bound implementation.
 */
fun interface ReplyAction {
    /** @return true when the source app accepted the reply. */
    fun send(context: Context, text: String): Boolean
}

/**
 * Fills the notification's own RemoteInput and fires its PendingIntent -- this
 * is how auto-reply apps answer WhatsApp without an account: the message is
 * composed locally and handed to the source app, which sends it.
 */
class RemoteInputReplyAction(
    private val pendingIntent: PendingIntent,
    private val remoteInput: RemoteInput
) : ReplyAction {

    override fun send(context: Context, text: String): Boolean {
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
        return try {
            pendingIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            // The notification was replaced or dismissed; the action is gone.
            false
        }
    }
}

/**
 * Live reply actions per conversation (phone-inbox T3).
 *
 * Process-wide on purpose: the listener registers actions, T4's policy sends
 * automatic replies and T5 sends a suggested reply the owner tapped, and all
 * three run in the same app process but not in the same object graph.
 *
 * A dead action is dropped as soon as delivery fails, so [send] never keeps
 * returning true for a notification the source app already removed.
 */
object ReplyActionRegistry {

    private val actions = ConcurrentHashMap<String, ReplyAction>()

    fun register(conversationKey: String, action: ReplyAction) {
        actions[conversationKey] = action
    }

    fun has(conversationKey: String): Boolean = actions.containsKey(conversationKey)

    fun drop(conversationKey: String) {
        actions.remove(conversationKey)
    }

    fun clear() = actions.clear()

    /**
     * Testable core: resolves the live action and applies the "failed delivery
     * means dead action" rule. [deliver] is the platform call.
     */
    fun send(
        conversationKey: String,
        text: String,
        deliver: (ReplyAction, String) -> Boolean
    ): Boolean {
        val action = actions[conversationKey] ?: return false
        // An empty auto-reply would be worse than none: never send it.
        if (text.isBlank()) return false
        val delivered = try {
            deliver(action, text)
        } catch (e: Exception) {
            false
        }
        if (!delivered) actions.remove(conversationKey, action)
        return delivered
    }

    /** Production entry point used by T4/T5. */
    fun send(context: Context, conversationKey: String, text: String): Boolean =
        send(conversationKey, text) { action, body -> action.send(context, body) }
}
