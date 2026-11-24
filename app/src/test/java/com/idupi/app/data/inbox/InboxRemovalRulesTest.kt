package com.idupi.app.data.inbox

import android.service.notification.NotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which removals count as "the owner dealt with this conversation"
 * (phone-inbox T3). A removal caused by an update, a package change or a
 * listener rebind must never mark items handled.
 */
class InboxRemovalRulesTest {

    @Test
    fun `tap, swipe and source-app cancel are owner dismissals`() {
        assertTrue(InboxRemovalRules.isUserDismissalReason(NotificationListenerService.REASON_CLICK))
        assertTrue(InboxRemovalRules.isUserDismissalReason(NotificationListenerService.REASON_CANCEL))
        assertTrue(InboxRemovalRules.isUserDismissalReason(NotificationListenerService.REASON_APP_CANCEL))
    }

    @Test
    fun `system events are not owner dismissals`() {
        val notDismissals = listOf(
            NotificationListenerService.REASON_CANCEL_ALL,
            NotificationListenerService.REASON_APP_CANCEL_ALL,
            NotificationListenerService.REASON_ERROR,
            NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED,
            NotificationListenerService.REASON_LISTENER_CANCEL,
            NotificationListenerService.REASON_LISTENER_CANCEL_ALL,
            NotificationListenerService.REASON_PACKAGE_CHANGED,
            NotificationListenerService.REASON_USER_STOPPED,
        )
        notDismissals.forEach { reason ->
            assertFalse(
                "reason $reason must not mark items handled",
                InboxRemovalRules.isUserDismissalReason(reason)
            )
        }
    }

    @Test
    fun `IDUPI cancelling a silenced notification is not an owner dismissal`() {
        // The listener cancels the notification right after capturing a
        // silenced conversation. Treating that as a dismissal would close the
        // item the owner asked to keep reading, which is the opposite of
        // "silence but read".
        assertFalse(
            InboxRemovalRules.isUserDismissalReason(
                NotificationListenerService.REASON_LISTENER_CANCEL
            )
        )
    }

    @Test
    fun `the update grace window is two seconds`() {
        assertEquals(2_000L, InboxRemovalRules.REMOVAL_GRACE_MS)
    }
}
