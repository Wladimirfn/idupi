package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Owner-local day math (phone-health T2). The PC keys the whole history by
 * these strings, so an off-by-one day is a silent data loss.
 */
class HealthDaysTest {

    private fun epoch(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `a late UTC instant belongs to the previous owner day west of UTC`() {
        // 02:30Z is 23:30 of the previous day at UTC-3.
        assertEquals(
            "2026-11-23",
            HealthDays.ownerDayKey(epoch("2026-11-24T02:30:00Z"), -180),
        )
    }

    @Test
    fun `an early UTC instant can already be tomorrow east of UTC`() {
        // 20:00Z is 01:30 next day at UTC+5:30.
        assertEquals(
            "2026-11-25",
            HealthDays.ownerDayKey(epoch("2026-11-24T20:00:00Z"), 330),
        )
    }

    @Test
    fun `lastNDays returns ascending keys ending today`() {
        assertEquals(
            listOf(
                "2026-11-18",
                "2026-11-19",
                "2026-11-20",
                "2026-11-21",
                "2026-11-22",
                "2026-11-23",
                "2026-11-24",
            ),
            HealthDays.lastNDays("2026-11-24", 7),
        )
    }

    @Test
    fun `lastNDays crosses a month and a year boundary`() {
        assertEquals(
            listOf("2026-12-30", "2026-12-31", "2027-01-01"),
            HealthDays.lastNDays("2027-01-01", 3),
        )
    }

    @Test
    fun `day start is local midnight shifted to UTC`() {
        assertEquals(
            epoch("2026-11-24T03:00:00Z"),
            HealthDays.dayStartMillis("2026-11-24", -180),
        )
        assertEquals(
            epoch("2026-11-23T18:30:00Z"),
            HealthDays.dayStartMillis("2026-11-24", 330),
        )
    }

    @Test
    fun `day end is the next local midnight`() {
        assertEquals(
            HealthDays.dayStartMillis("2026-11-25", -180),
            HealthDays.dayEndMillis("2026-11-24", -180),
        )
    }

    @Test
    fun `offset comes from the phone zone at that instant`() {
        val santiago = ZoneId.of("America/Santiago")
        assertEquals(
            -180,
            HealthDays.offsetMinutesAt(epoch("2026-11-24T12:00:00Z"), santiago),
        )
    }

    @Test
    fun `isoInstant keeps the UTC wire form`() {
        assertEquals("2026-11-24T12:00:00Z", HealthDays.isoInstant(epoch("2026-11-24T12:00:00Z")))
    }
}
