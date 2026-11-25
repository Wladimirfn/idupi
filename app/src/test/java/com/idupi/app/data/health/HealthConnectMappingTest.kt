package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Health Connect enum translation (phone-health T2). The literals are the
 * library's stage/type numbers on purpose: if a future connect-client changed
 * them, this test fails instead of silently filing awake time as REM sleep.
 */
class HealthConnectMappingTest {

    @Test
    fun `stage spans are summed into the four contract stages`() {
        val stages = HealthConnectMapping.sleepStages(
            listOf(
                HealthStageSpan(5, 0, 60 * 60_000L), // deep
                HealthStageSpan(4, 0, 120 * 60_000L), // light
                HealthStageSpan(6, 0, 30 * 60_000L), // rem
                HealthStageSpan(1, 0, 10 * 60_000L), // awake
                HealthStageSpan(7, 0, 5 * 60_000L), // awake in bed
                HealthStageSpan(3, 0, 5 * 60_000L), // out of bed
            )
        )

        assertEquals(60, stages?.deep)
        assertEquals(120, stages?.light)
        assertEquals(30, stages?.rem)
        assertEquals(20, stages?.awake)
    }

    @Test
    fun `generic sleeping and unknown stages are not invented into a stage`() {
        assertNull(
            HealthConnectMapping.sleepStages(
                listOf(
                    HealthStageSpan(2, 0, 60 * 60_000L), // generic sleeping
                    HealthStageSpan(0, 0, 60 * 60_000L), // unknown
                )
            )
        )
    }

    @Test
    fun `empty and under-a-minute spans produce no stage`() {
        assertNull(HealthConnectMapping.sleepStages(emptyList()))
        assertNull(
            HealthConnectMapping.sleepStages(listOf(HealthStageSpan(5, 0, 30_000L)))
        )
    }

    @Test
    fun `exercise types map to stable short names`() {
        assertEquals("running", HealthConnectMapping.exerciseTypeLabel(56))
        assertEquals("walking", HealthConnectMapping.exerciseTypeLabel(79))
        assertEquals("weightlifting", HealthConnectMapping.exerciseTypeLabel(81))
        assertEquals("other", HealthConnectMapping.exerciseTypeLabel(0))
        assertEquals("other", HealthConnectMapping.exerciseTypeLabel(999))
    }

    @Test
    fun `span minutes floor and never go negative or past a day`() {
        assertEquals(0, HealthConnectMapping.spanMinutes(1_000, 1_000))
        assertEquals(0, HealthConnectMapping.spanMinutes(2_000, 1_000))
        assertEquals(2, HealthConnectMapping.spanMinutes(0, 149_999))
        assertEquals(1440, HealthConnectMapping.spanMinutes(0, 10L * 24 * 60 * 60_000))
    }
}
