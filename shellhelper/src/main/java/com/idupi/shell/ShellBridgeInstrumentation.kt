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
 * Shell bridge spike inside the helper package (phone-shell, helper design).
 *
 * Started from adb with
 * `am instrument -w -r [-e flags N] com.idupi.shell/.ShellBridgeInstrumentation`.
 * It runs in com.idupi.shell's own process, so the force-stop that
 * `am instrument` performs hits only the helper, never com.idupi.app.
 *
 * `flags` selects the UiAutomation connection mode under test (default 1):
 * 1 = FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 2 = FLAG_DONT_USE_ACCESSIBILITY
 * (what the in-app spike used; it left every accessibility service unbound),
 * 3 = both. The question: which mode keeps IDUPI's accessibility service bound
 * (`dumpsys accessibility` -> Bound services) while the shell identity holds.
 *
 * The probe writes the time to the global setting `idupi_shell_probe` every
 * 5 s (needs WRITE_SECURE_SETTINGS, held by shell, not by this app).
 */
class ShellBridgeInstrumentation : Instrumentation() {

    private var flags = DEFAULT_FLAGS

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        flags = arguments?.getString(ARG_FLAGS)?.toIntOrNull() ?: DEFAULT_FLAGS
        start()
    }

    override fun onStart() {
        super.onStart()
        val adopted = runCatching {
            getUiAutomation(flags).adoptShellPermissionIdentity()
        }
        val outcome = adopted.fold({ "adopted" }, { "adopt failed: $it" })
        Log.i(TAG, "$outcome flags=$flags uid=${Process.myUid()}")
        // Report to `am instrument -w` without finishing: finishing would end
        // the instrumentation and drop the delegated identity.
        sendStatus(0, Bundle().apply { putString(TAG, "$outcome flags=$flags") })

        while (true) {
            val now = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            val probe = runCatching {
                Settings.Global.putString(targetContext.contentResolver, PROBE_KEY, now)
            }
            Log.i(TAG, "probe $now ok=${probe.isSuccess} ${probe.exceptionOrNull() ?: ""}")
            Thread.sleep(PROBE_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "IdupiShellBridge"
        const val ARG_FLAGS = "flags"
        const val DEFAULT_FLAGS = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        const val PROBE_KEY = "idupi_shell_probe"
        const val PROBE_INTERVAL_MS = 5_000L
    }
}
