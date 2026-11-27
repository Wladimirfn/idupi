package com.idupi.app.data.phone.control

import android.content.Context
import android.util.Log
import com.idupi.app.data.phone.shell.AdbShellClient
import com.idupi.app.data.phone.shell.PhoneShellStore
import com.idupi.app.data.phone.shell.ShellBridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Which surface performed the kick. */
enum class AccessibilityKickPath { NONE, HELPER, ADB }

/** Pure decision rules for the T7b kick. */
object AccessibilityKickDecision {

    /** True only when the owner enabled accessibility but the service is not bound. */
    fun shouldKick(enabledInSettings: Boolean, connected: Boolean): Boolean =
        enabledInSettings && !connected

    /** Preferred surface: the live helper first, adb only when paired. */
    fun path(bridgeActive: Boolean, paired: Boolean): AccessibilityKickPath = when {
        bridgeActive -> AccessibilityKickPath.HELPER
        paired -> AccessibilityKickPath.ADB
        else -> AccessibilityKickPath.NONE
    }
}

/**
 * Accessibility rebind kick (phone-shell T7b).
 *
 * The owner's S24 stops rebinding IDUPI's accessibility service after an
 * `install -r` of IDUPI, after the helper bridge dies, and after toggling the
 * enabled-services setting. A fresh UiAutomation connect/disconnect clears it.
 * The helper can do that in-process (`kick-accessibility`); when the bridge is
 * not active but the phone is paired, the app can run `uiautomator dump` over
 * its own adb session.
 *
 * The kick runs after a short grace so the system has time to rebind after the
 * event that triggered it. Outcomes are logged under `IdupiPhoneShell`; no
 * owner data or secrets reach the log.
 */
object AccessibilityKick {

    private const val TAG = "IdupiPhoneShell"
    private const val GRACE_MS = 5_000L
    private const val ADB_KICK_COMMAND =
        "uiautomator dump /data/local/tmp/idupi-kick.xml; rm -f /data/local/tmp/idupi-kick.xml"
    private const val ADB_KICK_TIMEOUT_MS = 20_000L
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastAttemptAt = 0L

    /** Schedules one kick after the grace; duplicate requests are dropped. */
    fun request(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            delay(GRACE_MS)
            runCatching { maybeKick(appContext) }
        }
    }

    /**
     * Runs the kick now when the service is enabled but not bound. The helper
     * is preferred; when it is not active the adb path runs only if the phone
     * is already paired. Returns the path that ran (`NONE` when skipped).
     */
    suspend fun maybeKick(context: Context): AccessibilityKickPath {
        if (!running.compareAndSet(false, true)) return AccessibilityKickPath.NONE
        try {
            val enabled = isAccessibilityControlEnabled(context)
            if (!AccessibilityKickDecision.shouldKick(enabled, AccessibilityControlState.connected)) {
                Log.i(TAG, "accessibility kick skipped: enabled=$enabled connected=${AccessibilityControlState.connected}")
                return AccessibilityKickPath.NONE
            }
            val now = System.currentTimeMillis()
            if (now - lastAttemptAt < MIN_INTERVAL_MS) {
                Log.i(TAG, "accessibility kick skipped: recently attempted")
                return AccessibilityKickPath.NONE
            }
            lastAttemptAt = now

            val bridge = ShellBridgeClient.get(context)
            val active = bridge.status().getOrNull()?.active == true
            if (active) {
                val result = bridge.kickAccessibility()
                val ok = result.getOrNull()?.ok == true
                Log.i(TAG, "accessibility kick via helper ok=$ok")
                if (ok) return AccessibilityKickPath.HELPER
            }

            if (!PhoneShellStore(context).paired) {
                Log.i(TAG, "accessibility kick skipped: bridge inactive and phone not paired")
                return AccessibilityKickPath.NONE
            }
            return kickWithAdb(context)
        } finally {
            running.set(false)
        }
    }

    private suspend fun kickWithAdb(context: Context): AccessibilityKickPath {
        val adb = AdbShellClient.get(context)
        if (adb.connect().isFailure) {
            Log.i(TAG, "accessibility kick skipped: adb connect failed")
            return AccessibilityKickPath.NONE
        }
        val result = adb.exec(ADB_KICK_COMMAND, ADB_KICK_TIMEOUT_MS)
        val ok = result.isSuccess && result.getOrThrow().exitCode == 0
        Log.i(TAG, "accessibility kick via adb ok=$ok")
        return if (ok) AccessibilityKickPath.ADB else AccessibilityKickPath.NONE
    }
}
