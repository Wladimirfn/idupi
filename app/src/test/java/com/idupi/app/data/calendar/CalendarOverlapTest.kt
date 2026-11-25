package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarOverlapTest {

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
    fun `touching endpoints do not overlap`() {
        val aStart = CalendarOverlap.parseInstant("2026-11-24T13:00:00Z")!!
        val aEnd = CalendarOverlap.parseInstant("2026-11-24T14:00:00Z")!!
        val bStart = CalendarOverlap.parseInstant("2026-11-24T14:00:00Z")!!
        val bEnd = CalendarOverlap.parseInstant("2026-11-24T15:00:00Z")!!

        assertFalse(CalendarOverlap.overlaps(aStart, aEnd, bStart, bEnd))
        assertTrue(CalendarOverlap.overlaps(aStart, aEnd, aStart + 1, bEnd))
    }

    @Test
    fun `all-day events never conflict on either side`() {
        val allDay = event(
            "feriado",
            "2026-11-25T00:00:00Z",
            "2026-11-26T00:00:00Z",
            allDay = true,
        )
        val timed = event("reunion", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z")

        val candidateStart = CalendarOverlap.parseInstant("2026-11-25T13:00:00Z")!!
        val candidateEnd = CalendarOverlap.parseInstant("2026-11-25T14:00:00Z")!!

        assertTrue(
            CalendarOverlap.conflictsFor(candidateStart, candidateEnd, false, listOf(allDay))
                .isEmpty()
        )
        assertTrue(
            CalendarOverlap.conflictsFor(candidateStart, candidateEnd, true, listOf(timed))
                .isEmpty()
        )
    }

    @Test
    fun `conflicting event ids flag both sides`() {
        val events = listOf(
            event("a", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"),
            event("b", "2026-11-24T13:30:00Z", "2026-11-24T14:30:00Z"),
            event("c", "2026-11-24T16:00:00Z", "2026-11-24T17:00:00Z"),
        )

        assertEquals(setOf("a", "b"), CalendarOverlap.conflictingEventIds(events))
    }

    @Test
    fun `conflicts for a candidate are sorted by start`() {
        val events = listOf(
            event("late", "2026-11-24T13:45:00Z", "2026-11-24T14:30:00Z"),
            event("early", "2026-11-24T12:30:00Z", "2026-11-24T13:15:00Z"),
        )
        val start = CalendarOverlap.parseInstant("2026-11-24T13:00:00Z")!!
        val end = CalendarOverlap.parseInstant("2026-11-24T14:00:00Z")!!

        assertEquals(
            listOf("early", "late"),
            CalendarOverlap.conflictsFor(start, end, false, events).map { it.id },
        )
    }

    @Test
    fun `invalid instants parse to null`() {
        assertNull(CalendarOverlap.parseInstant(null))
        assertNull(CalendarOverlap.parseInstant(""))
        assertNull(CalendarOverlap.parseInstant("not-a-date"))
    }
}
