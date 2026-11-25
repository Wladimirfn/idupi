package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Health Connect day bucketing (phone-health T2). The rules that matter are
 * the ones a naive "start day" grouping gets wrong: sleep belongs to the wake
 * day, workouts to the start day, and point readings only keep the latest.
 */
class HealthConnectBucketingTest {

    private val tz = -180
    private val days = listOf("2026-11-22", "2026-11-23", "2026-11-24")

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `sleep belongs to the day it ends on and stages are summed`() {
        val readings = listOf(
            HealthReading(
                kind = HealthReadingKind.SLEEP,
                startMillis = ms("2026-11-24T02:30:00Z"), // 23:30 on the 23rd
                endMillis = ms("2026-11-24T07:10:00Z"), // 04:10 on the 24th
                stageSpans = listOf(
                    HealthStageSpan(5, ms("2026-11-24T02:30:00Z"), ms("2026-11-24T03:30:00Z")),
                    HealthStageSpan(4, ms("2026-11-24T03:30:00Z"), ms("2026-11-24T05:30:00Z")),
                    HealthStageSpan(1, ms("2026-11-24T05:30:00Z"), ms("2026-11-24T05:40:00Z")),
                ),
            ),
            HealthReading(
                kind = HealthReadingKind.SLEEP,
                startMillis = ms("2026-11-24T08:00:00Z"), // a separate nap after waking
                endMillis = ms("2026-11-24T08:30:00Z"),
                stageSpans = listOf(
                    HealthStageSpan(6, ms("2026-11-24T08:00:00Z"), ms("2026-11-24T08:20:00Z")),
                ),
            ),
        )

        val bucket = HealthConnectBucketing.bucket(readings, emptyList(), days, tz)
        val sleep = bucket.detailsByDay.getValue("2026-11-24").sleep!!

        assertEquals(ms("2026-11-24T02:30:00Z"), sleep.startMillis)
        assertEquals(ms("2026-11-24T08:30:00Z"), sleep.endMillis)
        assertEquals(360, sleep.totalMinutes)
        assertEquals(60, sleep.stages?.deep)
        assertEquals(120, sleep.stages?.light)
        assertEquals(10, sleep.stages?.awake)
        // The richest source owns the stages; the nap is not added on top of them.
        assertEquals(null, sleep.stages?.rem)
    }

    @Test
    fun `the same night reported by two writers is not counted twice`() {
        val readings = listOf(
            HealthReading(
                kind = HealthReadingKind.SLEEP,
                startMillis = ms("2026-11-24T02:30:00Z"),
                endMillis = ms("2026-11-24T07:10:00Z"),
                stageSpans = listOf(
                    HealthStageSpan(5, ms("2026-11-24T02:30:00Z"), ms("2026-11-24T03:30:00Z")),
                ),
            ),
            HealthReading(
                kind = HealthReadingKind.SLEEP,
                startMillis = ms("2026-11-24T02:35:00Z"),
                endMillis = ms("2026-11-24T07:05:00Z"),
                stageSpans = listOf(
                    HealthStageSpan(4, ms("2026-11-24T02:35:00Z"), ms("2026-11-24T07:05:00Z")),
                ),
            ),
        )

        val bucket = HealthConnectBucketing.bucket(readings, emptyList(), days, tz)
        val sleep = bucket.detailsByDay.getValue("2026-11-24").sleep!!

        assertEquals(280, sleep.totalMinutes)
        assertEquals(60, sleep.stages?.deep)
        assertEquals(null, sleep.stages?.light)
    }

    @Test
    fun `a sleep session ending outside the window is ignored`() {
        val readings = listOf(
            HealthReading(
                kind = HealthReadingKind.SLEEP,
                startMillis = ms("2026-11-21T18:00:00Z"),
                endMillis = ms("2026-11-22T01:00:00Z"), // ends at 22:00 on the 21st
            ),
        )

        val bucket = HealthConnectBucketing.bucket(readings, emptyList(), days, tz)

        assertTrue(bucket.detailsByDay.isEmpty())
    }

    @Test
    fun `workouts belong to the day they start on and sum the exercise minutes`() {
        val readings = listOf(
            HealthReading(
                kind = HealthReadingKind.EXERCISE,
                startMillis = ms("2026-11-24T02:00:00Z"), // 23:00 on the 23rd
                endMillis = ms("2026-11-24T02:45:00Z"),
                exerciseType = 56,
            ),
            HealthReading(
                kind = HealthReadingKind.EXERCISE,
                startMillis = ms("2026-11-24T12:00:00Z"), // 09:00 on the 24th
                endMillis = ms("2026-11-24T12:30:00Z"),
                exerciseType = 79,
            ),
        )

        val bucket = HealthConnectBucketing.bucket(readings, emptyList(), days, tz)

        val day23 = bucket.detailsByDay.getValue("2026-11-23")
        assertEquals(45, day23.exerciseMinutes)
        assertEquals("running", day23.workouts.single().type)

        val day24 = bucket.detailsByDay.getValue("2026-11-24")
        assertEquals(30, day24.exerciseMinutes)
        assertEquals("walking", day24.workouts.single().type)
        assertEquals(ms("2026-11-24T12:00:00Z"), day24.workouts.single().startMillis)
    }

    @Test
    fun `only the latest weight and spo2 in the window are kept`() {
        val readings = listOf(
            HealthReading(HealthReadingKind.WEIGHT, ms("2026-11-22T12:00:00Z"), value = 80.0),
            HealthReading(HealthReadingKind.WEIGHT, ms("2026-11-24T12:00:00Z"), value = 79.5),
            HealthReading(HealthReadingKind.WEIGHT, ms("2026-11-21T12:00:00Z"), value = 100.0),
            HealthReading(HealthReadingKind.SPO2, ms("2026-11-22T12:00:00Z"), value = 96.0),
            HealthReading(HealthReadingKind.SPO2, ms("2026-11-23T12:00:00Z"), value = 98.0),
        )

        val bucket = HealthConnectBucketing.bucket(readings, emptyList(), days, tz)

        assertEquals(79.5, bucket.detailsByDay.getValue("2026-11-24").weightKg!!, 0.0001)
        assertEquals(98.0, bucket.detailsByDay.getValue("2026-11-23").spo2Pct!!, 0.0001)
        assertNull(bucket.detailsByDay["2026-11-22"]?.weightKg)
    }

    @Test
    fun `origins are filtered to valid package names and kept once`() {
        val bucket = HealthConnectBucketing.bucket(
            readings = emptyList(),
            originPackages = listOf(
                "com.sec.android.app.shealth",
                "com.sec.android.app.shealth",
                "not a package",
                "com.mi.health",
            ),
            dayKeys = days,
            tzOffsetMinutes = tz,
        )

        assertEquals(
            setOf("com.sec.android.app.shealth", "com.mi.health"),
            bucket.originPackages,
        )
    }

    @Test
    fun `a day with no readings is not reported`() {
        val bucket = HealthConnectBucketing.bucket(emptyList(), emptyList(), days, tz)

        assertTrue(bucket.detailsByDay.isEmpty())
    }
}
