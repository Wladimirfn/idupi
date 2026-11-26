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
}
