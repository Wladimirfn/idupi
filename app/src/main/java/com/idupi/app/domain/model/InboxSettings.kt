package com.idupi.app.domain.model

/**
 * Phone-side Alertas settings (phone-inbox T2).
 *
 * Defaults mirror the feature's agreed behaviour: every supported app is
 * captured, important unanswered items are reminded every 15 minutes and
 * auto-reply is OFF until the owner enables it. The template is the only text
 * an automatic reply ever sends (see the feature's Decisions section); it is
 * Spanish because the owner's contacts are.
 */
data class InboxSettings(
    val enabledApps: Set<String> = InboxApp.allIds,
    val reminderIntervalMinutes: Int = DEFAULT_REMINDER_INTERVAL_MINUTES,
    /** Only [InboxImportance.HIGH] or [InboxImportance.MEDIUM] trigger reminders. */
    val remindMinImportance: String = InboxImportance.HIGH,
    val autoReplyEnabled: Boolean = false,
    val autoReplyDelayMinutes: Int = DEFAULT_AUTO_REPLY_DELAY_MINUTES,
    val autoReplyTemplate: String = DEFAULT_AUTO_REPLY_TEMPLATE
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
