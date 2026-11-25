package com.idupi.app.domain.model

import com.idupi.app.data.health.HealthInstalledApp
import com.idupi.app.data.health.HealthMetrics
import kotlinx.serialization.Serializable

/**
 * Read models of the PC health routes (phone-health T2/T3).
 *
 * Field names are the wire contract in `idupi-server/docs/phone.md`; T3's
 * Salud screen consumes them, so they live in the domain just like the
 * calendar and inbox read models.
 */
@Serializable
data class HealthSummary(
    val rangeDays: Int = 7,
    val tzOffsetMinutes: Int? = null,
    val days: List<HealthDaySummary> = emptyList(),
    val latest: HealthDaySummary? = null,
)

/** One merged day: `sources` maps each metric key to the source that won it. */
@Serializable
data class HealthDaySummary(
    val date: String = "",
    val metrics: HealthMetrics = HealthMetrics(),
    val sources: Map<String, String> = emptyMap(),
    val capturedAt: String? = null,
)

/** `GET /api/v1/health/sources`: known sources plus the phone-reported apps. */
@Serializable
data class HealthSourcesResponse(
    val sources: List<HealthSourceInfo> = emptyList(),
    val installedApps: List<HealthInstalledApp> = emptyList(),
    val tzOffsetMinutes: Int? = null,
)

@Serializable
data class HealthSourceInfo(
    val id: String = "",
    val label: String = "",
    val lastCaptureAt: String? = null,
    val lastDate: String? = null,
)

/** One source's result inside a refresh job; never carries metric values. */
@Serializable
data class HealthRefreshResult(
    val source: String = "",
    val ok: Boolean = false,
    val date: String? = null,
    val sourceApp: String? = null,
    val metrics: Int? = null,
    val stored: Int? = null,
    val merged: Int? = null,
    val reason: String? = null,
    val detail: String? = null,
)

/** `POST /api/v1/health/refresh` and `GET /api/v1/health/refresh/{id}` job. */
@Serializable
data class HealthRefreshJob(
    val id: String = "",
    val status: String = "",
    val sources: List<String> = emptyList(),
    val reason: String? = null,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val results: List<HealthRefreshResult> = emptyList(),
    val error: String? = null,
)
