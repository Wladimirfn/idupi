package com.idupi.app.data.phone

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.time.Instant

/**
 * Thin Android glue for the status snapshot: reads the platform, never throws.
 *
 * Every section is collected inside its own `runCatching`; a missing permission,
 * an absent SIM or a ROM quirk leaves that field `null` and the rest of the
 * snapshot intact. The rules worth testing (usage aggregation, signal/network
 * mapping, battery math) live in the pure files next to this one, because this
 * project has no Robolectric.
 */
class PhoneStatusCollectors(private val context: Context) {

    /**
     * Reads every section and stamps the snapshot at [nowMillis].
     *
     * @param appVersion `BuildConfig.VERSION_NAME`; the PC prints it next to the
     *   sample to tell builds apart.
     */
    fun collect(appVersion: String, nowMillis: Long = System.currentTimeMillis()): PhoneStatus {
        val collectedAt = Instant.ofEpochMilli(nowMillis).toString()
        return try {
            val usage = collectUsage(nowMillis)
            PhoneStatus(
                battery = collectBattery(),
                signal = collectSignal(),
                network = collectNetwork(),
                memory = collectMemory(),
                storage = collectStorage(),
                foregroundApp = usage?.foregroundApp,
                topAppsToday = usage?.topApps,
                collectedAt = collectedAt,
                appVersion = appVersion,
            )
        } catch (e: Exception) {
            // A collector escaped its own guard; the snapshot is still worth
            // posting with whatever the timestamp alone guarantees.
            PhoneStatus(collectedAt = collectedAt, appVersion = appVersion)
        }
    }

    // --- Battery --------------------------------------------------------

    private fun collectBattery(): PhoneBattery? = runCatching {
        val battery = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return@runCatching null

        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperatureTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)

        PhoneBattery(
            percent = BatteryMapping.percent(level, scale),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureC = if (temperatureTenths == Int.MIN_VALUE) {
                null
            } else {
                BatteryMapping.temperatureCelsius(temperatureTenths)
            },
        )
    }.getOrNull()

    // --- Cellular signal ------------------------------------------------

    private fun collectSignal(): PhoneSignal? = runCatching {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@runCatching null

        // Operator name is readable without a permission.
        val operator = telephony.networkOperatorName?.takeIf { it.isNotBlank() }

        // getSignalStrength() may be refused without READ_PHONE_STATE; the
        // operator and network type are still worth reporting in that case.
        val strength = runCatching { telephony.signalStrength }.getOrNull()
        val level = strength?.let { SignalMapping.clampLevel(it.level) }
        val dbm = SignalMapping.validDbm(
            strength?.cellSignalStrengths?.maxByOrNull { it.level }?.dbm
        )
        val networkType = readDataNetworkType(telephony)

        if (operator == null && networkType == null && level == null && dbm == null) {
            null
        } else {
            PhoneSignal(
                level = level,
                dbm = dbm,
                operator = operator,
                networkType = networkType,
            )
        }
    }.getOrNull()

    /**
     * `getDataNetworkType()` requires READ_PHONE_STATE, so it is only read when
     * the owner (or an OEM image) already granted it. This app does not declare
     * or request that permission: on a stock install the field stays `null`,
     * which the contract allows and the PC simply does not print.
     */
    private fun readDataNetworkType(telephony: TelephonyManager): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching { SignalMapping.networkTypeName(telephony.dataNetworkType) }.getOrNull()
    }

    // --- Data network ---------------------------------------------------

    private fun collectNetwork(): PhoneNetwork? = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching null

        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val kind = NetworkMapping.kind(
            connected = capabilities != null,
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            bluetooth = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true,
        )
        // SSID and Wi-Fi RSSI need location access on modern Android; this app
        // never requests it, so both stay null (the contract makes them optional).
        PhoneNetwork(kind = kind)
    }.getOrNull()

    // --- Memory and storage ---------------------------------------------

    private fun collectMemory(): PhoneMemory? = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager ?: return@runCatching null
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        PhoneMemory(
            usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0L),
            totalBytes = info.totalMem,
        )
    }.getOrNull()

    private fun collectStorage(): PhoneStorage? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        if (totalBytes <= 0L) return@runCatching null
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        PhoneStorage(
            usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L),
            totalBytes = totalBytes,
        )
    }.getOrNull()

    // --- Usage stats and foreground app ---------------------------------

    /**
     * `null` when usage access is not granted (the PC then shows no usage
     * section at all, instead of a misleading "0 minutes").
     */
    private fun collectUsage(
        nowMillis: Long,
    ): UsageAggregation.Result? {
        if (!isUsageAccessGranted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val startMillis = UsageWindow.startOfDayMillis(nowMillis)
        val events = runCatching { readUsageEvents(manager, startMillis, nowMillis) }.getOrNull()
            ?: return null
        return UsageAggregation.aggregate(
            events = events,
            nowMillis = nowMillis,
            ownPackage = context.packageName,
            labelFor = ::appLabel,
        )
    }

    private fun readUsageEvents(
        manager: UsageStatsManager,
        startMillis: Long,
        endMillis: Long,
    ): List<UsageEvent> {
        val raw = manager.queryEvents(startMillis, endMillis) ?: return emptyList()
        val event = UsageEvents.Event()
        val events = mutableListOf<UsageEvent>()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            // MOVE_TO_FOREGROUND is the deprecated alias of ACTIVITY_RESUMED
            // and shares its value; ACTIVITY_STOPPED is how many apps end
            // their interval instead of ACTIVITY_PAUSED.
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventType.BACKGROUND
                else -> null
            } ?: continue
            val packageName = event.packageName ?: continue
            events += UsageEvent(
                packageName = packageName,
                type = type,
                timestampMillis = event.timeStamp,
            )
        }
        return events
    }

    private fun appLabel(packageName: String): String? = runCatching {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrNull()
}
