package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Day bucketing and conflict-day math shared by the Agenda views. */
class CalendarBucketingTest {

    private val zone = ZoneOffset.ofHours(-3)

    private fun event(
        id: String,
        start: String,
        end: String,
        allDay: Boolean = false,
    ) = PhoneCalendarEvent(
        id = id,
        calendarId = "1",
        title = id,
        start = start,
        end = end,
        allDay = allDay,
    )

    @Test
    fun `a timed event spans every local day it overlaps`() {
        val dates = listOf(
            LocalDate.of(2026, 11, 24),
            LocalDate.of(2026, 11, 25),
            LocalDate.of(2026, 11, 26),
        )
        val bucket = CalendarBucketing.bucket(
            events = listOf(event("viaje", "2026-11-24T23:00:00Z", "2026-11-26T01:00:00Z")),
            dates = dates,
            zone = zone,
        )

        // 20:00 Nov 24 through 22:00 Nov 25 in UTC-3.
        assertEquals(listOf("viaje"), bucket.getValue(dates[0]).map { it.id })
        assertEquals(listOf("viaje"), bucket.getValue(dates[1]).map { it.id })
        assertTrue(bucket.getValue(dates[2]).isEmpty())
    }

    @Test
    fun `all-day events keep the provider UTC dates`() {
        val bucket = CalendarBucketing.bucket(
            events = listOf(
                event("feriado", "2026-11-25T00:00:00Z", "2026-11-26T00:00:00Z", allDay = true)
            ),
            dates = listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)),
            zone = zone,
        )

        // The instant is Nov 24 21:00 local, but the provider's day is Nov 25.
        assertTrue(bucket.getValue(LocalDate.of(2026, 11, 24)).isEmpty())
        assertEquals(listOf("feriado"), bucket.getValue(LocalDate.of(2026, 11, 25)).map { it.id })
    }

    @Test
    fun `bucket keeps empty days and sorts all-day events first`() {
        val bucket = CalendarBucketing.bucket(
            events = listOf(
                event("reunion", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
                event("feriado", "2026-11-25T00:00:00Z", "2026-11-26T00:00:00Z", allDay = true),
            ),
            dates = listOf(
                LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 26),
            ),
            zone = zone,
        )

        assertEquals(3, bucket.size)
        assertTrue(bucket.getValue(LocalDate.of(2026, 11, 24)).isEmpty())
        assertEquals(
            listOf("feriado", "reunion"),
            bucket.getValue(LocalDate.of(2026, 11, 25)).map { it.id },
        )
        assertTrue(bucket.getValue(LocalDate.of(2026, 11, 26)).isEmpty())
    }

    @Test
    fun `conflict days mark overlapping timed events and ignore all-day rows`() {
        val dates = CalendarBucketing.conflictingDates(
            events = listOf(
                event("a", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
                event("b", "2026-11-25T13:30:00Z", "2026-11-25T14:30:00Z"),
                event("feriado", "2026-11-25T00:00:00Z", "2026-11-26T00:00:00Z", allDay = true),
            ),
            zone = ZoneOffset.UTC,
        )

        assertEquals(setOf(LocalDate.of(2026, 11, 25)), dates)
    }

    @Test
    fun `a multi-day conflicting event marks every covered day`() {
        val dates = CalendarBucketing.conflictingDates(
            events = listOf(
                event("viaje", "2026-11-24T23:00:00Z", "2026-11-26T01:00:00Z"),
                event("cena", "2026-11-25T23:30:00Z", "2026-11-26T00:30:00Z"),
            ),
            zone = ZoneOffset.UTC,
        )

        assertEquals(
            setOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26)),
            dates,
        )
    }

    @Test
    fun `touching events are not a conflict`() {
        val dates = CalendarBucketing.conflictingDates(
            events = listOf(
                event("a", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
                event("b", "2026-11-25T14:00:00Z", "2026-11-25T15:00:00Z"),
            ),
            zone = ZoneOffset.UTC,
        )

        assertTrue(dates.isEmpty())
    }
}
