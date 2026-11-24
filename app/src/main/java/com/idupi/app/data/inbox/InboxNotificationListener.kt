package com.idupi.app.data.inbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxMessageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures WhatsApp/Business, Gmail and Outlook notifications and uploads them
 * to the owner's PC (phone-inbox T3).
 *
 * Flow per post: build a plain [NotificationSnapshot], let [NotificationParser]
 * decide whether it is a message, remember the notification's own reply action
 * for T4/T5, record the conversation in [InboxSession], then upload in the
 * background through the same client [com.idupi.app.data.notification.DeliveryWatcher]
 * uses -- the shared [IduPiClientProvider], configured from the saved profile
 * because the system can start this service in a process where MainActivity
 * never ran.
 *
 * A notification removal caused by the owner (tap/swipe/app clear) marks the
 * conversation's open items handled after a short grace period. The grace
 * period is [InboxRemovalRules.REMOVAL_GRACE_MS] and it exists because
 * WhatsApp updates a conversation by cancel + re-post with the same key; a
 * re-post cancels the pending marking, so an update is never mistaken for
 * "read".
 *
 * Privacy: message text, sender and conversation only leave the phone to the
 * owner's server. This class logs app ids, counts and exception classes -- never
 * message content.
 */
class InboxNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pending "the owner dismissed this" markings, one per notification key.
     * A re-post with the same key cancels the job.
     */
    private val pendingHandled = ConcurrentHashMap<String, Job>()

    /** Set once the saved connection profile has been applied to the client. */
    private var clientConfigured = false

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val posted = sbn ?: return
        try {
            handlePosted(posted)
        } catch (e: Exception) {
            // A malformed notification must never crash the listener: the
            // system disables a listener that keeps dying. No content logged.
            Log.w(TAG, "could not capture notification: ${e.javaClass.simpleName}")
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        val key = sbn?.key ?: return
        if (!InboxRemovalRules.isUserDismissalReason(reason)) return
        val entry = InboxSession.find(key) ?: return

        val removedAt = System.currentTimeMillis()
        pendingHandled.remove(key)?.cancel()
        pendingHandled[key] = scope.launch {
            delay(InboxRemovalRules.REMOVAL_GRACE_MS)
            pendingHandled.remove(key)
            // A re-post that landed before or during the grace period wins:
            // it either cancelled this job already or refreshed lastSeen.
            val latest = InboxSession.find(key)
            if (latest != null && latest.lastSeenMillis > removedAt) return@launch
            ReplyActionRegistry.drop(entry.conversationKey)
            markConversationHandled(latest ?: entry)
        }
    }

    override fun onDestroy() {
        pendingHandled.values.forEach { it.cancel() }
        pendingHandled.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val captured = NotificationParser.parse(snapshotOf(sbn)) ?: return

        // An update of the same notification is not "read": cancel any pending
        // handled marking before remembering the refreshed capture.
        pendingHandled.remove(sbn.key)?.cancel()
        registerReplyAction(sbn, captured)
        InboxSession.remember(captured)
        scope.launch { upload(captured) }
    }

    /**
     * Turns the Android notification into the plain snapshot the parser takes.
     * MessagingStyle is where the per-message sender and the group name live;
     * mail apps put the sender in the title and subject + snippet in bigText.
     */
    private fun snapshotOf(sbn: StatusBarNotification): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification?.extras
        val style = runCatching {
            notification?.let {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
            }
        }.getOrNull()

        val lines = style?.messages?.map { message ->
            val sender = message.sender ?: message.person?.name
            MessagingLine(sender = sender?.toString(), text = message.text?.toString())
        }.orEmpty()

        return NotificationSnapshot(
            packageName = sbn.packageName ?: "",
            key = sbn.key ?: "",
            postTime = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isGroupSummary = ((notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = (
                style?.conversationTitle
                    ?: extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                )?.toString(),
            messagingLines = lines
        )
    }

    /**
     * Keeps the first action that actually carries a [android.app.RemoteInput]
     * (the "reply" action), keyed by conversation. Mark-as-read and other
     * actions have no RemoteInput and are ignored.
     */
    private fun registerReplyAction(sbn: StatusBarNotification, captured: CapturedMessage) {
        val actions = sbn.notification?.actions ?: return
        for (action in actions) {
            val remoteInput = action.remoteInputs?.firstOrNull() ?: continue
            val actionIntent = action.actionIntent ?: continue
            ReplyActionRegistry.register(
                inboxConversationKey(captured.app, captured.conversation),
                RemoteInputReplyAction(actionIntent, remoteInput)
            )
            return
        }
    }

    private suspend fun upload(captured: CapturedMessage) {
        try {
            val settings = SettingsProvider.repository.inboxSettings.first()
            if (captured.app !in settings.enabledApps) {
                // Monitoring was turned off after the notification appeared.
                ReplyActionRegistry.drop(inboxConversationKey(captured.app, captured.conversation))
                InboxSession.remove(captured.notificationKey)
                return
            }
            if (!ensureClientConfigured()) return

            val item = IduPiClientProvider.client.postInboxMessage(
                InboxMessageRequest(
                    app = captured.app,
                    packageName = captured.packageName,
                    sender = captured.sender,
                    conversation = captured.conversation,
                    text = captured.text,
                    postedAt = captured.postedAt,
                    notificationKey = captured.notificationKey
                )
            )
            InboxSession.attachItemId(captured.notificationKey, item.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "inbox upload failed (${captured.app}): ${e.javaClass.simpleName}")
        }
    }

    /**
     * Marks the conversation's open server items handled. The server has no
     * bulk endpoint, so this lists the recent items and transitions only the
     * ones that still belong to this app + conversation and are open: a reply
     * T4 already sent is `replied`/`auto_replied` and stays that way.
     */
    private suspend fun markConversationHandled(entry: InboxSession.Entry) {
        try {
            if (!ensureClientConfigured()) return
            val open = IduPiClientProvider.client.getInboxItems(LIST_LIMIT)
                .filter {
                    it.app == entry.app &&
                        it.conversation == entry.conversation &&
                        it.state == InboxItemState.OPEN
                }
            open.forEach { item ->
                IduPiClientProvider.client.setInboxItemState(item.id, InboxItemState.HANDLED)
            }
            if (open.isNotEmpty()) {
                Log.i(TAG, "marked ${open.size} ${entry.app} item(s) handled")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not mark ${entry.app} conversation handled: ${e.javaClass.simpleName}")
        } finally {
            // The notification is gone either way; T4 must stop reminding for
            // this conversation and re-read the server state on its own.
            InboxSession.removeConversation(entry.conversationKey)
        }
    }

    private fun ensureClientConfigured(): Boolean {
        clientConfigured = clientConfigured || IduPiClientProvider.configureFromStorage(this)
        return clientConfigured
    }

    companion object {
        private const val TAG = "InboxListener"

        /** Recent items inspected when resolving a conversation's open ones. */
        private const val LIST_LIMIT = 50
    }
}
