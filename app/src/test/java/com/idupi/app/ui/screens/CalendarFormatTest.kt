package com.idupi.app.ui.screens

import com.idupi.app.data.calendar.CalendarPalette
import com.idupi.app.data.calendar.CalendarReportState
import com.idupi.app.domain.model.InboxEvent
import com.idupi.app.domain.model.InboxEventConflict
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class CalendarFormatTest {

    private val zone = ZoneOffset.ofHours(-3)

    @Test
    fun `short date is deterministic Spanish`() {
        assertEquals("mié 25 nov", shortDateLabel(LocalDate.of(2026, 11, 25)))
        assertEquals("dom 1 feb", shortDateLabel(LocalDate.of(2026, 2, 1)))
    }

    @Test
    fun `day heading uses relative words`() {
        val today = LocalDate.of(2026, 11, 24)

        assertEquals("Hoy", dayHeadingLabel(today, today))
        assertEquals("Mañana", dayHeadingLabel(today.plusDays(1), today))
        assertEquals("mié 25 nov", dayHeadingLabel(today.plusDays(1), today.minusDays(1)))
    }

    @Test
    fun `timed chip shows day and clock`() {
        val event = InboxEvent(
            title = "Reunión",
            start = "2026-11-25T16:00:00Z",
            end = "2026-11-25T17:00:00Z",
        )

        assertEquals("📅 mié 25 nov 13:00 · Reunión", inboxEventChipLabel(event, zone))
    }

    @Test
    fun `all-day chip shows the UTC day and todo el dia`() {
        val event = InboxEvent(
            title = "Feriado",
            start = "2026-11-25T00:00:00Z",
            end = "2026-11-26T00:00:00Z",
            allDay = true,
        )

        assertEquals("📅 mié 25 nov todo el día · Feriado", inboxEventChipLabel(event, zone))
    }

    @Test
    fun `conflict line names each event and its clock`() {
        val conflicts = listOf(
            InboxEventConflict(
                title = "Reunión",
                start = "2026-11-25T16:00:00Z",
                end = "2026-11-25T17:00:00Z",
            ),
            InboxEventConflict(
                title = "Almuerzo",
                start = "2026-11-25T17:30:00Z",
                end = "2026-11-25T18:30:00Z",
            ),
        )

        assertEquals(
            "Choca con: Reunión (13:00); Almuerzo (14:30)",
            inboxConflictLine(conflicts, zone),
        )
        assertNull(inboxConflictLine(emptyList(), zone))
    }

    @Test
    fun `agenda conflict line is empty for the same event`() {
        val event = PhoneCalendarEvent(
            id = "1",
            title = "Reunión",
            start = "2026-11-25T16:00:00Z",
            end = "2026-11-25T17:00:00Z",
        )

        assertNull(agendaConflictLine(emptyList(), zone))
        assertEquals("Choca con: Reunión (13:00)", agendaConflictLine(listOf(event), zone))
    }

    @Test
    fun `proposal label combines relative day and time range`() {
        val proposal = PhoneCalendarProposal(
            id = "phr-1",
            title = "Almuerzo",
            start = "2026-11-25T16:00:00Z",
            end = "2026-11-25T17:30:00Z",
        )
        val now = java.time.Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

        assertEquals("Mañana 13:00–14:30", proposalWhenLabel(proposal, zone, now))
    }

    @Test
    fun `last report label shows result and event count`() {
        val now = 1_000_000_000_000L
        val state = CalendarReportState(
            lastAttemptAtMillis = now - 5 * 60_000,
            success = true,
            eventCount = 12,
        )

        assertEquals("Último envío: ok hace 5 min (12 eventos)", calendarLastReportLabel(state, now))
        assertEquals(
            "Último envío: ninguno todavía",
            calendarLastReportLabel(CalendarReportState(), now),
        )
        assertEquals(
            "Último envío: falló hace 5 min",
            calendarLastReportLabel(state.copy(success = false), now),
        )
        assertEquals(
            "Último envío: ok hace 5 min (1 evento)",
            calendarLastReportLabel(state.copy(eventCount = 1), now),
        )
    }

    @Test
    fun `month header uses the Spanish month name`() {
        assertEquals("Noviembre de 2026", calendarMonthTitle(YearMonth.of(2026, 11)))
        assertEquals("Febrero de 2026", calendarMonthTitle(YearMonth.of(2026, 2)))
    }

    @Test
    fun `weekday labels start on Monday in Spanish`() {
        assertEquals(
            listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
            weekdayShortLabels(),
        )
    }

    @Test
    fun `week header compacts a week inside one month`() {
        assertEquals(
            "23 – 29 de noviembre de 2026",
            calendarWeekTitle(LocalDate.of(2026, 11, 23)),
        )
    }

    @Test
    fun `week header names both months when the week crosses one`() {
        assertEquals(
            "30 de noviembre – 6 de diciembre de 2026",
            calendarWeekTitle(LocalDate.of(2026, 11, 30)),
        )
    }

    @Test
    fun `week header names both years when the week crosses one`() {
        assertEquals(
            "28 de diciembre de 2026 – 3 de enero de 2027",
            calendarWeekTitle(LocalDate.of(2026, 12, 28)),
        )
    }

    @Test
    fun `calendar palette parses provider colors and rejects malformed values`() {
        assertEquals(0xFFFF8800.toInt(), CalendarPalette.argb("#FF8800"))
        assertEquals(0xFF0A0B0C.toInt(), CalendarPalette.argb(" 0a0b0c "))
        assertNull(CalendarPalette.argb(null))
        assertNull(CalendarPalette.argb("#12345"))
        assertNull(CalendarPalette.argb("#GGGGGG"))
    }
}
