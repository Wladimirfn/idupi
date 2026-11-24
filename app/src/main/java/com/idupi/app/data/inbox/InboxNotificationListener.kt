package com.idupi.app.data.inbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.InboxMessageRequest
import com.idupi.app.domain.model.InboxSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures notifications from every app the owner follows and uploads them to
 * the owner's PC (phone-inbox T2/T3).
 *
 * Flow per post: build a plain [NotificationSnapshot], let [NotificationParser]
 * decide whether it is a message (preset id or package name as the app id, plus
 * the PackageManager label), remember the notification's own reply action for
 * T4/T5, record the conversation in [InboxSession], then upload in the
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
 *
 * While the listener is connected a coroutine ticks every
 * [REMINDER_TICK_MS]: it fetches the recent items, asks
 * [InboxReminderPolicy] what to do and executes it (reminder notification or
 * template auto-reply). The loop starts on [onListenerConnected] and stops on
 * [onListenerDisconnected]/[onDestroy], so losing notification access also
 * stops the reminders.
 *
 * Capture reliability (inbox capture fix T1): on connect the listener also
 * reads the notifications already in the shade (`activeNotifications`) through
 * the same parser as live posts. A failed upload is persisted in the bounded
 * [InboxRetryQueue] and drained on connect, on every tick, right after a
 * successful upload and on demand from Ajustes. Ajustes drives the on-demand
 * scan through [InboxCaptureController] and reads [InboxCaptureDiagnostics];
 * it never touches this service directly.
 */
class InboxNotificationListener : NotificationListenerService(), InboxScanHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True between [onListenerConnected] and disconnect/destroy. */
    @Volatile
    private var connected = false

    /** Serialises queue drains; an overlapping drain is a no-op. */
    private val drainMutex = Mutex()

    /** Persistent failed-upload queue, app-private and bounded. */
    private val retryRepository by lazy { InboxRetryProvider.get() }

    /**
     * Followed app ids as last observed (phone-inbox T3). Settings changes are
     * rare and written by our own UI; a collector keeps this current so a live
     * post can be filtered without a suspend call on the system's callback.
     */
    @Volatile
    private var followedApps: Set<String> = InboxApp.defaultFollowedIds

    /** Keeps [followedApps] in sync while the listener is connected. */
    private var settingsJob: Job? = null

    /** PackageManager label per package; a launcher label only changes on update. */
    private val appLabels = ConcurrentHashMap<String, String>()

    /**
     * Pending "the owner dismissed this" markings, one per notification key.
     * A re-post with the same key cancels the job.
     */
    private val pendingHandled = ConcurrentHashMap<String, Job>()

    /** Set once the saved connection profile has been applied to the client. */
    private var clientConfigured = false

    /** Reminder/auto-reply loop, alive only while the listener is connected. */
    private var reminderJob: Job? = null

    /** Per-item progress the policy needs across ticks; process-local on purpose. */
    private val reminderStates = ConcurrentHashMap<String, ReminderState>()

    /** Last analysis status seen per item, for the one-shot "important" ping. */
    private val observedStatuses = ConcurrentHashMap<String, String>()

    /** Item ids already announced as analyzed and important. */
    private val notifiedReady = ConcurrentHashMap.newKeySet<String>()

    private val notifier by lazy { InboxNotifier(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        InboxCaptureController.attach(this)
        InboxCaptureDiagnostics.onListenerConnected()
        startSettingsWatch()
        startReminderLoop()
        // phone-agent T2: this listener is the long-lived host that already
        // exists. The reporter is process-wide and idempotent, so T3's
        // accessibility service can start the same instance without a second loop.
        PhoneStatusReporting.start(this)
        // Notifications already in the shade are never re-posted by the system:
        // this scan is the only way they reach the PC.
        scope.launch { runInitialScan() }
    }

    override fun onListenerDisconnected() {
        connected = false
        InboxCaptureController.detach(this)
        InboxCaptureDiagnostics.onListenerDisconnected()
        stopSettingsWatch()
        stopReminderLoop()
        PhoneStatusReporting.stop()
        super.onListenerDisconnected()
    }

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
        connected = false
        InboxCaptureController.detach(this)
        InboxCaptureDiagnostics.onListenerDisconnected()
        stopSettingsWatch()
        stopReminderLoop()
        pendingHandled.values.forEach { it.cancel() }
        pendingHandled.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val captured = NotificationParser.parse(snapshotOf(sbn), followedApps)
            ?.withPackageLabel() ?: return

        // An update of the same notification is not "read": cancel any pending
        // handled marking before remembering the refreshed capture.
        pendingHandled.remove(sbn.key)?.cancel()
        registerReplyAction(sbn, captured)
        InboxSession.remember(captured)
        InboxCaptureDiagnostics.onCaptured(captured.app)
        scope.launch {
            if (upload(captured) == UploadOutcome.ACCEPTED) drainRetries()
        }
    }

    /**
     * Settings can change while the listener lives, and a live post must not
     * wait for a DataStore read: collect the persisted follow list once per
     * connection and keep the volatile field current.
     */
    private fun startSettingsWatch() {
        if (settingsJob?.isActive == true) return
        settingsJob = scope.launch {
            try {
                SettingsProvider.repository.inboxSettings.collect { settings ->
                    followedApps = settings.followedApps
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not observe inbox settings: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun stopSettingsWatch() {
        settingsJob?.cancel()
        settingsJob = null
    }

    /**
     * PackageManager label for display. A failing lookup stays empty and the
     * item falls back to the preset label or the app id. Labels are app names,
     * never message content.
     */
    private fun appLabelOf(packageName: String): String {
        appLabels[packageName]?.let { return it }
        val label = try {
            val manager = packageManager
            @Suppress("DEPRECATION")
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            ""
        }
        appLabels[packageName] = label
        return label
    }

    private fun CapturedMessage.withPackageLabel(): CapturedMessage =
        if (appLabel.isNotBlank()) this else copy(appLabel = appLabelOf(packageName))

    // --- Active-notification scan and retry drain (inbox capture fix T1) ---

    override fun isCaptureConnected(): Boolean = connected

    /**
     * Reads every notification currently in the shade, captures them through
     * the same parser + reply-action + session path as live posts, then drains
     * the retry queue. The system calls the scan on connect; Ajustes calls it
     * on demand.
     */
    override suspend fun scanActiveNow(): InboxScanResult {
        if (!connected) return InboxScanResult.NotConnected
        val followed = SettingsProvider.repository.inboxSettings.first().followedApps
        followedApps = followed
        val pairs = snapshotActiveNotifications()
        val sbnByKey = pairs.associate { it.second.key to it.first }
        val captured = InboxActiveScan.select(pairs.map { it.second }, followed)

        var sent = 0
        var uploadDeferred = false
        for (raw in captured) {
            val message = raw.withPackageLabel()
            pendingHandled.remove(message.notificationKey)?.cancel()
            if (message.notificationKey.isNotBlank()) {
                sbnByKey[message.notificationKey]?.let { sbn ->
                    registerReplyAction(sbn, message)
                }
            }
            InboxSession.remember(message)
            InboxCaptureDiagnostics.onCaptured(message.app)
            when (upload(message)) {
                UploadOutcome.ACCEPTED -> sent++
                UploadOutcome.QUEUED -> uploadDeferred = true
                UploadOutcome.DISCARDED -> Unit
            }
        }
        InboxCaptureDiagnostics.onScanCompleted(captured.size)
        // A deferred upload just proved the PC is unreachable (or unconfigured):
        // draining immediately would spend another timeout on the same
        // condition. The queue waits for the next tick or connect.
        if (!uploadDeferred) sent += drainRetries()
        return InboxScanResult.Completed(captured = captured.size, sent = sent)
    }

    /**
     * `activeNotifications` is a binder call that throws SecurityException when
     * access was revoked mid-run. One unreadable notification must not abort the
     * whole scan, so each snapshot is built defensively.
     */
    private fun snapshotActiveNotifications():
        List<Pair<StatusBarNotification, NotificationSnapshot>> {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        val pairs = ArrayList<Pair<StatusBarNotification, NotificationSnapshot>>(active.size)
        for (sbn in active) {
            val posted = sbn ?: continue
            val snapshot = try {
                snapshotOf(posted)
            } catch (e: Exception) {
                continue
            }
            pairs.add(posted to snapshot)
        }
        return pairs
    }

    /** The connect-time scan must never crash the listener (Android disables it). */
    private suspend fun runInitialScan() {
        try {
            // Deliver what was queued while the PC was down before reading the
            // shade; the scan then drains again if it captured anything new.
            drainRetries()
            scanActiveNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "initial inbox scan failed: ${e.javaClass.simpleName}")
        }
    }

    // --- Reminders and template auto-reply (phone-inbox T4) ---

    private fun startReminderLoop() {
        if (reminderJob?.isActive == true) return
        reminderJob = scope.launch {
            while (isActive) {
                delay(REMINDER_TICK_MS)
                try {
                    // The PC may have come back since the last failed upload.
                    drainRetries()
                    runReminderTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The server may be off; the next tick retries. No content logged.
                    Log.w(TAG, "inbox reminder tick failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun stopReminderLoop() {
        reminderJob?.cancel()
        reminderJob = null
    }

    /**
     * One policy pass: read the items, announce freshly analyzed important
     * messages once, then apply the reminder/auto-reply decisions.
     */
    private suspend fun runReminderTick() {
        if (!ensureClientConfigured()) return
        val settings = SettingsProvider.repository.inboxSettings.first()
        val items = IduPiClientProvider.client.getInboxItems(LIST_LIMIT)
        val now = System.currentTimeMillis()

        pruneReminderState(items)
        notifyNewlyReady(items)
        executeActions(items, settings, now)
    }

    private suspend fun executeActions(
        items: List<InboxItem>,
        settings: InboxSettings,
        nowMillis: Long
    ) {
        val itemsById = items.associateBy { it.id }
        val actions = InboxReminderPolicy.decide(
            items = items,
            settings = settings,
            nowMillis = nowMillis,
            stateFor = { id ->
                reminderStates[id]?.toPolicyState() ?: InboxReminderPolicy.ItemState()
            },
            hasLiveReplyAction = ReplyActionRegistry::has
        )

        for (action in actions) {
            when (action) {
                is InboxReminderPolicy.Action.Remind -> {
                    val item = itemsById[action.itemId] ?: continue
                    notifier.showReminder(item)
                    // Recorded even if the post was ignored (no notification
                    // permission): otherwise every 60 s tick would retry it.
                    reminderState(action.itemId).lastRemindedAtMillis = nowMillis
                }
                is InboxReminderPolicy.Action.AutoReply -> {
                    val item = itemsById[action.itemId] ?: continue
                    sendAutoReply(item, action.text)
                }
            }
        }
    }

    /**
     * Sends the owner's fixed template through the notification's own reply
     * action. On success the server item becomes `auto_replied`; on failure the
     * item keeps being reminded (the registry already dropped a dead action, so
     * the next ticks simply stop trying to send).
     */
    private suspend fun sendAutoReply(item: InboxItem, text: String) {
        val conversationKey = inboxConversationKey(item.app, item.conversation)
        if (!ReplyActionRegistry.send(this, conversationKey, text)) {
            Log.i(TAG, "auto-reply not delivered (${item.app})")
            return
        }
        reminderState(item.id).autoReplyDone = true
        try {
            IduPiClientProvider.client.setInboxItemState(item.id, InboxItemState.AUTO_REPLIED)
            Log.i(TAG, "auto-replied ${item.app} item")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto-reply sent but state update failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Announces an important item once, when its analysis finishes.
     *
     * Items that were already `ready` before this listener process started are
     * not announced: only a status transition seen here, or an item this
     * process captured (it has an [InboxSession] entry), is "newly analyzed".
     */
    private fun notifyNewlyReady(items: List<InboxItem>) {
        val capturedItemIds = InboxSession.all().mapNotNull { it.itemId }.toSet()
        for (item in items) {
            val previousStatus = observedStatuses.put(item.id, item.status)
            if (item.status != InboxItemStatus.READY) continue
            if (item.importance != InboxImportance.HIGH) continue
            if (item.state != InboxItemState.OPEN) continue
            if (!notifiedReady.add(item.id)) continue
            val isNewHere = previousStatus != null || item.id in capturedItemIds
            if (!isNewHere) continue
            notifier.showImportantReady(item)
        }
    }

    /** Keeps the process-local maps bounded by the items still on the server list. */
    private fun pruneReminderState(items: List<InboxItem>) {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        reminderStates.keys.retainAll(ids)
        observedStatuses.keys.retainAll(ids)
        notifiedReady.retainAll(ids)
    }

    private fun reminderState(itemId: String): ReminderState =
        reminderStates.getOrPut(itemId) { ReminderState() }

    private class ReminderState(
        var lastRemindedAtMillis: Long? = null,
        var autoReplyDone: Boolean = false,
    ) {
        fun toPolicyState(): InboxReminderPolicy.ItemState =
            InboxReminderPolicy.ItemState(lastRemindedAtMillis, autoReplyDone)
    }

    /** What happened to one capture's upload attempt. */
    private enum class UploadOutcome { ACCEPTED, QUEUED, DISCARDED }

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
            category = notification?.category,
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

    /**
     * Uploads one capture. A failure persists the capture for retry instead of
     * dropping it; a permanent 4xx rejection is not retried because the PC
     * understood and refused it.
     */
    private suspend fun upload(captured: CapturedMessage): UploadOutcome {
        try {
            val settings = SettingsProvider.repository.inboxSettings.first()
            if (captured.app !in settings.followedApps) {
                // The owner stopped following the app after the notification
                // appeared (or the collector had not seen a just-saved change).
                ReplyActionRegistry.drop(inboxConversationKey(captured.app, captured.conversation))
                InboxSession.remove(captured.notificationKey)
                return UploadOutcome.DISCARDED
            }
            if (!ensureClientConfigured()) {
                // No PC configured yet: keep the capture for when one is.
                enqueueRetry(captured)
                return UploadOutcome.QUEUED
            }

            val item = IduPiClientProvider.client.postInboxMessage(captured.toRequest())
            InboxSession.attachItemId(captured.notificationKey, item.id)
            InboxCaptureDiagnostics.onUploadSucceeded()
            return UploadOutcome.ACCEPTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = InboxUploadRules.statusCodeOf(e)
            Log.w(TAG, "inbox upload failed (${captured.app}): ${e.javaClass.simpleName}")
            InboxCaptureDiagnostics.onUploadFailed(e.javaClass.simpleName)
            if (InboxUploadRules.isPermanentRejection(status)) return UploadOutcome.DISCARDED
            enqueueRetry(captured)
            return UploadOutcome.QUEUED
        }
    }

    /** Persists a failed capture; dedupe and bounds are the queue's rules. */
    private suspend fun enqueueRetry(captured: CapturedMessage) {
        try {
            val entries = retryRepository.enqueue(captured)
            InboxCaptureDiagnostics.onPendingCount(entries.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not persist inbox retry: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Sends queued captures in arrival order. The server accepting them or
     * rejecting them permanently removes the entry; a transient failure stops
     * the batch and waits for the next tick instead of hammering a dead PC.
     *
     * @return how many queued uploads the PC accepted.
     */
    private suspend fun drainRetries(): Int {
        if (!drainMutex.tryLock()) return 0
        try {
            if (!ensureClientConfigured()) {
                InboxCaptureDiagnostics.onPendingCount(retryRepository.size())
                return 0
            }
            val batch = retryRepository.selectBatch()
            var sent = 0
            for (entry in batch) {
                try {
                    val item = IduPiClientProvider.client.postInboxMessage(entry.toRequest())
                    if (entry.notificationKey.isNotBlank()) {
                        InboxSession.attachItemId(entry.notificationKey, item.id)
                    }
                    retryRepository.remove(entry)
                    InboxCaptureDiagnostics.onUploadSucceeded()
                    sent++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val status = InboxUploadRules.statusCodeOf(e)
                    Log.w(TAG, "inbox retry failed (${entry.app}): ${e.javaClass.simpleName}")
                    InboxCaptureDiagnostics.onUploadFailed(e.javaClass.simpleName)
                    if (InboxUploadRules.isPermanentRejection(status)) {
                        retryRepository.remove(entry)
                        continue
                    }
                    break
                }
            }
            InboxCaptureDiagnostics.onPendingCount(retryRepository.size())
            return sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "inbox retry drain failed: ${e.javaClass.simpleName}")
            return 0
        } finally {
            drainMutex.unlock()
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

        /** How often the reminder/auto-reply policy runs while connected. */
        private const val REMINDER_TICK_MS = 60_000L
    }
}

/** The wire request for a captured notification; also used by retry drains. */
private fun CapturedMessage.toRequest(): InboxMessageRequest = InboxMessageRequest(
    app = app,
    appLabel = appLabel,
    packageName = packageName,
    sender = sender,
    conversation = conversation,
    text = text,
    postedAt = postedAt,
    notificationKey = notificationKey
)
