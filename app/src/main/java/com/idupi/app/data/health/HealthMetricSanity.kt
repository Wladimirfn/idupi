package com.idupi.app.data.health

/**
 * Sanity ranges of every metric accepted by `POST /api/v1/health/samples`
 * (phone-health T2), mirrored from the server's `METRIC_FIELDS`.
 *
 * The server rejects the WHOLE upload with a 400 when one value is outside its
 * range, so a phone-side out-of-range reading would cost the other six days
 * too. [sanitize] drops only the offending value instead: a missing metric is
 * honest, a fabricated or clamped one is not.
 */
object HealthMetricSanity {

    const val MAX_STEPS = 200_000
    const val MAX_TOP_APPS = 20
    const val MAX_WORKOUTS = 20
    const val MAX_LABEL_CHARS = 80
    const val MAX_WORKOUT_TYPE_CHARS = 60
    const val MAX_TEXT_CHARS = 40

    private fun intInRange(value: Int?, min: Int, max: Int): Int? =
        value?.takeIf { it in min..max }

    private fun doubleInRange(value: Double?, min: Double, max: Double): Double? =
        value?.takeIf { it.isFinite() && it in min..max }

    private fun text(value: String?, maxChars: Int): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(maxChars)

    /** Drops out-of-range values, empty nested bags and over-limit lists. */
    fun sanitize(metrics: HealthMetrics): HealthMetrics {
        val stages = metrics.sleepStages?.let { candidate ->
            val cleaned = HealthSleepStages(
                deep = intInRange(candidate.deep, 0, 1440),
                light = intInRange(candidate.light, 0, 1440),
                rem = intInRange(candidate.rem, 0, 1440),
                awake = intInRange(candidate.awake, 0, 1440),
            )
            cleaned.takeIf {
                it.deep != null || it.light != null || it.rem != null || it.awake != null
            }
        }
        val workouts = metrics.workouts
            ?.mapNotNull { candidate ->
                val type = text(candidate.type, MAX_WORKOUT_TYPE_CHARS) ?: return@mapNotNull null
                val minutes = intInRange(candidate.minutes, 0, 1440) ?: return@mapNotNull null
                candidate.copy(
                    type = type,
                    minutes = minutes,
                    kcal = doubleInRange(candidate.kcal, 0.0, 5000.0),
                )
            }
            ?.take(MAX_WORKOUTS)
            ?.takeIf { it.isNotEmpty() }
        val topApps = metrics.topApps
            ?.mapNotNull { candidate ->
                val label = text(candidate.label, MAX_LABEL_CHARS) ?: return@mapNotNull null
                val minutes = intInRange(candidate.minutes, 0, 1440) ?: return@mapNotNull null
                HealthTopApp(label = label, minutes = minutes)
            }
            ?.take(MAX_TOP_APPS)
            ?.takeIf { it.isNotEmpty() }

        return metrics.copy(
            steps = intInRange(metrics.steps, 0, MAX_STEPS),
            stepsGoal = intInRange(metrics.stepsGoal, 0, MAX_STEPS),
            distanceM = doubleInRange(metrics.distanceM, 0.0, 1_000_000.0),
            caloriesKcal = doubleInRange(metrics.caloriesKcal, 0.0, 50_000.0),
            activeCaloriesKcal = doubleInRange(metrics.activeCaloriesKcal, 0.0, 50_000.0),
            heartRateBpm = intInRange(metrics.heartRateBpm, 20, 250),
            restingHeartRateBpm = intInRange(metrics.restingHeartRateBpm, 20, 200),
            heartRateMin = intInRange(metrics.heartRateMin, 20, 250),
            heartRateMax = intInRange(metrics.heartRateMax, 20, 250),
            sleepMinutes = intInRange(metrics.sleepMinutes, 0, 1440),
            sleepGoalMinutes = intInRange(metrics.sleepGoalMinutes, 0, 1440),
            sleepStages = stages,
            sleepStart = text(metrics.sleepStart, MAX_TEXT_CHARS),
            sleepEnd = text(metrics.sleepEnd, MAX_TEXT_CHARS),
            exerciseMinutes = intInRange(metrics.exerciseMinutes, 0, 1440),
            exerciseGoalMinutes = intInRange(metrics.exerciseGoalMinutes, 0, 1440),
            workouts = workouts,
            weightKg = doubleInRange(metrics.weightKg, 20.0, 400.0),
            weightGoalKg = doubleInRange(metrics.weightGoalKg, 20.0, 400.0),
            spo2Pct = doubleInRange(metrics.spo2Pct, 50.0, 100.0),
            stress = intInRange(metrics.stress, 0, 100),
            stressLabel = text(metrics.stressLabel, MAX_TEXT_CHARS),
            waterMl = intInRange(metrics.waterMl, 0, 20_000),
            waterGoalMl = intInRange(metrics.waterGoalMl, 0, 20_000),
            wakeTime = text(metrics.wakeTime, 10),
            wakeGoal = text(metrics.wakeGoal, 10),
            bedtimeGoal = text(metrics.bedtimeGoal, 10),
            screenTimeMinutes = intInRange(metrics.screenTimeMinutes, 0, 1440),
            unlocks = intInRange(metrics.unlocks, 0, 5000),
            nightScreenMinutes = intInRange(metrics.nightScreenMinutes, 0, 1440),
            topApps = topApps,
        )
    }

    /** True when a sanitized bag carries at least one value to send. */
    fun hasAny(metrics: HealthMetrics): Boolean = sanitize(metrics) != HealthMetrics()
}
