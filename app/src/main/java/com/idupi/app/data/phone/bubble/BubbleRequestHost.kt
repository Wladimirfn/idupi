package com.idupi.app.data.phone.bubble

/**
 * Process-wide bridge between the Dashboard's "Optimizar teléfono" button and
 * the bubble (phone-dashboard T2), the same shape as [AssistDictationHost].
 *
 * The button must show progress and approvals in the bubble, so it cannot call
 * `IduPiClient.startPhoneAgentTurn` itself: only the overlay's normal send path
 * registers the turn in `OwnerTurnRegistry`, which is what authorizes an
 * owner-initiated turn on this phone. If the accessibility service is not
 * attached, the bubble cannot open and [send] answers false; the screen then
 * asks the owner to enable accessibility instead of starting an orphan turn.
 *
 * The handler runs on the caller's thread. The Dashboard calls from the main
 * thread, which is the thread the overlay's window work already needs.
 */
object BubbleRequestHost {

    private val lock = Any()

    private var handler: ((String) -> Boolean)? = null

    fun attach(handler: (String) -> Boolean) {
        synchronized(lock) { this.handler = handler }
    }

    fun detach() {
        synchronized(lock) { handler = null }
    }

    fun isAttached(): Boolean = synchronized(lock) { handler != null }

    /**
     * Hands [text] to the attached overlay as if the owner had typed it: the
     * panel expands and the controller's own send path starts the turn.
     *
     * @return true when a live overlay accepted the request; false means
     *   accessibility is off (or the panel could not open) and nothing ran.
     */
    fun send(text: String): Boolean {
        val current = synchronized(lock) { handler } ?: return false
        return current(text)
    }
}
