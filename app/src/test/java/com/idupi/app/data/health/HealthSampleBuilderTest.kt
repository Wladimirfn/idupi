package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Sample shaping (phone-health T2): only non-empty days are sent, every value
 * goes through the server ranges, and nulls stay omitted instead of becoming
 * explicit nulls the PC would have to special-case.
 */
class HealthSampleBuilderTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun fullDay() = HealthDayInput(
        date = "2026-11-24",
        connect = HealthConnectAggregates(
            steps = 8123,
            distanceM = 5432.16,
            caloriesKcal = 2100.44,
            activeCaloriesKcal = 400.0,
            heartRateAvg = 72,
            heartRateMin = 55,
            heartRateMax = 140,
            restingHeartRateBpm = 58,
        ),
        details = HealthConnectDayDetails(
            sleep = HealthSleep(
                startMillis = ms("2026-11-24T02:30:00Z"),
                endMillis = ms("2026-11-24T07:10:00Z"),
                totalMinutes = 280,
                stages = HealthSleepStages(deep = 60, light = 120, rem = 20, awake = 10),
            ),
            exerciseMinutes = 45,
            workouts = listOf(
                HealthWorkoutReading(
                    type = "running",
                    minutes = 45,
                    startMillis = ms("2026-11-24T12:00:00Z"),
                    kcal = 380.4,
                ),
            ),
            weightKg = 79.549,
            spo2Pct = 97.2,
        ),
        wellbeing = WellbeingDay(
            date = "2026-11-24",
            screenTimeMinutes = 120,
            unlocks = 42,
            nightScreenMinutes = 10,
            topApps = listOf(HealthTopApp("WhatsApp", 40)),
        ),
    )

    @Test
    fun `a full day produces one health connect and one wellbeing sample`() {
        val upload = HealthSampleBuilder.build(
            days = listOf(fullDay()),
            installedApps = listOf(
                HealthInstalledApp("com.sec.android.app.shealth", "Samsung Health", "health_connect"),
            ),
            tzOffsetMinutes = -180,
            capturedAtMillis = ms("2026-11-24T15:00:00Z"),
        )

        assertEquals(-180, upload.tzOffsetMinutes)
        assertEquals(2, upload.samples.size)
        assertEquals("com.sec.android.app.shealth", upload.installedApps.single().packageName)

        val connect = upload.samples.first { it.source == "health_connect" }
        assertEquals("2026-11-24", connect.date)
        assertEquals("", connect.sourceApp)
        assertEquals("2026-11-24T15:00:00Z", connect.capturedAt)
        assertEquals(8123, connect.metrics.steps)
        assertEquals(5432.2, connect.metrics.distanceM!!, 0.0001)
        assertEquals(2100.4, connect.metrics.caloriesKcal!!, 0.0001)
        assertEquals(72, connect.metrics.heartRateBpm)
        assertEquals(280, connect.metrics.sleepMinutes)
        assertEquals(60, connect.metrics.sleepStages?.deep)
        assertEquals("2026-11-24T02:30:00Z", connect.metrics.sleepStart)
        assertEquals(45, connect.metrics.exerciseMinutes)
        assertEquals("running", connect.metrics.workouts?.single()?.type)
        assertEquals(380.4, connect.metrics.workouts?.single()?.kcal!!, 0.0001)
        assertEquals(79.5, connect.metrics.weightKg!!, 0.0001)
        assertEquals(97.2, connect.metrics.spo2Pct!!, 0.0001)

        val wellbeing = upload.samples.first { it.source == "wellbeing" }
        assertEquals(120, wellbeing.metrics.screenTimeMinutes)
        assertEquals(42, wellbeing.metrics.unlocks)
        assertEquals(10, wellbeing.metrics.nightScreenMinutes)
        assertEquals("WhatsApp", wellbeing.metrics.topApps?.single()?.label)
    }

    @Test
    fun `a day without a single value is not sent`() {
        val upload = HealthSampleBuilder.build(
            days = listOf(
                HealthDayInput("2026-11-23"),
                HealthDayInput(
                    "2026-11-24",
                    details = HealthConnectDayDetails(exerciseMinutes = 0),
                ),
            ),
            installedApps = emptyList(),
            tzOffsetMinutes = -180,
            capturedAtMillis = ms("2026-11-24T15:00:00Z"),
        )

        assertEquals(1, upload.samples.size)
        assertEquals("2026-11-24", upload.samples.single().date)
        assertEquals(0, upload.samples.single().metrics.exerciseMinutes)
    }

    @Test
    fun `out-of-range values are dropped instead of rejecting the whole upload`() {
        val day = HealthDayInput(
            date = "2026-11-24",
            connect = HealthConnectAggregates(
                steps = 999_999,
                distanceM = -5.0,
                heartRateAvg = 10,
                restingHeartRateBpm = 500,
            ),
            details = HealthConnectDayDetails(
                weightKg = 79.5,
                spo2Pct = 40.0,
                sleep = HealthSleep(
                    startMillis = 0,
                    endMillis = 0,
                    totalMinutes = 2000,
                    stages = HealthSleepStages(deep = 2000, light = 100),
                ),
            ),
        )

        val metrics = HealthMetricSanity.sanitize(
            HealthSampleBuilder.connectMetrics(day.connect, day.details)
        )

        assertNull(metrics.steps)
        assertNull(metrics.distanceM)
        assertNull(metrics.heartRateBpm)
        assertNull(metrics.restingHeartRateBpm)
        assertNull(metrics.spo2Pct)
        assertNull(metrics.sleepMinutes)
        assertNull(metrics.sleepStages?.deep)
        assertEquals(100, metrics.sleepStages?.light)
        assertEquals(79.5, metrics.weightKg!!, 0.0001)
    }

    @Test
    fun `lists are capped and clipped to the server limits`() {
        val workouts = (1..30).map {
            HealthWorkoutReading(
                type = "t".repeat(100),
                minutes = 10,
                startMillis = 0,
            )
        } + HealthWorkoutReading(type = "bad", minutes = 5000, startMillis = 0)
        val topApps = (1..25).map { HealthTopApp("a".repeat(100), it) } +
            HealthTopApp("negative", -1)

        val metrics = HealthMetricSanity.sanitize(
            HealthMetrics(workouts = workouts.map {
                HealthWorkout(it.type, it.minutes, HealthDays.isoInstant(it.startMillis))
            }, topApps = topApps)
        )

        assertEquals(HealthMetricSanity.MAX_WORKOUTS, metrics.workouts?.size)
        assertEquals(60, metrics.workouts?.first()?.type?.length)
        assertEquals(HealthMetricSanity.MAX_TOP_APPS, metrics.topApps?.size)
        assertEquals(80, metrics.topApps?.first()?.label?.length)
        assertTrue(metrics.topApps!!.none { it.label == "negative" })
    }

    @Test
    fun `days are sorted oldest first whatever the provider order`() {
        val upload = HealthSampleBuilder.build(
            days = listOf(
                HealthDayInput("2026-11-24", HealthConnectAggregates(steps = 10)),
                HealthDayInput("2026-11-22", HealthConnectAggregates(steps = 30)),
                HealthDayInput("2026-11-23", HealthConnectAggregates(steps = 20)),
            ),
            installedApps = emptyList(),
            tzOffsetMinutes = -180,
            capturedAtMillis = 0,
        )

        assertEquals(
            listOf("2026-11-22", "2026-11-23", "2026-11-24"),
            upload.samples.map { it.date },
        )
    }
}
