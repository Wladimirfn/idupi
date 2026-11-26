package com.idupi.app.data.phone.bubble

import android.app.Activity
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
 */
class AssistEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        const val MESSAGE_ENABLE_ACCESSIBILITY =
            "Activa la accesibilidad de IDUPI para usar el asistente."
    }
}
