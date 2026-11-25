package com.idupi.app.data.health

/** One usage transition (or unlock) used by the wellbeing aggregation. */
enum class WellbeingEventType { FOREGROUND, BACKGROUND, KEYGUARD_HIDDEN }

data class WellbeingEvent(
    val packageName: String,
    val type: WellbeingEventType,
    val timestampMillis: Long,
)

/** One owner-local day of digital wellbeing, ready for the wire. */
data class WellbeingDay(
    val date: String,
    val screenTimeMinutes: Int,
    val unlocks: Int,
    val nightScreenMinutes: Int,
    val topApps: List<HealthTopApp> = emptyList(),
)

/**
 * Digital wellbeing per owner-local day (phone-health T2), from the same
 * `UsageStatsManager` stream the phone status already reads.
 *
 * Rules pinned by the tests:
 * - Foreground time comes from `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` /
 *   `ACTIVITY_STOPPED`; intervals crossing midnight are split at the local day
 *   boundary instead of being filed whole under the start day.
 * - IDUPI itself and the launchers are excluded from the total and the
 *   ranking: they are not "use" from the owner's point of view.
 * - Screen time and night use are whole minutes, floored. Night is the
 *   00:00-06:00 slice of the same intervals, and never greater than the total.
 * - Unlocks are `KEYGUARD_HIDDEN` events; the system may report them with the
 *   `android` package, so only the event type matters.
 * - `topApps` is the five apps with the most minutes, ties broken by label,
 *   so equal durations stay deterministic.
 */
object WellbeingAggregation {

    const val TOP_APPS_LIMIT = 5
    const val NIGHT_END_MINUTE = 6 * 60

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun aggregate(
        events: List<WellbeingEvent>,
        dayKeys: List<String>,
        tzOffsetMinutes: Int,
        ownPackage: String?,
        excludedPackages: Set<String>,
        nowMillis: Long,
        labelFor: (String) -> String?,
    ): List<WellbeingDay> {
        val orderedDays = dayKeys.sorted()
        val dayBounds = orderedDays.associateWith { key ->
            HealthDays.dayStartMillis(key, tzOffsetMinutes) to
                HealthDays.dayEndMillis(key, tzOffsetMinutes)
        }
        val durations = orderedDays.associateWith { mutableMapOf<String, Long>() }
        val nightMillis = orderedDays.associateWith { 0L }.toMutableMap()
        val unlocks = orderedDays.associateWith { 0 }.toMutableMap()

        fun addInterval(packageName: String, fromMillis: Long, toMillis: Long) {
            if (packageName == ownPackage || packageName in excludedPackages) return
            if (toMillis <= fromMillis) return
            for (dayKey in orderedDays) {
                val (start, end) = dayBounds.getValue(dayKey)
                val overlapStart = maxOf(fromMillis, start)
                val overlapEnd = minOf(toMillis, end, nowMillis)
                if (overlapEnd <= overlapStart) continue
                val minutes = durations.getValue(dayKey)
                minutes[packageName] = (minutes[packageName] ?: 0L) + (overlapEnd - overlapStart)

                val nightStart = start
                val nightEnd = start + NIGHT_END_MINUTE * MILLIS_PER_MINUTE
                val nightOverlapStart = maxOf(overlapStart, nightStart)
                val nightOverlapEnd = minOf(overlapEnd, nightEnd)
                if (nightOverlapEnd > nightOverlapStart) {
                    nightMillis[dayKey] =
                        nightMillis.getValue(dayKey) + (nightOverlapEnd - nightOverlapStart)
                }
            }
        }

        val openSince = mutableMapOf<String, Long>()

        fun close(packageName: String, atMillis: Long) {
            val since = openSince.remove(packageName) ?: return
            addInterval(packageName, since, maxOf(since, atMillis))
        }

        for (event in events.sortedBy { it.timestampMillis }) {
            when (event.type) {
                WellbeingEventType.FOREGROUND -> {
                    val packageName = event.packageName
                    if (packageName.isBlank()) continue
                    close(packageName, event.timestampMillis)
                    openSince[packageName] = event.timestampMillis
                }
                WellbeingEventType.BACKGROUND -> {
                    if (event.packageName.isBlank()) continue
                    close(event.packageName, event.timestampMillis)
                }
                WellbeingEventType.KEYGUARD_HIDDEN -> {
                    val dayKey = HealthDays.ownerDayKey(event.timestampMillis, tzOffsetMinutes)
                    if (dayKey in unlocks) unlocks[dayKey] = unlocks.getValue(dayKey) + 1
                }
            }
        }
        for (packageName in openSince.keys.toList()) {
            close(packageName, nowMillis)
        }

        return orderedDays.map { dayKey ->
            val perPackage = durations.getValue(dayKey)
            val totalMinutes = (perPackage.values.sum() / MILLIS_PER_MINUTE)
                .toInt()
                .coerceIn(0, 1440)
            val nightMinutes = (nightMillis.getValue(dayKey) / MILLIS_PER_MINUTE)
                .toInt()
                .coerceIn(0, 1440)
                .coerceAtMost(totalMinutes)
            val topApps = perPackage.entries
                .mapNotNull { (packageName, millis) ->
                    val minutes = (millis / MILLIS_PER_MINUTE).toInt()
                    if (minutes < 1) return@mapNotNull null
                    HealthTopApp(
                        label = labelFor(packageName)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: packageName,
                        minutes = minutes,
                    )
                }
                .sortedWith(
                    compareByDescending<HealthTopApp> { it.minutes }.thenBy { it.label },
                )
                .take(TOP_APPS_LIMIT)

            WellbeingDay(
                date = dayKey,
                screenTimeMinutes = totalMinutes,
                unlocks = unlocks.getValue(dayKey),
                nightScreenMinutes = nightMinutes,
                topApps = topApps,
            )
        }
    }
}
