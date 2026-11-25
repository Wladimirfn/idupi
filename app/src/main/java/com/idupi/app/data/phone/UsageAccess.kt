package com.idupi.app.data.phone

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * True when the owner granted "usage access" (`PACKAGE_USAGE_STATS`) in the
 * system settings.
 *
 * It is a special access, not a runtime permission: there is no dialog to
 * request it, and without it `UsageStatsManager` returns no events at all. The
 * Ajustes section shows the state and opens the system screen; collectors use
 * this check to return `null` instead of an empty, misleading summary.
 */
fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = try {
        // unsafeCheckOpNoThrow: this only reads our own op, it never throws on
        // the API levels this app supports (minSdk 29) and needs no note to the
        // user. A thrown SecurityException from a ROM is treated as "denied".
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } catch (e: Exception) {
        return false
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** The system screen where usage access is granted (same pattern as notification access). */
fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
