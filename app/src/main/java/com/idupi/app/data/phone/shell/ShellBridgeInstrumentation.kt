package com.idupi.app.data.phone.shell

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
 * Shell bridge (phone-shell T1 spike, plan C).
 *
 * Started once from adb with
 * `am instrument -w -r com.idupi.app/.data.phone.shell.ShellBridgeInstrumentation`,
 * this instrumentation runs inside the IDUPI process (forked by zygote into the
 * app's own cgroup, not adbd's) and adopts the shell permission identity through
 * UiAutomation. The question this spike answers: does that identity survive
 * when `am` dies and adbd restarts (Wi-Fi off turns wireless debugging off and
 * restarts adbd on the owner's One UI 16 phone, killing every uid-2000 process)?
 *
 * The probe writes the current time to the global setting `idupi_shell_probe`
 * every 5 s, which needs WRITE_SECURE_SETTINGS (held by shell, not by the app).
 * Read it with `adb shell settings get global idupi_shell_probe` and
 * `adb logcat -s IdupiShellBridge`.
 *
 * Only adb (uid 2000) can start an instrumentation, so this adds no entry point
 * for other apps.
 */
class ShellBridgeInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val adopted = runCatching {
            // FLAG_DONT_USE_ACCESSIBILITY: never take over or suppress the
            // owner's accessibility services (IDUPI's phone control runs there).
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            automation.adoptShellPermissionIdentity()
        }
        val outcome = adopted.fold({ "adopted" }, { "adopt failed: $it" })
        Log.i(TAG, "$outcome uid=${Process.myUid()}")
        // Report to `am instrument -w` without finishing: finishing would end
        // the instrumentation and drop the delegated identity.
        sendStatus(0, Bundle().apply { putString(TAG, outcome) })

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
        const val PROBE_KEY = "idupi_shell_probe"
        const val PROBE_INTERVAL_MS = 5_000L
    }
}
