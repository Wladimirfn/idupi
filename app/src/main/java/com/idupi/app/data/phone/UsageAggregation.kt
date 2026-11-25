package com.idupi.app.data.phone

/**
 * One foreground/background transition read from `UsageEvents`, reduced to the
 * only two kinds [UsageAggregation] cares about.
 *
 * The values are our own enum on purpose: the JVM tests run without Android, so
 * the platform event codes are mapped by the collector, not here.
 */
enum class UsageEventType { FOREGROUND, BACKGROUND }

data class UsageEvent(
    val packageName: String,
    val type: UsageEventType,
    val timestampMillis: Long,
)

/**
 * Turns the raw usage transitions since local midnight into the contract's
 * `topAppsToday` and `foregroundApp`. Pure: labels come from an injected
 * resolver, so the rules are unit-tested on the JVM.
 *
 * Rules pinned by the tests:
 * - A foreground event opens an interval; a background event (or another app
 *   coming to the foreground) closes it. Only one app is foreground at a time,
 *   which is what `UsageEvents` reflects.
 * - An interval still open at [nowMillis] is counted up to now.
 * - Durations are summed per package and floored to whole minutes; apps below
 *   one minute never appear (server contract: top 10, >= 1 min).
 * - The caller's own package is skipped in both the ranking and the foreground
 *   app: reporting IDUPI itself would be noise on the PC.
 * - Ranking is minutes descending, then package name ascending so equal
 *   durations are deterministic.
 */
object UsageAggregation {

    /** The PC shows at most ten apps for today. */
    const val TOP_APPS_LIMIT = 10

    private const val MILLIS_PER_MINUTE = 60_000L

    data class Result(
        val topApps: List<PhoneTopApp>,
        val foregroundApp: PhoneAppRef?,
    )

    /**
     * @param events raw transitions, any order; [UsageEvent.packageName] blank
     *   entries are ignored.
     * @param nowMillis end of the reporting window (and of an open interval).
     * @param ownPackage the reporting app, excluded from the result.
     * @param labelFor resolves a display label; a null/blank answer falls back
     *   to the package name so the PC always shows something usable.
     */
    fun aggregate(
        events: List<UsageEvent>,
        nowMillis: Long,
        ownPackage: String?,
        labelFor: (String) -> String?,
    ): Result {
        val relevant = events
            .filter { it.packageName.isNotBlank() && it.packageName != ownPackage }
            .sortedBy { it.timestampMillis }

        val foregroundPackage = relevant
            .lastOrNull { it.type == UsageEventType.FOREGROUND }
            ?.packageName

        val durations = mutableMapOf<String, Long>()
        var openPackage: String? = null
        var openSince = 0L

        fun closeOpen(atMillis: Long) {
            val pkg = openPackage ?: return
            val closedAt = atMillis.coerceAtLeast(openSince)
            durations[pkg] = (durations[pkg] ?: 0L) + (closedAt - openSince)
            openPackage = null
        }

        for (event in relevant) {
            when (event.type) {
                UsageEventType.FOREGROUND -> {
                    if (openPackage == event.packageName) continue
                    closeOpen(event.timestampMillis)
                    openPackage = event.packageName
                    openSince = event.timestampMillis
                }
                UsageEventType.BACKGROUND -> {
                    if (openPackage == event.packageName) closeOpen(event.timestampMillis)
                }
            }
        }
        closeOpen(nowMillis)

        val topApps = durations.entries
            .mapNotNull { (packageName, totalMillis) ->
                val minutes = totalMillis / MILLIS_PER_MINUTE
                if (minutes < 1) return@mapNotNull null
                PhoneTopApp(
                    label = labelFor(packageName)?.takeIf { it.isNotBlank() } ?: packageName,
                    packageName = packageName,
                    minutes = minutes.toInt(),
                )
            }
            .sortedWith(compareByDescending<PhoneTopApp> { it.minutes }.thenBy { it.packageName })
            .take(TOP_APPS_LIMIT)

        return Result(
            topApps = topApps,
            foregroundApp = foregroundPackage?.let { packageName ->
                PhoneAppRef(
                    label = labelFor(packageName)?.takeIf { it.isNotBlank() } ?: packageName,
                    packageName = packageName,
                )
            },
        )
    }
}
