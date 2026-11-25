package com.idupi.app.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord

/** One sleep stage interval as read from Health Connect, platform-free. */
data class HealthStageSpan(
    val type: Int,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Translation of Health Connect's integer enums into the contract's text.
 *
 * These constants are `const val` in the library, so the JVM unit tests can
 * exercise the mapping without Health Connect installed. Only the values the
 * server stores as text are mapped: sleep stages to the four stage minutes and
 * exercise types to a short stable name.
 */
object HealthConnectMapping {

    /** Sums the recognized stages of [spans]; a generic "sleeping" span is ignored. */
    fun sleepStages(spans: List<HealthStageSpan>): HealthSleepStages? {
        var deep = 0
        var light = 0
        var rem = 0
        var awake = 0
        var recognized = 0

        for (span in spans) {
            val minutes = spanMinutes(span.startMillis, span.endMillis)
            if (minutes <= 0) continue
            when (span.type) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> { deep += minutes; recognized++ }
                SleepSessionRecord.STAGE_TYPE_LIGHT -> { light += minutes; recognized++ }
                SleepSessionRecord.STAGE_TYPE_REM -> { rem += minutes; recognized++ }
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> { awake += minutes; recognized++ }
                else -> Unit
            }
        }

        if (recognized == 0) return null
        return HealthSleepStages(
            deep = deep.coerceIn(0, 1440).takeIf { it > 0 },
            light = light.coerceIn(0, 1440).takeIf { it > 0 },
            rem = rem.coerceIn(0, 1440).takeIf { it > 0 },
            awake = awake.coerceIn(0, 1440).takeIf { it > 0 },
        )
    }

    /** Short stable workout name; unknown types collapse to `other`. */
    fun exerciseTypeLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking_stationary"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "weightlifting"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing_machine"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "stair_climbing_machine"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "stretching"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial_arts"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "rock_climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "snowboarding"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"
        else -> "other"
    }

    /** Whole minutes of one interval, floored and never negative. */
    fun spanMinutes(startMillis: Long, endMillis: Long): Int {
        val millis = (endMillis - startMillis).coerceAtLeast(0L)
        return (millis / 60_000L).toInt().coerceIn(0, 1440)
    }
}
