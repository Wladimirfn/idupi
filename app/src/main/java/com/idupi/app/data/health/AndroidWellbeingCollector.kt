package com.idupi.app.data.health

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.idupi.app.data.phone.isUsageAccessGranted

/**
 * Reads `UsageStatsManager` events for the last [HealthDays.HISTORY_DAYS] days
 * and hands them to [WellbeingAggregation] (phone-health T2).
 *
 * `null` when the owner has not granted usage access: sending zeros would tell
 * the PC "the owner did not use the phone", which is a different statement from
 * "IDUPI cannot see the usage". IDUPI and the launchers are excluded from the
 * totals; labels come from PackageManager with the package name as fallback.
 */
class AndroidWellbeingCollector(private val context: Context) {

    fun collect(nowMillis: Long = System.currentTimeMillis()): List<WellbeingDay>? {
        if (!isUsageAccessGranted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null

        val tzOffsetMinutes = HealthDays.offsetMinutesAt(nowMillis)
        val today = HealthDays.ownerDayKey(nowMillis, tzOffsetMinutes)
        val dayKeys = HealthDays.lastNDays(today, HealthDays.HISTORY_DAYS)
        val windowStart = HealthDays.dayStartMillis(dayKeys.first(), tzOffsetMinutes)

        val events = runCatching { readEvents(manager, windowStart, nowMillis) }
            .getOrNull() ?: return null

        return WellbeingAggregation.aggregate(
            events = events,
            dayKeys = dayKeys,
            tzOffsetMinutes = tzOffsetMinutes,
            ownPackage = context.packageName,
            excludedPackages = launcherPackages(),
            nowMillis = nowMillis,
            labelFor = ::appLabel,
        )
    }

    private fun readEvents(
        manager: UsageStatsManager,
        startMillis: Long,
        endMillis: Long,
    ): List<WellbeingEvent> {
        val raw = manager.queryEvents(startMillis, endMillis) ?: return emptyList()
        val event = UsageEvents.Event()
        val events = mutableListOf<WellbeingEvent>()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> WellbeingEventType.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> WellbeingEventType.BACKGROUND
                UsageEvents.Event.KEYGUARD_HIDDEN -> WellbeingEventType.KEYGUARD_HIDDEN
                else -> null
            } ?: continue
            events += WellbeingEvent(
                packageName = event.packageName ?: "",
                type = type,
                timestampMillis = event.timeStamp,
            )
        }
        return events
    }

    /**
     * Home apps, read with a MAIN/HOME intent query declared in the manifest.
     * Package visibility would hide them otherwise on Android 11+.
     */
    private fun launcherPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packages = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toMutableSet()
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(packages::add)
        packages
    }.getOrDefault(emptySet())

    private fun appLabel(packageName: String): String? = runCatching {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrNull()
}
