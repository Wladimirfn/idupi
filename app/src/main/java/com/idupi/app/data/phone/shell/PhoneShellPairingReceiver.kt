package com.idupi.app.data.phone.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Receives the six-digit pairing code from the notification's RemoteInput
 * (phone-shell T3).
 *
 * Unexported and reached only through the PendingIntent attached to IDUPI's
 * own notification. The code is forwarded to the running service and is never
 * logged.
 */
class PhoneShellPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhoneShellService.ACTION_REPLY) return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(PhoneShellService.RESULT_KEY)
            ?.toString()
            ?.trim()
            ?: return
        if (code.isEmpty()) return

        PhoneShellService.deliverCode(context, code)
    }
}
