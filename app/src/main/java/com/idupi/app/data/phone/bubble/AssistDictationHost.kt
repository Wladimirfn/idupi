package com.idupi.app.data.phone.bubble

/**
 * Process-wide bridge between the system assistant entry point and the bubble
 * (assistant-key T1), the same shape as `ApprovalVoiceHost` (phone-bubble T23).
 *
 * They live in the same app process, so a shared callback replaces a binder.
 * The accessibility service attaches while it is connected and detaches when it
 * is unbound; `AssistEntryActivity` asks for one dictation attempt and a
 * detached app answers false, which sends the launch to the app-with-hint path.
 *
 * The handler runs on the caller's thread. The activity calls on the main
 * thread, which is the thread the overlay's window work already needs; do not
 * call [requestDictation] from a background thread.
 */
object AssistDictationHost {

    private val lock = Any()

    private var handler: (() -> Boolean)? = null

    fun attach(handler: () -> Boolean) {
        synchronized(lock) { this.handler = handler }
    }

    fun detach() {
        synchronized(lock) { handler = null }
    }

    fun isAttached(): Boolean = synchronized(lock) { handler != null }

    /**
     * Asks the attached accessibility overlay to open its panel in dictation.
     *
     * @return true when a live overlay took the request; false means the launch
     *   must fall back to opening the app with the accessibility hint.
     */
    fun requestDictation(): Boolean {
        val current = synchronized(lock) { handler } ?: return false
        return current()
    }

    /** Deadline of a side-key press made while locked, or 0 when none. */
    private var deferredUntilMillis = 0L

    /**
     * assistant-key T5: the side key was pressed on the lock screen and the
     * unlock prompt did not complete (Samsung's SystemUI cancelled it). The
     * press is remembered for [UNLOCK_WINDOW_MS]; unlocking inside that window
     * opens the dictation, later unlocks do not.
     */
    fun deferUntilUnlock(nowMillis: Long) {
        synchronized(lock) { deferredUntilMillis = nowMillis + UNLOCK_WINDOW_MS }
    }

    /** True once if a deferred press is still live at [nowMillis]; clears it. */
    fun consumeDeferred(nowMillis: Long): Boolean = synchronized(lock) {
        val live = deferredUntilMillis != 0L && nowMillis <= deferredUntilMillis
        deferredUntilMillis = 0L
        live
    }

    /** How long a locked side-key press waits for the unlock. */
    const val UNLOCK_WINDOW_MS = 30_000L
}
