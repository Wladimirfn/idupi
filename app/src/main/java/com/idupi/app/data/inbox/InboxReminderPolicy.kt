package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.InboxSettings
import java.time.Instant

/**
 * Reminder and template auto-reply rules (phone-inbox T4), kept pure so every
 * time/gate combination is testable on the JVM.
 *
 * The policy never touches Android: [InboxNotificationListener] polls the
 * server, feeds the items plus the process-local state here, and executes the
 * returned [Action]s. Auto-reply text is ALWAYS
 * [InboxSettings.autoReplyTemplate] and never the agent's
 * [InboxItem.suggestedReply]: a crafted message could steer the model, and
 * auto-sending its output back to the sender would be an exfiltration path
 * (see the feature's Decisions section).
 */
object InboxReminderPolicy {

    /**
     * A conversation that received a template auto-reply is not auto-answered
     * again for this long. The per-tick rule alone let a burst of messages
     * from one sender trigger the template once per tick, minutes apart.
     */
    const val AUTO_REPLY_COOLDOWN_MILLIS: Long = 6 * 60 * 60_000L

    /** Process-local per-item progress, owned by the caller across ticks. */
    data class ItemState(
        val lastRemindedAtMillis: Long? = null,
        val autoReplyDone: Boolean = false,
    )

    /** What the host should do for one item on this tick. */
    sealed interface Action {
        /** Post a local reminder notification for [itemId]. */
        data class Remind(val itemId: String) : Action

        /** Send [text] through the item's live reply action. */
        data class AutoReply(val itemId: String, val text: String) : Action
    }

    /**
     * Decides this tick's actions.
     *
     * Rules, in order for every server item:
     * - Only `open` items of an app the owner still follows qualify.
     * - Importance: an item whose analysis failed or is still running counts
     *   as `medium`; a ready item without a level counts as `low` so an
     *   inconclusive analysis never makes the phone buzz.
     * - Auto-reply (checked first, so a message being answered is not
     *   reminded too): enabled, WhatsApp/Business only, importance passes the
     *   gate, the delay elapsed since it was received, not done yet, a
     *   non-blank template, and a live reply action for its conversation. At
     *   most one auto-reply per conversation per tick, and none while any item
     *   of that conversation was marked `auto_replied` within
     *   [AUTO_REPLY_COOLDOWN_MILLIS] (server state, so it survives restarts).
     * - Remind: when `now - max(receivedAt, lastRemindedAt)` reached the
     *   configured interval. An item that just received an auto-reply is not
     *   reminded in the same tick.
     *
     * [stateFor] and [hasLiveReplyAction] are functions so the caller can back
     * them with a map and [ReplyActionRegistry] while tests use plain lambdas.
     *
     * [isSilencedConversation] implements "silence but read" (inbox-management
     * T4): a conversation the owner silenced inside IDUPI gets no reminder
     * unless the analysis says `high`, because that is the one level the owner
     * still wants to hear. Auto-reply is a setting, not a notification, so this
     * gate does not touch it.
     */
    fun decide(
        items: List<InboxItem>,
        settings: InboxSettings,
        nowMillis: Long,
        stateFor: (String) -> ItemState = { ItemState() },
        hasLiveReplyAction: (String) -> Boolean = { false },
        isSilencedConversation: (String) -> Boolean = { false },
    ): List<Action> {
        if (items.isEmpty()) return emptyList()

        val minImportance = InboxSettings.normalizeImportance(settings.remindMinImportance)
        val reminderIntervalMillis = settings.reminderIntervalMinutes.coerceAtLeast(1) * 60_000L
        val autoReplyDelayMillis = settings.autoReplyDelayMinutes.coerceAtLeast(0) * 60_000L
        val autoReplyTemplate = settings.autoReplyTemplate

        val actions = mutableListOf<Action>()
        val repliedConversations = items
            .filter { it.state == InboxItemState.AUTO_REPLIED }
            .filter { item ->
                val repliedAt = parseInstantMillis(item.updatedAt) ?: receivedAtMillis(item)
                repliedAt != null && nowMillis - repliedAt < AUTO_REPLY_COOLDOWN_MILLIS
            }
            .mapTo(mutableSetOf()) { inboxConversationKey(it.app, it.conversation) }

        for (item in items) {
            if (!isEligible(item, settings)) continue
            val receivedAt = receivedAtMillis(item) ?: continue
            val state = stateFor(item.id)
            val importance = effectiveImportance(item)
            if (!passesImportanceGate(importance, minImportance)) continue
            val conversationKey = inboxConversationKey(item.app, item.conversation)

            val autoReply = autoReplyFor(
                item = item,
                settings = settings,
                state = state,
                receivedAt = receivedAt,
                nowMillis = nowMillis,
                delayMillis = autoReplyDelayMillis,
                template = autoReplyTemplate,
                repliedConversations = repliedConversations,
                hasLiveReplyAction = hasLiveReplyAction,
            )
            if (autoReply != null) {
                actions += autoReply
                repliedConversations += conversationKey
                continue
            }

            // Silence-but-read: no reminder for a muted conversation unless
            // the analysis says the owner must act soon.
            if (isSilencedConversation(conversationKey) && importance != InboxImportance.HIGH) {
                continue
            }

            val baseline = maxOf(receivedAt, state.lastRemindedAtMillis ?: Long.MIN_VALUE)
            if (nowMillis - baseline >= reminderIntervalMillis) {
                actions += Action.Remind(item.id)
            }
        }
        return actions
    }

    /**
     * Importance the reminder gate actually sees. The agent leaves
     * [InboxItem.importance] empty until it finishes, so "failed" and
     * "analyzing" are treated as medium on purpose: a message that could not
     * be judged must not be silently buried.
     */
    fun effectiveImportance(item: InboxItem): String {
        val raw = item.importance
        return when {
            raw == InboxImportance.HIGH || raw == InboxImportance.MEDIUM ||
                raw == InboxImportance.LOW -> raw!!
            item.status == InboxItemStatus.READY -> InboxImportance.LOW
            else -> InboxImportance.MEDIUM
        }
    }

    /** `high` lets only high through; `medium` also lets medium through. */
    fun passesImportanceGate(importance: String, minImportance: String): Boolean =
        when (InboxSettings.normalizeImportance(minImportance)) {
            InboxImportance.HIGH -> importance == InboxImportance.HIGH
            else -> importance == InboxImportance.HIGH || importance == InboxImportance.MEDIUM
        }

    /** [InboxItem.receivedAt] wins; [InboxItem.postedAt] is the fallback. */
    fun receivedAtMillis(item: InboxItem): Long? =
        parseInstantMillis(item.receivedAt) ?: parseInstantMillis(item.postedAt)

    private fun parseInstantMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun isEligible(item: InboxItem, settings: InboxSettings): Boolean =
        item.id.isNotBlank() &&
            item.state == InboxItemState.OPEN &&
            item.app in settings.followedApps

    private fun autoReplyFor(
        item: InboxItem,
        settings: InboxSettings,
        state: ItemState,
        receivedAt: Long,
        nowMillis: Long,
        delayMillis: Long,
        template: String,
        repliedConversations: Set<String>,
        hasLiveReplyAction: (String) -> Boolean,
    ): Action.AutoReply? {
        if (!settings.autoReplyEnabled) return null
        if (!isWhatsApp(item.app)) return null
        if (state.autoReplyDone) return null
        if (template.isBlank()) return null
        if (nowMillis - receivedAt < delayMillis) return null
        val conversationKey = inboxConversationKey(item.app, item.conversation)
        if (conversationKey in repliedConversations) return null
        if (!hasLiveReplyAction(conversationKey)) return null
        return Action.AutoReply(itemId = item.id, text = template)
    }

    private fun isWhatsApp(app: String): Boolean =
        app == InboxApp.WHATSAPP.id || app == InboxApp.WHATSAPP_BUSINESS.id
}
