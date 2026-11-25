package com.idupi.app.data.inbox

import android.service.notification.NotificationListenerService

/**
 * When a notification removal means "the owner dealt with this conversation"
 * (phone-inbox T3).
 *
 * Only a user-visible dismissal counts: tapping the notification, swiping it
 * away or the source app clearing it. Everything else (group-summary cleanup,
 * listener rebinds, errors, package changes) leaves the items open.
 */
object InboxRemovalRules {

    /**
     * How long a removal waits before the conversation is marked handled.
     * WhatsApp updates a conversation by cancelling and re-posting the SAME
     * notification key within milliseconds; a re-post inside this window
     * cancels the marking. A real dismissal followed by a brand-new message
     * usually takes longer than this, so it keeps its own open item.
     */
    const val REMOVAL_GRACE_MS = 2_000L

    fun isUserDismissalReason(reason: Int): Boolean = when (reason) {
        NotificationListenerService.REASON_CLICK,      // tapped: the owner opened it
        NotificationListenerService.REASON_CANCEL,     // swiped away
        NotificationListenerService.REASON_APP_CANCEL  // source app cleared it
            -> true
        else -> false
    }
}
