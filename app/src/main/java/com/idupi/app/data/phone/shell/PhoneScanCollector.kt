package com.idupi.app.data.phone.shell

import android.app.usage.UsageStatsManager
import android.content.Context
import com.idupi.app.data.phone.AppStorageSource
import com.idupi.app.data.phone.AndroidAppStorageSource
import com.idupi.app.data.phone.isUsageAccessGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** `shell scan` needs usage access for `lastUsed`; the owner grants it in Ajustes. */
class PhoneScanUnavailableException(message: String) : Exception(message)

/** Last foreground use per package, from UsageStatsManager. */
fun interface UsageLastUsedSource {
    fun load(nowMillis: Long): Map<String, Long>
}

class AndroidUsageLastUsedSource(private val context: Context) : UsageLastUsedSource {

    override fun load(nowMillis: Long): Map<String, Long> = runCatching {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@runCatching emptyMap()
        val start = nowMillis - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS)
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            start,
            nowMillis,
        ) ?: return@runCatching emptyMap()
        stats
            .filter { it.lastTimeUsed > 0L }
            .groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.maxOf { it.lastTimeUsed } }
    }.getOrDefault(emptyMap())

    private companion object {
        /** Wider than the unused window so a 31-day-old use is still visible. */
        const val LOOKBACK_DAYS = 35L
    }
}

/**
 * Runs `shell scan` in the app (phone-shell T4b): reuses the dashboard's
 * per-app `StorageStatsManager` sizes, reads `lastUsed` from UsageStatsManager
 * and ranks the cleanup candidates. No helper and no approval involved.
 */
class PhoneScanCollector(
    private val context: Context,
    private val storageSource: AppStorageSource = AndroidAppStorageSource(context),
    private val usageSource: UsageLastUsedSource = AndroidUsageLastUsedSource(context),
) {

    suspend fun scan(nowMillis: Long = System.currentTimeMillis()): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isUsageAccessGranted(context)) {
                return@withContext Result.failure(
                    PhoneScanUnavailableException(
                        "Falta el acceso de uso. Actívalo en Ajustes > Acceso de uso."
                    )
                )
            }
            val lastUsed = usageSource.load(nowMillis)
            val apps = storageSource.load().mapNotNull { storage ->
                if (storage.packageName == context.packageName) return@mapNotNull null
                if (storage.systemApp) return@mapNotNull null
                if (ShellCommandRules.isProtected(storage.packageName)) return@mapNotNull null
                ScannedApp(
                    packageName = storage.packageName,
                    label = storage.label,
                    cacheBytes = storage.cacheBytes,
                    appBytes = storage.appBytes,
                    dataBytes = storage.dataBytes,
                    lastUsedMillis = lastUsed[storage.packageName] ?: 0L,
                )
            }
            val selection = PhoneScanRanking.select(apps, nowMillis)
            Result.success(PhoneScanReport.render(selection))
        }
}
