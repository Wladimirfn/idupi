package com.idupi.app.domain.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One live sample from the PC (`GET /api/v1/system/metrics`).
 *
 * Every sensor is nullable on purpose: the owner's Ryzen 5600X exposes no
 * temperature or power through Windows WMI, and DDR4 has no temperature
 * sensor, so the server answers `null` instead of a made-up number. The UI
 * renders "— / no disponible" for those and shows [hints] when the server
 * knows how to enable the missing source.
 *
 * Defaults everywhere so a wire-shape addition cannot break deserialisation
 * (`ignoreUnknownKeys` + defaults), same contract as [DeliveryInfo].
 */
@Serializable
data class SystemMetrics(
    val sampledAt: String = "",
    val cpu: CpuMetrics = CpuMetrics(),
    val memory: MemoryMetrics = MemoryMetrics(),
    val gpus: List<GpuMetrics> = emptyList(),
    /**
     * Every fixed local disk. `mount` is the drive letter with a colon (for
     * example `C:`); sizes come from statfs on the server and are null when a
     * probe could not read that drive.
     */
    val disks: List<DiskMetrics> = emptyList(),
    val sources: MetricsSources = MetricsSources(),
    val hints: List<String> = emptyList(),
)

@Serializable
data class DiskMetrics(
    val mount: String = "",
    val label: String? = null,
    val fileSystem: String? = null,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val usedBytes: Long? = null,
    val usagePercent: Double? = null,
)

/** Storage card thresholds: below 75 % is healthy, from 90 % it is critical. */
enum class DiskUsageLevel { OK, WARN, CRITICAL, UNKNOWN }

/**
 * Maps the server's usage percentage to the storage card's bar colour. The
 * thresholds are the task's: <75 green, <90 amber, >=90 red; an unreadable
 * drive stays neutral instead of pretending to be healthy.
 */
fun diskUsageLevel(usagePercent: Double?): DiskUsageLevel = when {
    usagePercent == null || !usagePercent.isFinite() -> DiskUsageLevel.UNKNOWN
    usagePercent < 75.0 -> DiskUsageLevel.OK
    usagePercent < 90.0 -> DiskUsageLevel.WARN
    else -> DiskUsageLevel.CRITICAL
}

@Serializable
data class CpuMetrics(
    val name: String? = null,
    val usagePercent: Double? = null,
    val temperatureC: Double? = null,
    val powerW: Double? = null,
    val cores: Int? = null,
)

@Serializable
data class MemoryMetrics(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val usagePercent: Double? = null,
    val temperatureC: Double? = null,
)

@Serializable
data class GpuMetrics(
    val name: String? = null,
    val usagePercent: Double? = null,
    val temperatureC: Double? = null,
    val powerW: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
)

@Serializable
data class MetricsSources(
    val gpu: String? = null,
    val sensors: String? = null,
)

/**
 * Sum of every known watt reading, plus which side is missing one. The
 * dashboard labels the total as partial when the CPU sensor is absent (this
 * PC's normal case) instead of presenting a GPU-only sum as the whole draw.
 */
data class PowerSummary(
    val totalW: Double,
    val missingCpu: Boolean,
    val missingGpu: Boolean,
) {
    val partial: Boolean get() = missingCpu || missingGpu
}

/** Null when no power sensor answered at all. */
fun SystemMetrics.knownPowerSummary(): PowerSummary? {
    val cpuPower = cpu.powerW
    val gpuPowers = gpus.mapNotNull { it.powerW }
    val known = gpuPowers + listOfNotNull(cpuPower)
    if (known.isEmpty()) return null
    return PowerSummary(
        totalW = known.sum(),
        missingCpu = cpuPower == null,
        missingGpu = gpuPowers.size < gpus.size,
    )
}

/**
 * Deterministic `Locale.US` formatting so the same value renders identically
 * on every device and in JVM tests, matching [formatDeliverySize].
 */
fun formatMetricGigabytes(bytes: Long?): String? {
    if (bytes == null || bytes < 0L) return null
    val gigabytes = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f GB", gigabytes)
}

/** "9.6 / 15.9 GB" when both ends are known, one end otherwise, null if neither. */
fun formatMemoryUsageBytes(usedBytes: Long?, totalBytes: Long?): String? {
    val used = formatMetricGigabytes(usedBytes)
    val total = formatMetricGigabytes(totalBytes)
    return when {
        used != null && total != null -> "$used / $total"
        else -> used ?: total
    }
}

/** Whole percent for card headlines; null in, null out. */
fun formatMetricPercent(percent: Double?): String? {
    if (percent == null || !percent.isFinite()) return null
    return String.format(Locale.US, "%.0f %%", percent)
}

/** Whole degrees for card rows; null in, null out. */
fun formatMetricCelsius(celsius: Double?): String? {
    if (celsius == null || !celsius.isFinite()) return null
    return String.format(Locale.US, "%.0f °C", celsius)
}

/** Whole watts for card rows; null in, null out. */
fun formatMetricWatts(watts: Double?): String? {
    if (watts == null || !watts.isFinite()) return null
    return String.format(Locale.US, "%.0f W", watts)
}
