package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval

/**
 * Process-wide bridge between the approval poll and the bubble overlay
 * (phone-bubble T23).
 *
 * They live in the same app process, so a shared callback replaces a binder.
 * The overlay registers while its window is attached and detaches when it is
 * removed; the dispatcher only offers an approval when a handler exists, and
 * the handler answers false when it cannot take it (so the notification is
 * posted instead). Off the main thread by design: the handler returns the
 * acceptance and the overlay moves the window work to its own main scope.
 */
object ApprovalVoiceHost {

    private val lock = Any()

    private var handler: ((PhoneApproval) -> Boolean)? = null

    fun attach(handler: (PhoneApproval) -> Boolean) {
        synchronized(lock) { this.handler = handler }
    }

    fun detach() {
        synchronized(lock) { handler = null }
    }

    fun isAttached(): Boolean = synchronized(lock) { handler != null }

    /**
     * Offers [approval] to the attached bubble.
     *
     * @return true when the bubble took ownership of the approval; false means
     *   there is no attached bubble and the caller must post the notification.
     */
    fun offer(approval: PhoneApproval): Boolean {
        val current = synchronized(lock) { handler } ?: return false
        return current(approval)
    }
}
