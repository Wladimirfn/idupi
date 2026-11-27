package com.idupi.shell

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell bridge instrumentation inside the helper package (phone-shell).
 *
 * Started from adb with
 * `am instrument -w -r [-e flags N] com.idupi.shell/.ShellBridgeInstrumentation`.
 * It runs in com.idupi.shell's own process, so the force-stop that
 * `am instrument` performs hits only the helper, never com.idupi.app.
 *
 * It adopts the shell permission identity with
 * [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] and publishes the
 * automation in [BridgeState]. The probe writes `idupi_shell_probe` every 5 s
 * so the app can verify the identity is alive.
 *
 * [kickAccessibility] is the T7b recovery: `Instrumentation.getUiAutomation`
 * reconnects its automation when the requested flags differ (AOSP disconnects
 * and reconnects), so requesting flags 0 forces the system to rebind
 * accessibility services, then the bridge restores the non-suppressing
 * connection and re-adopts the identity. The restore always runs in a
 * `finally`.
 */
class ShellBridgeInstrumentation : Instrumentation() {

    private var flags = DEFAULT_FLAGS
    private var automation: UiAutomation? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        flags = arguments?.getString(ARG_FLAGS)?.toIntOrNull() ?: DEFAULT_FLAGS
        start()
    }

    override fun onStart() {
        super.onStart()
        val adopted = runCatching {
            adopt(flags)
            "adopted"
        }.getOrElse { error ->
            BridgeState.markInactive()
            "adopt failed: ${error.javaClass.simpleName}"
        }
        Log.i(TAG, "$adopted flags=$flags uid=${Process.myUid()}")
        // Report to `am instrument -w` without finishing: finishing would end
        // the instrumentation and drop the delegated identity.
        sendStatus(0, Bundle().apply { putString(TAG, "$adopted flags=$flags") })
        probeLoop()
    }

    override fun onDestroy() {
        BridgeState.markInactive()
        super.onDestroy()
    }

    /**
     * Reconnects UiAutomation with suppression on, then restores the real
     * bridge connection. Runs from the provider's Binder thread; the final
     * non-suppressing connection with the identity adopted is attempted in a
     * `finally`, so an exception in the suppress step cannot leave accessibility
     * suppressed.
     */
    @Synchronized
    fun kickAccessibility(): Boolean {
        try {
            // Instrumentation disconnects the current automation and connects
            // the same instance with the new flags (AOSP getUiAutomation).
            getUiAutomation(0)
            Thread.sleep(KICK_SETTLE_MS)
        } catch (error: Throwable) {
            Log.w(TAG, "accessibility kick suppress failed: ${error.javaClass.simpleName}")
        } finally {
            val restored = runCatching { adopt(DEFAULT_FLAGS) }
            if (restored.isFailure) {
                BridgeState.markInactive()
                Log.w(
                    TAG,
                    "accessibility kick restore failed: " +
                        restored.exceptionOrNull()?.javaClass?.simpleName,
                )
            }
        }
        val active = BridgeState.isActive()
        Log.i(TAG, "accessibility kick done active=$active")
        return active
    }

    private fun adopt(flags: Int): UiAutomation {
        val ui = getUiAutomation(flags)
        ui.adoptShellPermissionIdentity()
        automation = ui
        BridgeState.attach(this, ui)
        return ui
    }

    private fun probeLoop() {
        while (true) {
            val now = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            val probe = runCatching {
                Settings.Global.putString(targetContext.contentResolver, PROBE_KEY, now)
            }
            Log.i(TAG, "probe $now ok=${probe.isSuccess} ${probe.exceptionOrNull() ?: ""}")
            try {
                Thread.sleep(PROBE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private companion object {
        const val TAG = "IdupiShellBridge"
        const val ARG_FLAGS = "flags"
        const val DEFAULT_FLAGS = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        const val PROBE_KEY = "idupi_shell_probe"
        const val PROBE_INTERVAL_MS = 5_000L
        const val KICK_SETTLE_MS = 500L
    }
}
