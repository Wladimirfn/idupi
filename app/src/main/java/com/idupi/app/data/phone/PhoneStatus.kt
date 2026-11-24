package com.idupi.app.data.phone

import kotlinx.serialization.Serializable

/**
 * Status snapshot the phone posts to `POST /api/v1/phone/status`.
 *
 * The wire shape is the contract in `idupi-server/docs/phone.md` ("Status
 * snapshot contract"): the PC CLI prints these field names verbatim, so they
 * must not be renamed here. Every section is optional and stays `null` when its
 * collector cannot read it, so one missing permission never suppresses the
 * rest of the snapshot.
 *
 * @property collectedAt ISO-8601 instant the snapshot was taken (phone clock).
 * @property appVersion `BuildConfig.VERSION_NAME`, so the PC knows which build
 *   reported the sample.
 */
@Serializable
data class PhoneStatus(
    val battery: PhoneBattery? = null,
    val signal: PhoneSignal? = null,
    val network: PhoneNetwork? = null,
    val memory: PhoneMemory? = null,
    val storage: PhoneStorage? = null,
    val foregroundApp: PhoneAppRef? = null,
    val topAppsToday: List<PhoneTopApp>? = null,
    val collectedAt: String,
    val appVersion: String = "",
)

/** `battery` section: 0..100 percent, charging state and temperature in °C. */
@Serializable
data class PhoneBattery(
    val percent: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
)

/**
 * `signal` section: 0..4 bars, dBm of the strongest cell and the operator.
 * [networkType] is only filled when READ_PHONE_STATE is already granted.
 */
@Serializable
data class PhoneSignal(
    val level: Int? = null,
    val dbm: Int? = null,
    val operator: String? = null,
    val networkType: String? = null,
)

/**
 * `network` section: [kind] is a short word (`wifi`, `mobile`, `none`,
 * `ethernet`, `vpn`, `bluetooth`, `other`). [ssid]/[rssiDbm] stay `null`
 * because reading them needs location access this app does not request.
 */
@Serializable
data class PhoneNetwork(
    val kind: String,
    val ssid: String? = null,
    val rssiDbm: Int? = null,
)

@Serializable
data class PhoneMemory(
    val usedBytes: Long,
    val totalBytes: Long,
)

@Serializable
data class PhoneStorage(
    val usedBytes: Long,
    val totalBytes: Long,
)

/** Label + package of one app (foreground or top usage entry). */
@Serializable
data class PhoneAppRef(
    val label: String,
    val packageName: String,
)

/** One `topAppsToday` entry: whole minutes, at least 1 (server contract). */
@Serializable
data class PhoneTopApp(
    val label: String,
    val packageName: String,
    val minutes: Int,
)
