package com.idupi.app.data.phone.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After IDUPI is updated, the system may keep the accessibility grant recorded
 * while never rebinding the service (phone-shell T7b). This unexported receiver
 * waits the same grace as app start and runs the kick.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                delay(GRACE_MS)
                runCatching { AccessibilityKick.maybeKick(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L
    }
}
