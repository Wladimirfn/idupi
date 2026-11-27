package com.idupi.app.data.phone

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import com.idupi.app.BuildConfig
import com.idupi.app.IduPiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Where the Dashboard's phone page reads its snapshot (phone-dashboard T1).
 *
 * The page must never need a server round trip: production answers from
 * [PhoneDashboardCollector] on the IO dispatcher; the ViewModel tests inject a
 * fake, the same seam [com.idupi.app.data.inbox.InstalledAppsSource] uses.
 */
fun interface PhoneDashboardSource {
    /**
     * @param forceAppStorage re-reads the per-app sizes even when the 60 s
     *   cache is still fresh; the owner's explicit refresh asks for that.
     */
    suspend fun collect(forceAppStorage: Boolean): PhoneDashboardSnapshot
}

/**
 * Per-app sizes behind a seam so [PhoneDashboardCollector] stays small and the
 * mapping stays testable; this is the only part that touches StorageStatsManager.
 */
fun interface AppStorageSource {
    fun load(): List<PhoneAppStorage>
}

/**
 * Process-wide production source. Built lazily so a JVM test that injects a
 * fake never loads the Android collector (`IduPiApp.instance`).
 */
object AndroidPhoneDashboardSource : PhoneDashboardSource {

    private val collector by lazy { PhoneDashboardCollector(IduPiApp.instance) }

    override suspend fun collect(forceAppStorage: Boolean): PhoneDashboardSnapshot =
        withContext(Dispatchers.IO) { collector.collect(forceAppStorage = forceAppStorage) }

    /**
     * The per-app summary for the status reporter (T3), from the same collector
     * and 60 s cache the phone page uses. Null without usage access.
     */
    fun appStorageSummary(): PhoneAppStorageSummary? = collector.appStorageSummary()
}

/**
 * Android glue for the phone page: reuses [PhoneStatusCollectors] for battery,
 * signal, network, RAM, storage and today's usage, and adds the per-app
 * `StorageStatsManager` sizes. The status reporter reuses the same cached
 * reader since phone-dashboard T3 to send the 8 largest apps.
 *
 * The per-app read walks every launchable app, so its result is cached for
 * [AppStorageRefreshPolicy.REFRESH_INTERVAL_MS]; the owner's refresh bypasses
 * the cache. Each package is read inside its own guard: a package uninstalled
 * mid-walk or a ROM that refuses its stats only removes that row.
 */
class PhoneDashboardCollector(
    private val context: Context,
    private val statusCollectors: PhoneStatusCollectors = PhoneStatusCollectors(context),
    private val appStorageSource: AppStorageSource = AndroidAppStorageSource(context),
) {

    private var cachedAppStorage: PhoneAppStorageSummary? = null
    private var appStorageReadAtMillis: Long? = null

    /**
     * Serialized so a poll tick and the owner's refresh never read the app
     * list twice at the same time; the first one fills the cache for the other.
     */
    @Synchronized
    fun collect(
        forceAppStorage: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): PhoneDashboardSnapshot {
        val status = statusCollectors.collect(BuildConfig.VERSION_NAME, nowMillis)
        val usageAccessGranted = isUsageAccessGranted(context)
        val appStorage = appStorageSummary(forceAppStorage, nowMillis, usageAccessGranted)

        return PhoneDashboardSnapshot(
            battery = status.battery,
            signal = status.signal,
            network = status.network,
            memory = status.memory,
            lowMemory = statusCollectors.isLowMemory(),
            storage = status.storage,
            topAppsToday = status.topAppsToday.orEmpty(),
            usageAccessGranted = usageAccessGranted,
            appStorage = appStorage,
        )
    }

    /**
     * The per-app sizes, cached for [AppStorageRefreshPolicy.REFRESH_INTERVAL_MS]
     * and shared with the status reporter (T3), which sends the largest eight.
     * Null when usage access is not granted: revoking it must hide stale sizes
     * instead of freezing the last list forever.
     */
    @Synchronized
    fun appStorageSummary(
        forceAppStorage: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        usageAccessGranted: Boolean = isUsageAccessGranted(context),
    ): PhoneAppStorageSummary? {
        if (!usageAccessGranted) {
            cachedAppStorage = null
            appStorageReadAtMillis = null
            return null
        }
        val due = forceAppStorage ||
            AppStorageRefreshPolicy.isDue(appStorageReadAtMillis, nowMillis)
        if (due) {
            PhoneAppStorageMapping.summarize(appStorageSource.load()).also {
                cachedAppStorage = it
                appStorageReadAtMillis = nowMillis
            }
        }
        return cachedAppStorage
    }
}

/**
 * Reads `StorageStatsManager` for every launchable app. It follows the
 * enumeration in [com.idupi.app.data.inbox.AndroidInstalledApps] (MAIN/LAUNCHER
 * through the manifest's `<queries>`), deduplicates packages that publish more
 * than one launcher activity and never asks for QUERY_ALL_PACKAGES.
 */
class AndroidAppStorageSource(private val context: Context) : AppStorageSource {

    override fun load(): List<PhoneAppStorage> = runCatching {
        val packageManager = context.packageManager
        val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE)
            as? StorageStatsManager ?: return@runCatching emptyList()

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)

        val seenPackages = HashSet<String>(resolved.size)
        val apps = mutableListOf<PhoneAppStorage>()
        for (info in resolved) {
            val packageName = info.activityInfo?.packageName ?: continue
            if (!seenPackages.add(packageName)) continue
            val stats = try {
                statsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    packageName,
                    Process.myUserHandle(),
                )
            } catch (missing: PackageManager.NameNotFoundException) {
                continue
            } catch (denied: SecurityException) {
                continue
            } catch (io: IOException) {
                continue
            }
            apps += PhoneAppStorage(
                label = info.loadLabel(packageManager).toString().ifBlank { packageName },
                packageName = packageName,
                appBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes,
            )
        }
        apps
    }.getOrElse { error ->
        // Personal sizes never reach the log: the failure kind only.
        Log.w(TAG, "app storage read failed: ${error.javaClass.simpleName}")
        emptyList()
    }

    private companion object {
        private const val TAG = "AndroidAppStorageSource"
    }
}
