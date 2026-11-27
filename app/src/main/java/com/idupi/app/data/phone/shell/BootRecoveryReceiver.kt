package com.idupi.app.data.phone.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot trigger for the shell bridge recovery (phone-shell T6).
 *
 * Android drops Wireless debugging on reboot, so a paired phone needs adbd
 * turned back on and the helper restarted. The broadcast itself only hands off
 * to [PhoneShellService], which is a foreground service and therefore survives
 * long enough to wait for Wi-Fi and run the bootstrap. BOOT_COMPLETED arrives
 * after the owner unlocks, so the credential-protected pairing/key stores are
 * readable.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "recovery(boot): scheduling")
        runCatching { PhoneShellService.startBootRecovery(context) }
            .onFailure { error ->
                Log.w(TAG, "recovery(boot): could not start the service: ${error.javaClass.simpleName}")
            }
    }

    private companion object {
        const val TAG = "IdupiPhoneShell"
    }
}
