package com.idupi.app.data.phone.bubble

/** What one launch of the system assistant entry point must do (assistant-key T1). */
sealed interface AssistLaunch {

    /** Hand the launch to the accessibility overlay and start in-bubble dictation. */
    data object OpenBubbleDictation : AssistLaunch

    /** Open IDUPI on Ajustes and explain the missing accessibility grant. */
    data object OpenAppWithHint : AssistLaunch
}

/**
 * Pure launch rule behind `AssistEntryActivity` (assistant-key T1).
 *
 * The accessibility service is the only component that can draw the bubble, so
 * the rule targets it whenever either signal says it can: the settings grant
 * (the service may still be binding) or the process-wide host being attached
 * (the service is alive even if the settings read lags). Only when both are
 * absent does the launch fall back to the app with the hint.
 */
object AssistLaunchRule {

    fun decide(accessibilityRunning: Boolean, bubbleOverlayAttached: Boolean): AssistLaunch =
        if (accessibilityRunning || bubbleOverlayAttached) {
            AssistLaunch.OpenBubbleDictation
        } else {
            AssistLaunch.OpenAppWithHint
        }
}
