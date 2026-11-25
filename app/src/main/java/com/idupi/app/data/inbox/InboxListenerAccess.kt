package com.idupi.app.data.inbox

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * True when the owner granted this app notification-listener access in the
 * system settings. Without it the listener exists but receives nothing.
 *
 * T5 shows this state in Ajustes; the listener itself cannot ask for it.
 */
fun isInboxListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * The system screen where that access is granted. There is no runtime
 * permission dialog for notification access: the owner must toggle it there.
 */
fun notificationListenerSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
