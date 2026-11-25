package com.idupi.app.data.health

import kotlin.math.round

/** Everything the phone knows about one owner-local day, before wire shaping. */
data class HealthDayInput(
    val date: String,
    val connect: HealthConnectAggregates = HealthConnectAggregates(),
    val details: HealthConnectDayDetails = HealthConnectDayDetails(),
    val wellbeing: WellbeingDay? = null,
)

/**
 * Builds the `POST /api/v1/health/samples` body (phone-health T2).
 *
 * One sample per source and day: `health_connect` carries the aggregates plus
 * the raw-record details, `wellbeing` carries screen time/unlocks/top apps.
 * Days without a single value are not sent, because an empty sample would
 * overwrite nothing but still cost a request slot. Every value goes through
 * [HealthMetricSanity] first: the server rejects the whole upload on one
 * out-of-range number, and dropping a bad value is better than losing six days.
 */
object HealthSampleBuilder {

    const val SOURCE_HEALTH_CONNECT = "health_connect"
    const val SOURCE_WELLBEING = "wellbeing"

    fun build(
        days: List<HealthDayInput>,
        installedApps: List<HealthInstalledApp>,
        tzOffsetMinutes: Int,
        capturedAtMillis: Long,
    ): HealthSamplesUpload {
        val capturedAt = HealthDays.isoInstant(capturedAtMillis)
        val samples = mutableListOf<HealthSample>()

        for (day in days.sortedBy { it.date }) {
            val connect = HealthMetricSanity.sanitize(connectMetrics(day.connect, day.details))
            if (connect != HealthMetrics()) {
                samples += HealthSample(
                    date = day.date,
                    source = SOURCE_HEALTH_CONNECT,
                    sourceApp = "",
                    capturedAt = capturedAt,
                    metrics = connect,
                )
            }
            day.wellbeing?.let { wellbeing ->
                val metrics = HealthMetricSanity.sanitize(wellbeingMetrics(wellbeing))
                if (metrics != HealthMetrics()) {
                    samples += HealthSample(
                        date = day.date,
                        source = SOURCE_WELLBEING,
                        sourceApp = "",
                        capturedAt = capturedAt,
                        metrics = metrics,
                    )
                }
            }
        }

        return HealthSamplesUpload(
            tzOffsetMinutes = tzOffsetMinutes,
            samples = samples,
            installedApps = installedApps,
        )
    }

    /** Health Connect aggregates + raw details, before range filtering. */
    fun connectMetrics(
        connect: HealthConnectAggregates,
        details: HealthConnectDayDetails,
    ): HealthMetrics {
        val sleep = details.sleep
        val workouts = details.workouts
            .take(HealthMetricSanity.MAX_WORKOUTS)
            .map { workout ->
                HealthWorkout(
                    type = workout.type,
                    minutes = workout.minutes,
                    start = HealthDays.isoInstant(workout.startMillis),
                    kcal = workout.kcal,
                )
            }
            .takeIf { it.isNotEmpty() }

        return HealthMetrics(
            steps = connect.steps,
            distanceM = round1(connect.distanceM),
            caloriesKcal = round1(connect.caloriesKcal),
            activeCaloriesKcal = round1(connect.activeCaloriesKcal),
            heartRateBpm = connect.heartRateAvg,
            heartRateMin = connect.heartRateMin,
            heartRateMax = connect.heartRateMax,
            restingHeartRateBpm = connect.restingHeartRateBpm,
            sleepMinutes = sleep?.totalMinutes,
            sleepStages = sleep?.stages,
            sleepStart = sleep?.let { HealthDays.isoInstant(it.startMillis) },
            sleepEnd = sleep?.let { HealthDays.isoInstant(it.endMillis) },
            exerciseMinutes = details.exerciseMinutes,
            workouts = workouts,
            weightKg = round1(details.weightKg),
            spo2Pct = round1(details.spo2Pct),
        )
    }

    /** Digital wellbeing metrics for one day. */
    fun wellbeingMetrics(day: WellbeingDay): HealthMetrics = HealthMetrics(
        screenTimeMinutes = day.screenTimeMinutes,
        unlocks = day.unlocks,
        nightScreenMinutes = day.nightScreenMinutes,
        topApps = day.topApps.take(HealthMetricSanity.MAX_TOP_APPS).takeIf { it.isNotEmpty() },
    )

    private fun round1(value: Double?): Double? =
        value?.let { round(it * 10.0) / 10.0 }
}
