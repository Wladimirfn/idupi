package com.idupi.app.data.phone

/**
 * Pure mappings from platform codes to the short values the status contract
 * uses. They deliberately avoid Android imports: reading the platform
 * constants from `android.telephony.TelephonyManager` would need a device or
 * Robolectric, and this project runs plain JVM unit tests.
 */
object SignalMapping {

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 4

    /** `SignalStrength.getLevel()` promises 0..4, but ROMs have shipped 5. */
    fun clampLevel(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /**
     * dBm of a cell signal, or null when the platform could not measure it.
     * `CellSignalStrength` answers [UNAVAILABLE_DBM] instead of null.
     */
    fun validDbm(raw: Int?): Int? = raw?.takeIf { it != UNAVAILABLE_DBM }

    /** `CellSignalStrength.UNAVAILABLE`; duplicated to keep this file Android-free. */
    const val UNAVAILABLE_DBM = Int.MAX_VALUE

    // Raw values from android.telephony.TelephonyManager (stable public API).
    private const val NETWORK_TYPE_UNKNOWN = 0
    private const val NETWORK_TYPE_GPRS = 1
    private const val NETWORK_TYPE_EDGE = 2
    private const val NETWORK_TYPE_UMTS = 3
    private const val NETWORK_TYPE_CDMA = 4
    private const val NETWORK_TYPE_EVDO_0 = 5
    private const val NETWORK_TYPE_EVDO_A = 6
    private const val NETWORK_TYPE_1XRTT = 7
    private const val NETWORK_TYPE_HSDPA = 8
    private const val NETWORK_TYPE_HSUPA = 9
    private const val NETWORK_TYPE_HSPA = 10
    private const val NETWORK_TYPE_IDEN = 11
    private const val NETWORK_TYPE_EVDO_B = 12
    private const val NETWORK_TYPE_LTE = 13
    private const val NETWORK_TYPE_EHRPD = 14
    private const val NETWORK_TYPE_HSPAP = 15
    private const val NETWORK_TYPE_GSM = 16
    private const val NETWORK_TYPE_TD_SCDMA = 17
    private const val NETWORK_TYPE_IWLAN = 18
    private const val NETWORK_TYPE_LTE_CA = 19
    private const val NETWORK_TYPE_NR = 20

    /** Short radio name for a `TelephonyManager` network type; null when unknown. */
    fun networkTypeName(rawType: Int): String? = when (rawType) {
        NETWORK_TYPE_UNKNOWN -> null
        NETWORK_TYPE_GPRS -> "GPRS"
        NETWORK_TYPE_EDGE -> "EDGE"
        NETWORK_TYPE_UMTS -> "UMTS"
        NETWORK_TYPE_CDMA -> "CDMA"
        NETWORK_TYPE_EVDO_0 -> "EVDO 0"
        NETWORK_TYPE_EVDO_A -> "EVDO A"
        NETWORK_TYPE_1XRTT -> "1xRTT"
        NETWORK_TYPE_HSDPA -> "HSDPA"
        NETWORK_TYPE_HSUPA -> "HSUPA"
        NETWORK_TYPE_HSPA -> "HSPA"
        NETWORK_TYPE_IDEN -> "iDEN"
        NETWORK_TYPE_EVDO_B -> "EVDO B"
        NETWORK_TYPE_LTE -> "LTE"
        NETWORK_TYPE_EHRPD -> "eHRPD"
        NETWORK_TYPE_HSPAP -> "HSPA+"
        NETWORK_TYPE_GSM -> "GSM"
        NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        NETWORK_TYPE_IWLAN -> "IWLAN"
        NETWORK_TYPE_LTE_CA -> "LTE-CA"
        NETWORK_TYPE_NR -> "NR"
        else -> null
    }
}

/** Maps the active `NetworkCapabilities` transports to `network.kind`. */
object NetworkMapping {

    const val KIND_WIFI = "wifi"
    const val KIND_MOBILE = "mobile"
    const val KIND_NONE = "none"
    const val KIND_ETHERNET = "ethernet"
    const val KIND_VPN = "vpn"
    const val KIND_BLUETOOTH = "bluetooth"
    const val KIND_OTHER = "other"

    /**
     * Priority follows what the owner cares about: a Wi-Fi connection over a
     * cellular one, cellular over a tether. `connected` is false when there is
     * no active network at all.
     */
    fun kind(
        connected: Boolean,
        wifi: Boolean = false,
        mobile: Boolean = false,
        ethernet: Boolean = false,
        vpn: Boolean = false,
        bluetooth: Boolean = false,
    ): String = when {
        !connected -> KIND_NONE
        wifi -> KIND_WIFI
        vpn -> KIND_VPN
        mobile -> KIND_MOBILE
        ethernet -> KIND_ETHERNET
        bluetooth -> KIND_BLUETOOTH
        else -> KIND_OTHER
    }
}

/** Battery sticky-intent math, separated so it is unit-tested. */
object BatteryMapping {

    /** `EXTRA_LEVEL`/`EXTRA_SCALE` to 0..100; null when the intent is incomplete. */
    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    /** `EXTRA_TEMPERATURE` is tenths of a degree Celsius. */
    fun temperatureCelsius(tenths: Int): Double = tenths / 10.0
}

/** Local-midnight boundary for the usage query, shared by collector and tests. */
object UsageWindow {

    /** Start of the current local day, in epoch milliseconds. */
    fun startOfDayMillis(
        epochMillis: Long,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}
