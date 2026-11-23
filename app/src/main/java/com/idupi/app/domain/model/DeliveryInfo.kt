package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * One APK delivery registered on the PC (`/api/v1/deliveries`).
 *
 * A delivery is a server-side SNAPSHOT: the bytes behind [id] do not change
 * when the PC rebuilds the APK, so [sha256] is a safe integrity check for the
 * download. Every field except [id] defaults so a wire-shape addition on the
 * server cannot break deserialisation (`ignoreUnknownKeys` + defaults).
 *
 * [createdAt] is an ISO-8601 UTC instant (`Date#toISOString` on the server), so
 * plain string comparison preserves chronological order.
 */
@Serializable
data class DeliveryInfo(
    val id: String = "",
    val name: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val sourcePath: String = "",
    val createdAt: String? = null
)

/**
 * Human size for the deliveries list. Deterministic `Locale.US` formatting so
 * the same value renders identically on every device and in JVM tests.
 */
fun formatDeliverySize(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", megabytes)
}

/**
 * Short relative-age label for a delivery, in the app's neutral Spanish.
 * Returns an empty string when [createdAt] is missing or not an ISO instant.
 */
fun relativeDeliveryTime(
    createdAt: String?,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val instant = createdAt
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        ?: return ""
    val diffMillis = (nowMillis - instant.toEpochMilli()).coerceAtLeast(0L)
    val minutes = diffMillis / 60_000L
    if (minutes < 1L) return "ahora"
    if (minutes < 60L) return "hace $minutes min"
    val hours = minutes / 60L
    if (hours < 24L) return "hace $hours h"
    val days = hours / 24L
    return "hace $days d"
}
