package com.idupi.app.data.inbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * True when the owner granted this app notification-listener access in the
 * system settings. Without it the listener exists but receives nothing.
 *
 * Ajustes shows this state as "Acceso a notificaciones". It is the permission,
 * not a live service; [InboxCaptureDiagnostics] is what tells the two apart.
 */
fun isInboxListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * The system screen where that access is granted. There is no runtime
 * permission dialog for notification access: the owner must toggle it there.
 */
fun notificationListenerSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

/**
 * Asks the system to rebind the listener (inbox capture fix T1).
 *
 * Having the permission string is not the same as having a live service: after
 * an APK update or an OEM battery kill Android unbinds the listener while
 * [isInboxListenerEnabled] still reports `true`. Only
 * [NotificationListenerService.requestRebind] recovers from that state, so the
 * app calls this whenever access is granted but diagnostics say the listener is
 * not connected.
 *
 * @return true when the request was handed to the system, false when the
 *   platform refused it.
 */
fun requestInboxListenerRebind(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    return try {
        NotificationListenerService.requestRebind(
            ComponentName(context, InboxNotificationListener::class.java)
        )
        true
    } catch (e: Exception) {
        Log.w("InboxListener", "could not request rebind: ${e.javaClass.simpleName}")
        false
    }
}
