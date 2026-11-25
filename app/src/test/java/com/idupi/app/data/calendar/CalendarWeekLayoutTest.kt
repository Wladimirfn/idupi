package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Week grid placement: minutes from the grid start, clipping and overlap columns. */
class CalendarWeekLayoutTest {

    private val date = LocalDate.of(2026, 11, 25)

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

    private fun blocks(vararg events: PhoneCalendarEvent) = CalendarWeekLayout.blocks(
        eventsByDay = mapOf(date to events.toList()),
        zone = ZoneOffset.UTC,
    )

    @Test
    fun `top and height come from the event minutes`() {
        val block = blocks(event("reunion", "2026-11-25T13:00:00Z", "2026-11-25T14:30:00Z")).single()

        // The grid starts at 07:00.
        assertEquals(6 * 60, block.topMinutes)
        assertEquals(90, block.heightMinutes)
        assertEquals(0, block.column)
        assertEquals(1, block.columnCount)
    }

    @Test
    fun `events are clipped to the visible hours`() {
        val early = blocks(event("temprano", "2026-11-25T06:00:00Z", "2026-11-25T08:00:00Z")).single()
        assertEquals(0, early.topMinutes)
        assertEquals(60, early.heightMinutes)

        val late = blocks(event("tarde", "2026-11-25T21:00:00Z", "2026-11-25T23:00:00Z")).single()
        assertEquals((21 - 7) * 60, late.topMinutes)
        assertEquals(60, late.heightMinutes)
    }

    @Test
    fun `events outside the grid are omitted`() {
        assertTrue(blocks(event("madrugada", "2026-11-25T05:00:00Z", "2026-11-25T06:00:00Z")).isEmpty())
        assertTrue(blocks(event("noche", "2026-11-25T23:00:00Z", "2026-11-26T00:00:00Z")).isEmpty())
    }

    @Test
    fun `all-day events stay out of the timed grid`() {
        val allDay = event(
            "feriado",
            "2026-11-25T00:00:00Z",
            "2026-11-26T00:00:00Z",
            allDay = true,
        )

        assertTrue(blocks(allDay).isEmpty())
    }

    @Test
    fun `overlapping events share the cluster width`() {
        val placed = blocks(
            event("a", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
            event("b", "2026-11-25T13:30:00Z", "2026-11-25T14:30:00Z"),
        )

        assertEquals(listOf(0, 1), placed.sortedBy { it.event.id }.map { it.column })
        assertTrue(placed.all { it.columnCount == 2 })
    }

    @Test
    fun `touching events keep full width because they do not overlap`() {
        val placed = blocks(
            event("a", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
            event("b", "2026-11-25T14:00:00Z", "2026-11-25T15:00:00Z"),
        )

        assertTrue(placed.all { it.column == 0 && it.columnCount == 1 })
    }

    @Test
    fun `a multi-day event is laid out on each covered day`() {
        val placed = CalendarWeekLayout.blocks(
            eventsByDay = mapOf(
                LocalDate.of(2026, 11, 24) to listOf(
                    event("viaje", "2026-11-24T20:00:00Z", "2026-11-26T01:00:00Z")
                ),
                LocalDate.of(2026, 11, 25) to listOf(
                    event("viaje", "2026-11-24T20:00:00Z", "2026-11-26T01:00:00Z")
                ),
            ),
            zone = ZoneOffset.UTC,
        )

        assertEquals(2, placed.size)
        assertEquals(setOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)), placed.map { it.date }.toSet())
        // On Nov 24 the block is clipped to the 22:00 grid end.
        assertEquals(120, placed.first { it.date == LocalDate.of(2026, 11, 24) }.heightMinutes)
    }
}
