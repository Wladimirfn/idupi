package com.idupi.app.domain.model

/**
 * Phone-side Alertas settings (phone-inbox T2/T3).
 *
 * Defaults mirror the feature's agreed behaviour: the four original apps plus
 * Messenger and Telegram are followed, important unanswered items are reminded
 * every 15 minutes and auto-reply is OFF until the owner enables it. The
 * template is the only text an automatic reply ever sends (see the feature's
 * Decisions section); it is Spanish because the owner's contacts are.
 */
data class InboxSettings(
    /**
     * Apps IDUPI follows: a preset [InboxApp.id] or the package name of any
     * other installed app. Empty is a real choice ("follow nothing").
     */
    val followedApps: Set<String> = InboxApp.defaultFollowedIds,
    val reminderIntervalMinutes: Int = DEFAULT_REMINDER_INTERVAL_MINUTES,
    /** Only [InboxImportance.HIGH] or [InboxImportance.MEDIUM] trigger reminders. */
    val remindMinImportance: String = InboxImportance.HIGH,
    val autoReplyEnabled: Boolean = false,
    val autoReplyDelayMinutes: Int = DEFAULT_AUTO_REPLY_DELAY_MINUTES,
    val autoReplyTemplate: String = DEFAULT_AUTO_REPLY_TEMPLATE,
    /**
     * Conversations the owner silenced inside IDUPI (inbox-management T4),
     * keyed by `inboxConversationKey(app, conversation)`. IDUPI keeps capturing
     * them, removes their notification from the shade and suppresses their
     * reminders, so the owner can read a busy group without the notifications.
     * The source app's own mute must be OFF: a muted chat posts nothing.
     */
    val silencedConversations: Set<String> = emptySet()
) {

    companion object {
        const val DEFAULT_REMINDER_INTERVAL_MINUTES = 15
        const val DEFAULT_AUTO_REPLY_DELAY_MINUTES = 30
        const val DEFAULT_AUTO_REPLY_TEMPLATE =
            "Hola, en este momento no puedo responder. Te contesto apenas pueda."

        /** The only levels the reminder policy understands, lowest last. */
        val REMIND_MIN_IMPORTANCES = listOf(InboxImportance.HIGH, InboxImportance.MEDIUM)

        /**
         * Keeps an unknown stored level from silently disabling or flooding
         * reminders: anything outside the supported pair means "high", the
         * stricter choice.
         */
        fun normalizeImportance(raw: String?): String =
            if (raw in REMIND_MIN_IMPORTANCES) raw!! else InboxImportance.HIGH
    }
}
