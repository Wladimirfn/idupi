package com.idupi.app.data.phone

import java.util.Locale

/**
 * Storage sizes of one launchable app, read with `StorageStatsManager` on the
 * phone (phone-dashboard T1). The Dashboard reads them locally; since T3 the
 * status reporter also sends the 8 largest as the additive `largestApps`
 * field, so the PC agent can name what a cleanup would free.
 *
 * @property appBytes APK plus extracted code; @property dataBytes the app's
 *   data directory; @property cacheBytes what "Borrar caché" would free.
 */
data class PhoneAppStorage(
    val label: String,
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    /** Preinstalled by the system image; `shell scan` keeps only third-party apps. */
    val systemApp: Boolean = false,
) {
    /** Whole footprint the "Apps que más ocupan" row shows. */
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

/**
 * The "Apps que más ocupan" section: the eight largest apps plus the cache
 * total across every app that was read, not only the visible top.
 */
data class PhoneAppStorageSummary(
    val apps: List<PhoneAppStorage>,
    val totalCacheBytes: Long,
)

/**
 * Everything the Dashboard's phone page renders. It is read locally on the
 * phone; no server round trip is involved.
 *
 * Sections stay nullable for the same reason as [PhoneStatus]: an unreadable
 * sensor must not hide the rest of the page. [appStorage] is null when the
 * owner has not granted usage access (the page then offers the access action).
 */
data class PhoneDashboardSnapshot(
    val battery: PhoneBattery? = null,
    val signal: PhoneSignal? = null,
    val network: PhoneNetwork? = null,
    val memory: PhoneMemory? = null,
    val lowMemory: Boolean = false,
    val storage: PhoneStorage? = null,
    val topAppsToday: List<PhoneTopApp> = emptyList(),
    val usageAccessGranted: Boolean = false,
    val appStorage: PhoneAppStorageSummary? = null,
)

/**
 * Pure rules for the storage section, kept Android-free so the ordering and the
 * cap are unit-tested on the JVM (this project has no Robolectric).
 */
object PhoneAppStorageMapping {

    /** How many apps the phone page lists. */
    const val TOP_APPS_LIMIT = 8

    /**
     * Sorts by total size descending, breaks ties by package name so equal
     * sizes are deterministic, keeps the first [limit] rows and sums the cache
     * of every entry that was read.
     */
    fun summarize(
        apps: List<PhoneAppStorage>,
        limit: Int = TOP_APPS_LIMIT,
    ): PhoneAppStorageSummary = PhoneAppStorageSummary(
        apps = apps
            .sortedWith(
                compareByDescending<PhoneAppStorage> { it.totalBytes }
                    .thenBy { it.packageName }
            )
            .take(limit.coerceAtLeast(0)),
        totalCacheBytes = apps.sumOf { it.cacheBytes },
    )

    /**
     * Wire form of the largest apps for `PhoneStatus.largestApps` (T3): the
     * already-sorted top list, or null when there is nothing to report (no
     * usage access, an empty read or every app with zero bytes). Null keeps the
     * field out of the JSON instead of sending an empty array.
     */
    fun toLargestApps(summary: PhoneAppStorageSummary?): List<PhoneLargestApp>? =
        summary?.apps
            ?.map { app ->
                PhoneLargestApp(
                    label = app.label,
                    packageName = app.packageName,
                    totalBytes = app.totalBytes,
                    cacheBytes = app.cacheBytes,
                )
            }
            ?.takeIf { it.isNotEmpty() }
}

/**
 * The 60 s rule for the expensive per-app `StorageStatsManager` read. The page
 * polls RAM and battery every few seconds; the app list is reused until this
 * window expires or the owner refreshes explicitly.
 */
object AppStorageRefreshPolicy {

    const val REFRESH_INTERVAL_MS = 60_000L

    /** True when there is no previous read or it is at least one window old. */
    fun isDue(
        lastLoadedAtMillis: Long?,
        nowMillis: Long,
        intervalMillis: Long = REFRESH_INTERVAL_MS,
    ): Boolean {
        if (lastLoadedAtMillis == null) return true
        return nowMillis - lastLoadedAtMillis >= intervalMillis
    }
}

/**
 * Bytes in es-CL style for the owner-facing phone page: comma decimals and the
 * unit that keeps the number readable. One decimal from MB up, whole kB, raw
 * bytes below a kilobyte. Deterministic on the JVM (`Locale.US` first, then the
 * comma swap) so the tests pin the exact strings.
 */
fun formatPhoneBytes(bytes: Long?): String? {
    if (bytes == null || bytes < 0L) return null
    val value = bytes.toDouble()
    return when {
        value >= GIB -> oneDecimal(value / GIB) + " GB"
        value >= MIB -> oneDecimal(value / MIB) + " MB"
        value >= KIB -> oneDecimal(value / KIB) + " kB"
        else -> "$bytes B"
    }
}

/** "42 GB / 128 GB" when both ends are known, one end otherwise, null for none. */
fun formatPhoneUsageBytes(usedBytes: Long?, totalBytes: Long?): String? {
    val used = formatPhoneBytes(usedBytes)
    val total = formatPhoneBytes(totalBytes)
    return when {
        used != null && total != null -> "$used / $total"
        else -> used ?: total
    }
}

/** Used/total as 0..100; null when the total is missing or zero. */
fun phoneUsagePercent(usedBytes: Long?, totalBytes: Long?): Double? {
    if (usedBytes == null || totalBytes == null || totalBytes <= 0L) return null
    return (usedBytes.toDouble() / totalBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
}

/** Owner-facing label for `PhoneNetwork.kind`; null when unknown. */
fun networkKindLabel(kind: String?): String? = when (kind) {
    null, "" -> null
    NetworkMapping.KIND_WIFI -> "Wi-Fi"
    NetworkMapping.KIND_MOBILE -> "Datos móviles"
    NetworkMapping.KIND_NONE -> "Sin conexión"
    NetworkMapping.KIND_ETHERNET -> "Ethernet"
    NetworkMapping.KIND_VPN -> "VPN"
    NetworkMapping.KIND_BLUETOOTH -> "Bluetooth"
    else -> "Otra"
}

/** "3/4" bars of the strongest cell; null when the platform gave no level. */
fun signalLevelLabel(level: Int?): String? = level?.let { "$it/${SignalMapping.MAX_LEVEL}" }

/** Battery charging state as the owner reads it; null when unknown. */
fun batteryChargingLabel(charging: Boolean?): String? = when (charging) {
    null -> null
    true -> "Sí"
    false -> "No"
}

private const val KIB = 1024.0
private const val MIB = KIB * 1024.0
private const val GIB = MIB * 1024.0

private fun oneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value)
        .replace('.', ',')
        .removeSuffix(",0")
