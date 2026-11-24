package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AgendaGroupingTest {

    private val zone = ZoneOffset.ofHours(-3)
    private val now = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

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
    fun `timed events are grouped by the device day`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event("reunion", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertEquals(1, days.size)
        assertEquals(java.time.LocalDate.of(2026, 11, 24), days.single().date)
        assertEquals(listOf("reunion"), days.single().events.map { it.id })
    }

    @Test
    fun `all-day events keep their UTC date west of UTC`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event(
                    "feriado",
                    "2026-11-25T00:00:00Z",
                    "2026-11-26T00:00:00Z",
                    allDay = true,
                ),
            ),
            nowMillis = now,
            zone = zone,
        )

        // In UTC-3 the instant is Nov 24 at 21:00, but the provider's day is
        // the UTC date; shifting it would show the holiday one day early.
        assertEquals(java.time.LocalDate.of(2026, 11, 25), days.single().date)
    }

    @Test
    fun `multi-day all-day events appear on every touched day`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event(
                    "viaje",
                    "2026-11-26T00:00:00Z",
                    "2026-11-28T00:00:00Z",
                    allDay = true,
                ),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertEquals(
            listOf(
                java.time.LocalDate.of(2026, 11, 26),
                java.time.LocalDate.of(2026, 11, 27),
            ),
            days.map { it.date },
        )
    }

    @Test
    fun `all-day events sort before timed events in a day`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event("reunion", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
                event(
                    "feriado",
                    "2026-11-25T00:00:00Z",
                    "2026-11-26T00:00:00Z",
                    allDay = true,
                ),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertEquals(listOf("feriado", "reunion"), days.single().events.map { it.id })
    }

    @Test
    fun `events outside the seven-day window are omitted`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event("old", "2026-11-23T13:00:00Z", "2026-11-23T14:00:00Z"),
                event("far", "2026-12-05T13:00:00Z", "2026-12-05T14:00:00Z"),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertTrue(days.isEmpty())
    }

    @Test
    fun `empty days are omitted`() {
        val days = AgendaGrouping.days(
            events = listOf(
                event("today", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"),
                event("later", "2026-11-27T13:00:00Z", "2026-11-27T14:00:00Z"),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertEquals(
            listOf(
                java.time.LocalDate.of(2026, 11, 24),
                java.time.LocalDate.of(2026, 11, 27),
            ),
            days.map { it.date },
        )
    }
}
