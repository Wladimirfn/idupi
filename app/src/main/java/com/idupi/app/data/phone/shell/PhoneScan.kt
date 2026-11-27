package com.idupi.app.data.phone.shell

import com.idupi.app.data.phone.formatPhoneBytes
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One third-party launchable app measured by `shell scan` (phone-shell T4b). */
data class ScannedApp(
    val packageName: String,
    val label: String,
    val cacheBytes: Long,
    val appBytes: Long,
    val dataBytes: Long,
    val lastUsedMillis: Long,
)

/** The scan's candidate lists, already ranked and capped. */
data class PhoneScanSelection(
    val apps: List<ScannedApp>,
    val unused: List<ScannedApp>,
    val heavyCache: List<ScannedApp>,
    val totalCacheBytes: Long,
)

/**
 * Pure scan ranking (phone-shell T4b): which third-party apps are unused and
 * which hold the most cache. The Android collector only reads sizes and usage;
 * these rules are unit-tested on the JVM.
 */
object PhoneScanRanking {

    const val UNUSED_DAYS = 30L
    const val UNUSED_LIMIT = 20
    const val HEAVY_CACHE_LIMIT = 10

    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    private const val OWN_PACKAGE = "com.idupi.app"

    fun select(apps: List<ScannedApp>, nowMillis: Long): PhoneScanSelection {
        val eligible = apps.filter { app ->
            app.packageName.isNotBlank() &&
                app.packageName != OWN_PACKAGE &&
                !ShellCommandRules.isProtected(app.packageName)
        }

        val unused = eligible
            .filter { app ->
                app.lastUsedMillis <= 0L ||
                    nowMillis - app.lastUsedMillis >= UNUSED_DAYS * DAY_MS
            }
            .sortedWith(
                compareBy<ScannedApp> { it.lastUsedMillis }
                    .thenByDescending { it.cacheBytes }
                    .thenBy { it.packageName }
            )
            .take(UNUSED_LIMIT)

        val heavyCache = eligible
            .filter { it.cacheBytes > 0L }
            .sortedWith(
                compareByDescending<ScannedApp> { it.cacheBytes }
                    .thenBy { it.packageName }
            )
            .take(HEAVY_CACHE_LIMIT)

        return PhoneScanSelection(
            apps = eligible.sortedBy { it.packageName },
            unused = unused,
            heavyCache = heavyCache,
            totalCacheBytes = eligible.sumOf { it.cacheBytes },
        )
    }
}

/**
 * Owner-facing scan report (phone-shell T4b): a short Spanish summary followed
 * by the compact JSON block the agent reads to build the one-approval plan.
 */
object PhoneScanReport {

    private const val JSON_FENCE = "```json"

    fun render(selection: PhoneScanSelection): String {
        val cache = formatPhoneBytes(selection.totalCacheBytes) ?: "0 B"
        val appNoun = if (selection.apps.size == 1) "app" else "apps"
        val summary = buildString {
            append("Analicé ${selection.apps.size} $appNoun de terceros: $cache de caché recuperable.")
            if (selection.unused.isNotEmpty()) {
                append(" ${selection.unused.size} sin usar hace ${PhoneScanRanking.UNUSED_DAYS}+ días.")
            }
            if (selection.heavyCache.isNotEmpty()) {
                append(" ${selection.heavyCache.size} con más caché.")
            }
        }
        return "$summary\n\n$JSON_FENCE\n${json(selection)}\n```"
    }

    fun json(selection: PhoneScanSelection): String = buildJsonObject {
        put("appCount", selection.apps.size)
        put("totalCacheBytes", selection.totalCacheBytes)
        put("unusedLimit", PhoneScanRanking.UNUSED_LIMIT)
        put("heavyCacheLimit", PhoneScanRanking.HEAVY_CACHE_LIMIT)
        put("unused", appsJson(selection.unused))
        put("heavyCache", appsJson(selection.heavyCache))
    }.toString()

    private fun appsJson(apps: List<ScannedApp>) = buildJsonArray {
        apps.forEach { app ->
            add(buildJsonObject {
                put("package", app.packageName)
                put("label", app.label)
                put("cacheBytes", app.cacheBytes)
                put("appBytes", app.appBytes)
                put("dataBytes", app.dataBytes)
                put("lastUsed", app.lastUsedMillis)
            })
        }
    }
}
