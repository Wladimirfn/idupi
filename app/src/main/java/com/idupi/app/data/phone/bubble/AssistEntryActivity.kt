package com.idupi.app.data.phone.bubble

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.idupi.app.MainActivity
import com.idupi.app.data.phone.control.isAccessibilityControlEnabled
import com.idupi.app.ui.navigation.AppNavigationIntents
import com.idupi.app.ui.navigation.Screen

/**
 * Exported entry point for the system's digital assistant (assistant-key T1).
 *
 * It is declared for `android.intent.action.ASSIST` and `VOICE_ASSIST`, which is
 * what makes IDUPI appear under "Aplicación de asistente digital" next to
 * Google, ChatGPT, Claude and Meta AI. A long press of the side key starts it.
 *
 * Exported means any app can start it, so it is deliberately inert: it reads no
 * extras, accepts no data and performs exactly one action — hand a dictation
 * request to the accessibility bubble, or open the app with the grant hint when
 * the bubble cannot run. It draws no UI of its own, owns an empty task affinity
 * and finishes immediately, so launching it never brings MainActivity up behind
 * the bubble (the same lesson as phone-bubble T20).
 *
 * Since assistant-key T5 a locked phone changes that: accessibility overlays
 * draw above the lock screen, so opening the dictation panel there would let
 * whoever holds the locked phone dictate a message that the bubble turn then
 * auto-approves (phone-bubble T23). While the keyguard is showing this activity
 * asks the system to dismiss it and waits; only `onDismissSucceeded` runs the
 * launch rule, and a cancelled or failed dismissal opens nothing.
 */
class AssistEntryActivity : Activity() {

    /** Set once the dismiss request went out, so a second resume never repeats it. */
    private var unlockRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        when (AssistUnlockRule.decide(keyguard.isKeyguardLocked)) {
            AssistUnlockDecision.Launch -> launchAndFinish()
            // Visible over the keyguard first; the request itself waits for
            // onResume (verified on the owner's Samsung: requesting from
            // onCreate failed at once and the activity was destroyed).
            AssistUnlockDecision.AskToUnlock -> {
                // Only an opaque activity occludes the keyguard; a translucent
                // one is dropped from over the lock screen and SystemUI then
                // cancels the dismiss request (seen on the owner's Samsung:
                // OCCLUDED -> LOCKSCREEN, notifyDismissCancelled). The unlock
                // UI covers this window, so going opaque shows nothing extra.
                setTranslucent(false)
                setShowWhenLocked(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || unlockRequested) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked) {
            // Unlocked between onCreate and now (e.g. face unlock): just go.
            launchAndFinish()
            return
        }
        unlockRequested = true
        requestUnlock(keyguard)
    }

    /**
     * Asks the system for the keyguard dismissal and keeps the activity alive
     * while the owner decides.
     *
     * `KeyguardManager.requestDismissKeyguard` documents that the requesting
     * activity must be visible through `showWhenLocked` (or be in a state in
     * which it would be visible if the keyguard were not hiding it), otherwise
     * the request fails immediately with `onDismissError`; it is also what lets
     * the system dismiss UI (PIN, pattern, password or biometric) be presented
     * over the lock screen. This activity is transparent, so it adds no content
     * above the keyguard while the owner decides.
     */
    private fun requestUnlock(keyguard: KeyguardManager) {
        val requested = runCatching {
            keyguard.requestDismissKeyguard(this, dismissCallback)
        }.isSuccess
        // A request that never reached the system can never succeed, so this
        // fails closed: nothing opens behind the lock screen.
        if (!requested) finish()
    }

    private val dismissCallback = object : KeyguardManager.KeyguardDismissCallback() {

        override fun onDismissSucceeded() {
            Log.i(TAG, "unlock succeeded")
            launchAndFinish()
        }

        override fun onDismissCancelled() {
            Log.i(TAG, "unlock cancelled")
            deferToUnlock()
        }

        override fun onDismissError() {
            Log.w(TAG, "unlock request failed")
            deferToUnlock()
        }
    }

    /**
     * The unlock prompt did not complete (on the owner's Samsung SystemUI
     * cancels it right away). Nothing opens over the lock screen; the press is
     * remembered so the owner's next unlock, within the window, opens the
     * dictation (see [AssistDictationHost.deferUntilUnlock]).
     */
    private fun deferToUnlock() {
        AssistDictationHost.deferUntilUnlock(System.currentTimeMillis())
        Toast.makeText(applicationContext, MESSAGE_UNLOCK_TO_DICTATE, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Runs the existing launch rule once the phone is usable, then goes away. */
    private fun launchAndFinish() {
        val launch = AssistLaunchRule.decide(
            accessibilityRunning = isAccessibilityControlEnabled(this),
            bubbleOverlayAttached = AssistDictationHost.isAttached(),
        )
        when (launch) {
            AssistLaunch.OpenBubbleDictation -> {
                // The host refuses when the service is still binding; the app
                // with the hint is a better answer than a silent no-op.
                if (!AssistDictationHost.requestDictation()) openAppWithHint()
            }
            AssistLaunch.OpenAppWithHint -> openAppWithHint()
        }
        finish()
    }

    /**
     * Opens Ajustes (the pending-navigation mechanism routes the running app
     * there) and explains why the side key did not dictate. The Toast belongs to
     * the application context, so it survives this activity's immediate finish.
     */
    private fun openAppWithHint() {
        AppNavigationIntents.request(Screen.SETTINGS)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "could not open the app: ${error.javaClass.simpleName}")
        }
        Toast.makeText(applicationContext, MESSAGE_ENABLE_ACCESSIBILITY, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AssistEntryActivity"

        /** Shown only when the accessibility service cannot draw the bubble. */
        /** Shown when the side key was pressed on the lock screen. */
        const val MESSAGE_UNLOCK_TO_DICTATE = "Desbloquea el teléfono para dictar."

        const val MESSAGE_ENABLE_ACCESSIBILITY =
            "Activa la accesibilidad de IDUPI para usar el asistente."
    }
}
