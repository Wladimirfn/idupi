package com.idupi.app.data.calendar

import com.idupi.app.domain.model.InboxEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarEventBuilderTest {

    private val utc = ZoneOffset.UTC
    private val chile = ZoneId.of("America/Santiago")

    private val google = PhoneCalendarRef(
        id = "1",
        name = "Personal",
        accountType = "com.google",
        writable = true,
    )
    private val outlook = PhoneCalendarRef(
        id = "2",
        name = "Trabajo",
        accountType = "com.microsoft.exchange",
        writable = true,
    )
    private val readOnly = PhoneCalendarRef(
        id = "3",
        name = "Feriados",
        accountType = "com.google",
        writable = false,
    )

    private fun draft(
        title: String = "Reunión",
        date: String = "2026-11-24",
        start: String = "13:00",
        end: String = "14:00",
        allDay: Boolean = false,
        calendarId: String = "1",
        location: String = "Oficina",
    ) = CalendarEventDraft(
        title = title,
        date = date,
        startTime = start,
        endTime = end,
        allDay = allDay,
        location = location,
        calendarId = calendarId,
    )

    @Test
    fun `valid timed draft uses the device zone`() {
        val result = CalendarEventBuilder.build(
            draft(),
            listOf(google, outlook),
            chile,
        ) as CalendarDraftResult.Valid

        assertEquals("1", result.values.calendarId)
        assertEquals("Reunión", result.values.title)
        assertEquals("America/Santiago", result.values.timeZoneId)
        assertFalse(result.values.allDay)
        // 13:00 in Chile (UTC-3 in November) is 16:00Z.
        assertEquals("2026-11-24T16:00:00Z", java.time.Instant.ofEpochMilli(result.values.startMillis).toString())
        assertEquals("2026-11-24T17:00:00Z", java.time.Instant.ofEpochMilli(result.values.endMillis).toString())
    }

    @Test
    fun `all-day draft uses UTC midnight boundaries`() {
        val result = CalendarEventBuilder.build(
            draft(allDay = true),
            listOf(google),
            chile,
        ) as CalendarDraftResult.Valid

        assertTrue(result.values.allDay)
        assertEquals("UTC", result.values.timeZoneId)
        assertEquals("2026-11-24T00:00:00Z", java.time.Instant.ofEpochMilli(result.values.startMillis).toString())
        assertEquals("2026-11-25T00:00:00Z", java.time.Instant.ofEpochMilli(result.values.endMillis).toString())
    }

    @Test
    fun `title is required`() {
        val result = CalendarEventBuilder.build(draft(title = "  "), listOf(google), utc)
        assertEquals(
            "Escribe un título para el evento.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `date must be AAAA-MM-DD`() {
        val result = CalendarEventBuilder.build(draft(date = "24/11/2026"), listOf(google), utc)
        assertEquals(
            "La fecha debe tener el formato AAAA-MM-DD.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `start time must be HH MM`() {
        val result = CalendarEventBuilder.build(draft(start = "25:00"), listOf(google), utc)
        assertEquals(
            "La hora de inicio debe tener el formato HH:MM.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `end time must be HH MM`() {
        val result = CalendarEventBuilder.build(draft(end = "9"), listOf(google), utc)
        assertEquals(
            "La hora de fin debe tener el formato HH:MM.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `end must be after start`() {
        val result = CalendarEventBuilder.build(draft(start = "14:00", end = "14:00"), listOf(google), utc)
        assertEquals(
            "La hora de fin debe ser después de la hora de inicio.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `a missing calendar is rejected`() {
        val result = CalendarEventBuilder.build(draft(calendarId = "nope"), listOf(google), utc)
        assertEquals("Elige un calendario.", (result as CalendarDraftResult.Invalid).message)
    }

    @Test
    fun `a read-only calendar is rejected`() {
        val result = CalendarEventBuilder.build(draft(calendarId = "3"), listOf(readOnly), utc)
        assertEquals(
            "Ese calendario no permite cambios.",
            (result as CalendarDraftResult.Invalid).message,
        )
    }

    @Test
    fun `title and location clip on save`() {
        val result = CalendarEventBuilder.build(
            draft(title = "t".repeat(250), location = "l".repeat(300)),
            listOf(google),
            utc,
        ) as CalendarDraftResult.Valid

        assertEquals(200, result.values.title.length)
        assertEquals(200, result.values.location.length)
    }

    @Test
    fun `default calendar prefers the prefilled one`() {
        assertEquals(
            "2",
            CalendarEventBuilder.defaultCalendarId(listOf(google, outlook), "2", "1"),
        )
    }

    @Test
    fun `default calendar falls back to last used then Google`() {
        assertEquals(
            "2",
            CalendarEventBuilder.defaultCalendarId(listOf(google, outlook), null, "2"),
        )
        assertEquals(
            "1",
            CalendarEventBuilder.defaultCalendarId(listOf(outlook, google), null, null),
        )
    }

    @Test
    fun `default calendar ignores read-only and returns empty when none`() {
        assertEquals("", CalendarEventBuilder.defaultCalendarId(listOf(readOnly), "3", "3"))
    }

    @Test
    fun `all-day prefill reads the UTC date, not the local one`() {
        val prefill = EventPrefill(
            title = "Feriado",
            startIso = "2026-11-25T00:00:00Z",
            endIso = "2026-11-26T00:00:00Z",
            allDay = true,
        )

        val draft = CalendarEventBuilder.toDraft(prefill, listOf(google), null, chile)

        assertEquals("2026-11-25", draft.date)
        assertTrue(draft.allDay)
    }

    @Test
    fun `timed prefill converts to the device date and time`() {
        val prefill = EventPrefill(
            title = "Reunión",
            startIso = "2026-11-24T16:00:00Z",
            endIso = "2026-11-24T17:00:00Z",
        )

        val draft = CalendarEventBuilder.toDraft(prefill, listOf(google), null, chile)

        assertEquals("2026-11-24", draft.date)
        assertEquals("13:00", draft.startTime)
        assertEquals("14:00", draft.endTime)
    }

    @Test
    fun `proposal prefill keeps its calendar hint`() {
        val proposal = PhoneCalendarProposal(
            id = "phr-1",
            title = "Almuerzo",
            start = "2026-11-25T16:00:00Z",
            end = "2026-11-25T17:00:00Z",
            location = "Centro",
            calendarId = "2",
        )

        val draft = CalendarEventBuilder.toDraft(
            CalendarEventBuilder.prefillFor(proposal),
            listOf(google, outlook),
            null,
            chile,
        )

        assertEquals("Almuerzo", draft.title)
        assertEquals("2026-11-25", draft.date)
        assertEquals("2", draft.calendarId)
    }

    @Test
    fun `inbox event prefill carries the extracted fields`() {
        val prefill = CalendarEventBuilder.prefillFor(
            InboxEvent(
                title = "Cita",
                start = "2026-11-26T15:00:00Z",
                end = "2026-11-26T16:00:00Z",
                location = "Clínica",
            )
        )

        assertEquals("Cita", prefill.title)
        assertEquals("2026-11-26T15:00:00Z", prefill.startIso)
        assertEquals("Clínica", prefill.location)
    }

    @Test
    fun `date and time parsing accept real values only`() {
        assertEquals(LocalDate.of(2026, 11, 24), CalendarEventBuilder.parseDate("2026-11-24")!!)
        assertNull(CalendarEventBuilder.parseDate("2026-02-30"))
        assertTrue(CalendarEventBuilder.parseTime("9:05") != null)
        assertTrue(CalendarEventBuilder.parseTime("23:59") != null)
        assertNull(CalendarEventBuilder.parseTime("24:00"))
        assertNull(CalendarEventBuilder.parseTime("12:60"))
    }
}
