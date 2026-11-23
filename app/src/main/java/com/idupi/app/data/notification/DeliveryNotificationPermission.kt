package com.idupi.app.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `POST_NOTIFICATIONS` became a runtime permission in Android 13 (API 33).
 * Before that, posting was allowed unless the user muted the app, so the only
 * thing to do is point them at the notification settings.
 */
const val POST_NOTIFICATIONS_API: Int = Build.VERSION_CODES.TIRAMISU

/** What the app should do about notification permission on this launch. */
data class NotificationPermissionPlan(
    /** Ask the system dialog once, on the first launch after a PC is configured. */
    val requestOnLaunch: Boolean,
    /** Show the Entregas banner while the ping cannot be posted. */
    val showBanner: Boolean
)

/**
 * Pure permission decision, shared by MainActivity (one-time request) and the
 * Entregas banner.
 *
 * The one-time request waits for a configured server: asking before the owner
 * has a PC to receive deliveries from is noise. The banner stays up whenever
 * the ping cannot be posted, including a granted permission with the channel
 * muted in system settings, where "Abrir ajustes" is the only fix.
 *
 * @param sdkInt `Build.VERSION.SDK_INT`.
 * @param permissionGranted `POST_NOTIFICATIONS` grant state (`true` below 33,
 *   where the permission does not exist).
 * @param notificationsEnabled `NotificationManagerCompat.areNotificationsEnabled()`.
 * @param hasConfiguredServer a connection profile with a host is stored.
 * @param alreadyAsked the one-time request was already issued on this install.
 */
fun notificationPermissionPlan(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    hasConfiguredServer: Boolean,
    alreadyAsked: Boolean
): NotificationPermissionPlan {
    val runtimePermissionMissing = sdkInt >= POST_NOTIFICATIONS_API && !permissionGranted
    return NotificationPermissionPlan(
        requestOnLaunch = runtimePermissionMissing && hasConfiguredServer && !alreadyAsked,
        showBanner = runtimePermissionMissing || !notificationsEnabled
    )
}

/** Reads the permission/notification gate from the platform, then applies [notificationPermissionPlan]. */
fun currentNotificationPermissionPlan(
    context: Context,
    hasConfiguredServer: Boolean,
    alreadyAsked: Boolean
): NotificationPermissionPlan = notificationPermissionPlan(
    sdkInt = Build.VERSION.SDK_INT,
    permissionGranted = hasPostNotificationsPermission(context),
    notificationsEnabled = DeliveryNotifications(context).canPost(),
    hasConfiguredServer = hasConfiguredServer,
    alreadyAsked = alreadyAsked
)

/** Raw grant state of `POST_NOTIFICATIONS`; `true` on platforms without it. */
fun hasPostNotificationsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < POST_NOTIFICATIONS_API) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/** Persists whether the one-time runtime request already happened. */
object NotificationPermissionState {
    private const val PREFS_NAME = "idupi_notification_permission"
    private const val KEY_ASKED = "post_notifications_asked"

    fun wasAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
