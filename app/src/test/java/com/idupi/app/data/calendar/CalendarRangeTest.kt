package com.idupi.app.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Grid shape and provider-window math for the Agenda month and week views. */
class CalendarRangeTest {

    @Test
    fun `weeks start on Monday`() {
        val wednesday = LocalDate.of(2026, 11, 25)

        assertEquals(LocalDate.of(2026, 11, 23), CalendarRange.weekStart(wednesday))
        assertEquals(LocalDate.of(2026, 11, 23), CalendarRange.weekStart(LocalDate.of(2026, 11, 23)))
        assertEquals(LocalDate.of(2026, 11, 23), CalendarRange.weekStart(LocalDate.of(2026, 11, 29)))
    }

    @Test
    fun `week dates are Monday through Sunday across a month boundary`() {
        val dates = CalendarRange.weekDates(LocalDate.of(2026, 12, 2))

        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 11, 30), dates.first())
        assertEquals(LocalDate.of(2026, 12, 6), dates.last())
        assertTrue(dates.all { it.dayOfWeek != DayOfWeek.SUNDAY || it == dates.last() })
    }

    @Test
    fun `a month needing six rows includes leading and trailing days`() {
        // November 2026 starts on Sunday and ends on Monday: the Monday-first
        // grid needs Oct 26..Dec 6.
        val weeks = CalendarRange.monthWeeks(YearMonth.of(2026, 11))

        assertEquals(6, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(LocalDate.of(2026, 10, 26), weeks.first().first())
        assertEquals(LocalDate.of(2026, 12, 6), weeks.last().last())
        assertEquals(DayOfWeek.MONDAY, weeks.first().first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, weeks.last().last().dayOfWeek)
    }

    @Test
    fun `a month that fits uses five rows`() {
        // February 2026 starts on Sunday and has 28 days: Jan 26..Mar 1.
        val weeks = CalendarRange.monthWeeks(YearMonth.of(2026, 2))

        assertEquals(5, weeks.size)
        assertEquals(LocalDate.of(2026, 1, 26), weeks.first().first())
        assertEquals(LocalDate.of(2026, 3, 1), weeks.last().last())
    }

    @Test
    fun `a four-week month is padded to five rows`() {
        // February 2027 starts on Monday and has 28 days: without padding it
        // would be exactly four weeks.
        val weeks = CalendarRange.monthWeeks(YearMonth.of(2027, 2))

        assertEquals(5, weeks.size)
        assertEquals(LocalDate.of(2027, 2, 1), weeks.first().first())
        assertEquals(LocalDate.of(2027, 3, 7), weeks.last().last())
    }

    @Test
    fun `month dates flatten every grid cell`() {
        val dates = CalendarRange.monthDates(YearMonth.of(2026, 11))

        assertEquals(42, dates.size)
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `query covers the local start of the first and the day after the last date`() {
        val query = CalendarRange.query(
            dates = listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)),
            zone = ZoneOffset.UTC,
        )

        assertEquals("2026-11-24T00:00:00Z", java.time.Instant.ofEpochMilli(query!!.startMillis).toString())
        assertEquals(
            "2026-11-26T00:00:00Z",
            java.time.Instant.ofEpochMilli(query.endExclusiveMillis).toString(),
        )
    }

    @Test
    fun `an empty date list has no query window`() {
        assertNull(CalendarRange.query(emptyList(), ZoneOffset.UTC))
    }
}
