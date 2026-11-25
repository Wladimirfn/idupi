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
    /** Planned connectors that cannot capture yet (Huawei Health Kit). */
    val futureSources: List<HealthFutureSource> = emptyList(),
)

/** A source IDUPI will read once its registration exists; never a capture. */
@Serializable
data class HealthFutureSource(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val reason: String = "",
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

/**
 * Daily digest (phone-health T3): the agent's neutral-Spanish summary of today
 * against the week, plus highlights, kind non-medical recommendations and notes
 * that cross the numbers with the agenda.
 *
 * `status` is `ready`, `analyzing` or `failed`. `available` is false when the
 * window has no health or wellbeing data: the agent was not run.
 */
@Serializable
data class HealthDigest(
    val date: String = "",
    val status: String = "ready",
    val summary: String = "",
    val highlights: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val agendaNotes: List<String> = emptyList(),
    val error: String? = null,
    val updatedAt: String? = null,
    val available: Boolean = true,
) {
    val isAnalyzing: Boolean get() = status == "analyzing"
    val isFailed: Boolean get() = status == "failed"
    val hasContent: Boolean get() = summary.isNotBlank()
}

/** `GET /api/v1/health/adb`: PC-side hidden-reading state for Ajustes > SALUD. */
@Serializable
data class HealthAdbStatus(
    val adb: HealthAdbTool = HealthAdbTool(),
    val scrcpy: HealthScrcpyTool = HealthScrcpyTool(),
    val connected: Boolean = false,
    val serial: String? = null,
    val via: String? = null,
    val targets: List<HealthAdbTarget> = emptyList(),
)

@Serializable
data class HealthAdbTool(
    val path: String = "",
    val found: Boolean = false,
)

@Serializable
data class HealthScrcpyTool(
    val path: String = "",
    val found: Boolean = false,
    val version: String? = null,
)

@Serializable
data class HealthAdbTarget(
    val serial: String = "",
    val network: String = "",
    val model: String? = null,
    val lastConnectedAt: String? = null,
)
