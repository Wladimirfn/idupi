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

/** What the system assistant entry point does about the lock screen (assistant-key T5). */
enum class AssistUnlockDecision {

    /** The phone is unlocked: the launch rule may run now. */
    Launch,

    /** The keyguard is showing: ask the owner to unlock and open nothing yet. */
    AskToUnlock,
}

/**
 * Pure keyguard rule behind `AssistEntryActivity` (assistant-key T5).
 *
 * Firing `android.intent.action.ASSIST` while the phone was locked opened the
 * bubble panel over the lock screen, and a bubble turn approves messaging out
 * loud (phone-bubble T23), so a locked phone must never reach the launch rule
 * before the owner is past the keyguard. The activity asks the system to
 * dismiss the keyguard and only an `onDismissSucceeded` callback runs the
 * launch rule; a cancelled or failed dismissal opens nothing.
 */
object AssistUnlockRule {

    fun decide(keyguardLocked: Boolean): AssistUnlockDecision =
        if (keyguardLocked) AssistUnlockDecision.AskToUnlock else AssistUnlockDecision.Launch
}
