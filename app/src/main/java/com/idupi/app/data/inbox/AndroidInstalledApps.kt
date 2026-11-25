package com.idupi.app.data.inbox

import android.content.Intent
import com.idupi.app.IduPiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where Ajustes gets the installed-app list (phone-inbox T3). The ViewModel
 * depends on this seam, so its state and follow toggles are unit-tested with a
 * fake inventory instead of a device.
 */
fun interface InstalledAppsSource {
    suspend fun load(): InstalledAppInventory
}

/**
 * Android adapter for [InstalledApps]: enumerates launchable activities through
 * the manifest's MAIN/LAUNCHER `<queries>` declaration and never asks for
 * QUERY_ALL_PACKAGES. `queryIntentActivities` and the label lookup are binder
 * calls, so they run on [Dispatchers.IO].
 */
object AndroidInstalledApps : InstalledAppsSource {

    override suspend fun load(): InstalledAppInventory = withContext(Dispatchers.IO) {
        val context = IduPiApp.instance
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)
        val launchable = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            packageName to info.loadLabel(packageManager).toString()
        }
        InstalledApps.inventory(launchable, ownPackageName = context.packageName)
    }
}
