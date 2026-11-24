package com.idupi.app.data.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives the PackageInstaller session verdict for a commit issued by
 * [PackageInstallerApkInstaller].
 *
 * `STATUS_PENDING_USER_ACTION` carries the system's own confirmation activity
 * in [Intent.EXTRA_INTENT]; that activity is what actually installs the APK,
 * so it must be launched (with `FLAG_ACTIVITY_NEW_TASK` from a receiver
 * context). Every other status is final and is published to
 * [InstallResultStore] for the deliveries screen.
 *
 * Declared `exported=false` in the manifest: the system targets this receiver
 * by explicit component, so no other app needs to (or can) reach it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)
        val deliveryId = intent.getStringExtra(EXTRA_DELIVERY_ID)
        Log.d(TAG, "install result delivery=$deliveryId status=$status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirmation(context, intent)
            PackageInstaller.STATUS_SUCCESS -> publish(
                deliveryId,
                success = true,
                message = installStatusMessage(PackageInstaller.STATUS_SUCCESS)
            )
            else -> publish(
                deliveryId,
                success = false,
                message = installStatusMessage(status)
            )
        }
    }

    private fun launchConfirmation(context: Context, intent: Intent) {
        val confirmation = userActionIntent(intent)
        if (confirmation == null) {
            Log.w(TAG, "STATUS_PENDING_USER_ACTION without EXTRA_INTENT")
            publish(
                intent.getStringExtra(EXTRA_DELIVERY_ID),
                success = false,
                message = "El sistema no pudo abrir la confirmación de instalación"
            )
            return
        }
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirmation) }
            .onFailure {
                Log.w(TAG, "could not launch install confirmation", it)
                publish(
                    intent.getStringExtra(EXTRA_DELIVERY_ID),
                    success = false,
                    message = "El sistema no pudo abrir la confirmación de instalación"
                )
            }
    }

    private fun userActionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun publish(deliveryId: String?, success: Boolean, message: String) {
        InstallResultStore.shared.publish(
            InstallOutcome(deliveryId = deliveryId, success = success, message = message)
        )
    }

    companion object {
        private const val TAG = "InstallResultReceiver"

        const val ACTION_INSTALL_RESULT = "com.idupi.app.action.INSTALL_RESULT"
        const val EXTRA_DELIVERY_ID = "com.idupi.app.extra.DELIVERY_ID"

        /** Not a real PackageInstaller code: means the extra was missing. */
        private const val STATUS_UNKNOWN = Int.MIN_VALUE
    }
}
