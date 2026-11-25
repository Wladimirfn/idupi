package com.idupi.app.data.health

import kotlinx.serialization.Serializable

/**
 * Phone -> PC upload body of `POST /api/v1/health/samples` (phone-health T2).
 *
 * The wire shape is the contract in `idupi-server/docs/phone.md` ("Sample wire
 * shape"). Every optional metric keeps a `null` default so kotlinx omits it on
 * encode: the server merges per day and an omitted key preserves the older
 * value, while an explicit null would be dropped by the server anway. Only the
 * metric keys listed here are valid; an unknown key rejects the whole upload
 * with a 400.
 *
 * @property tzOffsetMinutes the phone's offset at capture time, so day keys are
 *   the owner's local days on both sides.
 */
@Serializable
data class HealthSamplesUpload(
    val tzOffsetMinutes: Int,
    val samples: List<HealthSample>,
    val installedApps: List<HealthInstalledApp> = emptyList(),
)

/** One source's view of one owner-local day (`date` = `YYYY-MM-DD`). */
@Serializable
data class HealthSample(
    val date: String,
    val source: String,
    /** The server requires `metrics` to be an object, even when it is empty. */
    val metrics: HealthMetrics,
    val sourceApp: String? = null,
    val capturedAt: String? = null,
)

/**
 * Installed health app and how IDUPI can read it: `health_connect` when the
 * app writes to Health Connect, `hidden_display` when it needs the PC's ADB
 * reader, `not_supported` when there is no route yet.
 */
@Serializable
data class HealthInstalledApp(
    val packageName: String,
    val label: String,
    val route: String,
)

/** Metric bag: the exact keys the server accepts, all optional. */
@Serializable
data class HealthMetrics(
    val steps: Int? = null,
    val stepsGoal: Int? = null,
    val distanceM: Double? = null,
    val caloriesKcal: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val heartRateMin: Int? = null,
    val heartRateMax: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepGoalMinutes: Int? = null,
    val sleepStages: HealthSleepStages? = null,
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val exerciseMinutes: Int? = null,
    val exerciseGoalMinutes: Int? = null,
    val workouts: List<HealthWorkout>? = null,
    val weightKg: Double? = null,
    val weightGoalKg: Double? = null,
    val spo2Pct: Double? = null,
    val stress: Int? = null,
    val stressLabel: String? = null,
    val waterMl: Int? = null,
    val waterGoalMl: Int? = null,
    val wakeTime: String? = null,
    val wakeGoal: String? = null,
    val bedtimeGoal: String? = null,
    val screenTimeMinutes: Int? = null,
    val unlocks: Int? = null,
    val nightScreenMinutes: Int? = null,
    val topApps: List<HealthTopApp>? = null,
)

/** Sleep stage minutes; only the four stages the server knows. */
@Serializable
data class HealthSleepStages(
    val deep: Int? = null,
    val light: Int? = null,
    val rem: Int? = null,
    val awake: Int? = null,
)

/** One workout inside `metrics.workouts` (server caps at 50 entries). */
@Serializable
data class HealthWorkout(
    val type: String,
    val minutes: Int,
    val start: String? = null,
    val kcal: Double? = null,
)

/** One `metrics.topApps` entry: label + whole minutes, never a package name. */
@Serializable
data class HealthTopApp(
    val label: String,
    val minutes: Int,
)
