package com.idupi.app.data.health

/** Which raw Health Connect record a [HealthReading] came from. */
enum class HealthReadingKind { SLEEP, EXERCISE, WEIGHT, SPO2 }

/**
 * One raw Health Connect reading, reduced to the fields the day bucketing
 * needs. The Android reader maps library records into this type so the rules
 * below stay pure and unit-tested without Health Connect installed.
 */
data class HealthReading(
    val kind: HealthReadingKind,
    val startMillis: Long,
    val endMillis: Long = startMillis,
    val value: Double? = null,
    val originPackage: String? = null,
    val stageSpans: List<HealthStageSpan> = emptyList(),
    val exerciseType: Int? = null,
)

/** Aggregates for one owner-local day, read through Health Connect's aggregate API. */
data class HealthConnectAggregates(
    val steps: Int? = null,
    val distanceM: Double? = null,
    val caloriesKcal: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val heartRateAvg: Int? = null,
    val heartRateMin: Int? = null,
    val heartRateMax: Int? = null,
    val restingHeartRateBpm: Int? = null,
)

/** One merged sleep session for a day (all sessions ending that day). */
data class HealthSleep(
    val startMillis: Long,
    val endMillis: Long,
    val totalMinutes: Int,
    val stages: HealthSleepStages? = null,
)

/** One workout reduced for the contract's `metrics.workouts` entry. */
data class HealthWorkoutReading(
    val type: String,
    val minutes: Int,
    val startMillis: Long,
    val kcal: Double? = null,
)

/** Raw-record details for one owner-local day. */
data class HealthConnectDayDetails(
    val sleep: HealthSleep? = null,
    val exerciseMinutes: Int? = null,
    val workouts: List<HealthWorkoutReading> = emptyList(),
    val weightKg: Double? = null,
    val spo2Pct: Double? = null,
)

/** Result of bucketing the raw window: details per day plus the found data origins. */
data class HealthConnectBucket(
    val detailsByDay: Map<String, HealthConnectDayDetails>,
    val originPackages: Set<String>,
)

/**
 * Buckets the raw Health Connect window into owner-local days (phone-health T2).
 *
 * Rules pinned by the tests:
 * - A sleep session belongs to the day it ENDS on: a night from 23:30 to 07:10
 *   is today's sleep, which is what the owner means by "last night".
 * - Several sessions ending the same day merge into one summary (earliest
 *   start, latest end, summed stages).
 * - Workouts belong to the day they START on; the total exercise minutes is
 *   the sum of those sessions, per the phone's own day.
 * - Weight and SpO2 are point readings: only the latest one in the window is
 *   kept, filed under its own day.
 * - Readings outside the requested window are ignored.
 * - Every data-origin package that wrote anything is collected, even when no
 *   metric survives the value ranges; that is the app-detection signal.
 */
object HealthConnectBucketing {

    fun bucket(
        readings: List<HealthReading>,
        originPackages: Collection<String>,
        dayKeys: List<String>,
        tzOffsetMinutes: Int,
    ): HealthConnectBucket {
        val days = dayKeys.toSet()
        val origins = originPackages
            .filter { HealthSourceDetection.isValidPackageName(it) }
            .toSet()

        val builder = mutableMapOf<String, MutableDay>()

        fun dayFor(dayKey: String): MutableDay? =
            if (dayKey in days) builder.getOrPut(dayKey) { MutableDay() } else null

        val sleepByDay = mutableMapOf<String, MutableList<HealthReading>>()
        for (reading in readings) {
            if (reading.kind != HealthReadingKind.SLEEP) continue
            val dayKey = HealthDays.ownerDayKey(reading.endMillis, tzOffsetMinutes)
            if (dayKey !in days) continue
            sleepByDay.getOrPut(dayKey) { mutableListOf() }.add(reading)
        }
        for ((dayKey, sessions) in sleepByDay) {
            val target = dayFor(dayKey) ?: continue
            target.sleep = mergeSleep(sessions.sortedBy { it.startMillis })
        }

        val workoutsByDay = mutableMapOf<String, MutableList<HealthWorkoutReading>>()
        for (reading in readings) {
            if (reading.kind != HealthReadingKind.EXERCISE) continue
            val dayKey = HealthDays.ownerDayKey(reading.startMillis, tzOffsetMinutes)
            if (dayKey !in days) continue
            val minutes = HealthConnectMapping.spanMinutes(reading.startMillis, reading.endMillis)
            val workout = HealthWorkoutReading(
                type = HealthConnectMapping.exerciseTypeLabel(reading.exerciseType ?: 0),
                minutes = minutes,
                startMillis = reading.startMillis,
                kcal = reading.value,
            )
            workoutsByDay.getOrPut(dayKey) { mutableListOf() }.add(workout)
        }
        for ((dayKey, workouts) in workoutsByDay) {
            val target = dayFor(dayKey) ?: continue
            target.workouts = workouts
                .sortedBy { it.startMillis }
                .take(HealthMetricSanity.MAX_WORKOUTS)
            target.exerciseMinutes =
                workouts.sumOf { it.minutes }.coerceIn(0, 1440)
        }

        latestPoint(readings, HealthReadingKind.WEIGHT, tzOffsetMinutes, days)?.let { (dayKey, value) ->
            dayFor(dayKey)?.weightKg = value
        }
        latestPoint(readings, HealthReadingKind.SPO2, tzOffsetMinutes, days)?.let { (dayKey, value) ->
            dayFor(dayKey)?.spo2Pct = value
        }

        return HealthConnectBucket(
            detailsByDay = builder
                .filterValues { it.hasAnyValue() }
                .mapValues { it.value.toDetails() },
            originPackages = origins,
        )
    }

    private fun mergeSleep(sessions: List<HealthReading>): HealthSleep {
        // Health Connect may return the same night from more than one writer
        // (Samsung Health and Mi Fitness both record sleep). Overlapping
        // sessions are one night: take the union interval and the stages of
        // the richest session instead of adding the same hours twice.
        val clusters = mutableListOf<MutableList<HealthReading>>()
        for (session in sessions.sortedBy { it.startMillis }) {
            val current = clusters.lastOrNull()
            if (current != null && isDuplicateOfCluster(current, session)) {
                current.add(session)
            } else {
                clusters.add(mutableListOf(session))
            }
        }

        var startMillis = Long.MAX_VALUE
        var endMillis = Long.MIN_VALUE
        var stages: HealthSleepStages? = null
        var richestStageScore = 0

        for (cluster in clusters) {
            val clusterStart = cluster.minOf { it.startMillis }
            val clusterEnd = cluster.maxOf { it.endMillis }
            startMillis = minOf(startMillis, clusterStart)
            endMillis = maxOf(endMillis, clusterEnd)

            for (session in cluster) {
                val candidate = HealthConnectMapping.sleepStages(session.stageSpans)
                val candidateScore = stageScore(
                    candidate,
                    HealthConnectMapping.spanMinutes(session.startMillis, session.endMillis),
                )
                if (candidateScore > richestStageScore) {
                    richestStageScore = candidateScore
                    stages = candidate
                }
            }
        }

        startMillis = if (startMillis == Long.MAX_VALUE) 0L else startMillis
        endMillis = if (endMillis == Long.MIN_VALUE) 0L else endMillis
        val totalMinutes = HealthConnectMapping.spanMinutes(startMillis, endMillis)

        return HealthSleep(
            startMillis = startMillis,
            endMillis = endMillis,
            totalMinutes = totalMinutes,
            stages = stages,
        )
    }

    /** True when [session] overlaps more than half of itself with [cluster]. */
    private fun isDuplicateOfCluster(
        cluster: List<HealthReading>,
        session: HealthReading,
    ): Boolean {
        val clusterStart = cluster.minOf { it.startMillis }
        val clusterEnd = cluster.maxOf { it.endMillis }
        val overlap = minOf(clusterEnd, session.endMillis) - maxOf(clusterStart, session.startMillis)
        if (overlap <= 0L) return false
        val sessionMillis = (session.endMillis - session.startMillis).coerceAtLeast(0L)
        return sessionMillis > 0L && overlap * 2 > sessionMillis
    }

    /**
     * Richest stage report wins: more distinct stages first (a detailed
     * deep/light/REM breakdown beats a single coarse stage), then the longer
     * session (the original recording beats a truncated duplicate).
     */
    private fun stageScore(stages: HealthSleepStages?, sessionMinutes: Int): Int {
        if (stages == null) return 0
        val distinct = listOfNotNull(stages.deep, stages.light, stages.rem, stages.awake).size
        return distinct * 1_000_000 + sessionMinutes
    }

    private fun latestPoint(
        readings: List<HealthReading>,
        kind: HealthReadingKind,
        tzOffsetMinutes: Int,
        days: Set<String>,
    ): Pair<String, Double>? {
        val latest = readings
            .filter { it.kind == kind && it.value != null }
            .filter { HealthDays.ownerDayKey(it.startMillis, tzOffsetMinutes) in days }
            .maxByOrNull { it.startMillis }
            ?: return null
        return HealthDays.ownerDayKey(latest.startMillis, tzOffsetMinutes) to latest.value!!
    }

    private class MutableDay {
        var sleep: HealthSleep? = null
        var exerciseMinutes: Int? = null
        var workouts: List<HealthWorkoutReading> = emptyList()
        var weightKg: Double? = null
        var spo2Pct: Double? = null

        fun hasAnyValue(): Boolean =
            sleep != null || exerciseMinutes != null || workouts.isNotEmpty() ||
                weightKg != null || spo2Pct != null

        fun toDetails(): HealthConnectDayDetails = HealthConnectDayDetails(
            sleep = sleep,
            exerciseMinutes = exerciseMinutes,
            workouts = workouts,
            weightKg = weightKg,
            spo2Pct = spo2Pct,
        )
    }
}
