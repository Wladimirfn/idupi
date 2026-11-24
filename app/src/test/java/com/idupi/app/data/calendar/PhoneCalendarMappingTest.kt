package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PhoneCalendarMappingTest {

    private val calendar = CalendarRow(
        id = "1",
        name = "Personal",
        accountType = "com.google",
        accountName = "owner@gmail.com",
        colorArgb = 0xFF4285F4.toInt(),
        accessLevel = 700,
    )

    private val start = Instant.parse("2026-11-24T13:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-11-24T14:00:00Z").toEpochMilli()

    private fun instance(
        eventId: String = "10",
        calendarId: String = "1",
        title: String = "Reunión",
        begin: Long = start,
        endMillis: Long = end,
        allDay: Boolean = false,
        location: String? = "Oficina",
        description: String? = "Detalle",
        status: Int = 0,
        deleted: Boolean = false,
    ) = CalendarInstanceRow(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        beginMillis = begin,
        endMillis = endMillis,
        allDay = allDay,
        location = location,
        description = description,
        status = status,
        deleted = deleted,
    )

    @Test
    fun `writable follows the contributor access level`() {
        assertFalse(CalendarSnapshotMapping.isWritable(499))
        assertTrue(CalendarSnapshotMapping.isWritable(500))
        assertTrue(CalendarSnapshotMapping.isWritable(700))
    }

    @Test
    fun `color is reported as RRGGBB`() {
        assertEquals("#4285F4", CalendarSnapshotMapping.colorHex(0xFF4285F4.toInt()))
        assertNull(CalendarSnapshotMapping.colorHex(null))
    }

    @Test
    fun `timed instances are ISO instants and keep the calendar`() {
        val event = CalendarSnapshotMapping.toEvents(
            listOf(instance()),
            setOf("1"),
        ).single()

        assertTrue(event.id.startsWith("10@"))
        assertEquals("1", event.calendarId)
        assertEquals("2026-11-24T13:00:00Z", event.start)
        assertEquals("2026-11-24T14:00:00Z", event.end)
        assertFalse(event.allDay)
        assertEquals("Oficina", event.location)
    }

    @Test
    fun `all-day instances keep UTC day boundaries`() {
        val allDayStart = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
        val allDayEnd = Instant.parse("2026-11-26T00:00:00Z").toEpochMilli()

        val event = CalendarSnapshotMapping.toEvents(
            listOf(instance(begin = allDayStart, endMillis = allDayEnd, allDay = true)),
            setOf("1"),
        ).single()

        assertTrue(event.allDay)
        assertEquals("2026-11-25T00:00:00Z", event.start)
        assertEquals("2026-11-26T00:00:00Z", event.end)
    }

    @Test
    fun `recurring occurrences get unique ids`() {
        val first = instance(eventId = "10", begin = 1_000L, endMillis = 2_000L)
        val second = instance(eventId = "10", begin = 3_000L, endMillis = 4_000L)

        val events = CalendarSnapshotMapping.toEvents(listOf(first, second), setOf("1"))

        assertEquals(2, events.size)
        assertEquals(2, events.map { it.id }.toSet().size)
    }

    @Test
    fun `cancelled deleted and foreign-calendar rows are skipped`() {
        val rows = listOf(
            instance(eventId = "ok", status = 0),
            instance(eventId = "cancelled", status = 2),
            instance(eventId = "deleted", deleted = true),
            instance(eventId = "foreign", calendarId = "2"),
        )

        val events = CalendarSnapshotMapping.toEvents(rows, setOf("1"))

        assertEquals(listOf("ok@${start}"), events.map { it.id })
    }

    @Test
    fun `free text is clipped to the server caps`() {
        val event = CalendarSnapshotMapping.toEvents(
            listOf(
                instance(
                    title = "t".repeat(250),
                    location = "l".repeat(250),
                    description = "d".repeat(700),
                )
            ),
            setOf("1"),
        ).single()

        assertEquals(200, event.title.length)
        assertEquals(200, event.location.length)
        assertEquals(500, event.description.length)
    }

    @Test
    fun `snapshot caps calendars and events`() {
        val calendars = (1..60).map { index ->
            calendar.copy(id = "c$index", name = "Cal $index")
        }
        val instances = (1..1100).map { index ->
            instance(
                eventId = "e$index",
                calendarId = "c1",
                begin = 1_000_000L + index,
                endMillis = 2_000_000L + index,
            )
        }

        val snapshot = CalendarSnapshotMapping.buildSnapshot(
            nowMillis = start,
            tzOffsetMinutes = 0,
            calendars = calendars,
            instances = instances,
        )

        assertEquals(50, snapshot.calendars.size)
        assertEquals("Cal 1", snapshot.calendars.first().name)
        assertEquals(1000, snapshot.events.size)
    }

    @Test
    fun `snapshot stamps the capture and the 14-day window`() {
        val snapshot = CalendarSnapshotMapping.buildSnapshot(
            nowMillis = start,
            tzOffsetMinutes = -180,
            calendars = listOf(calendar),
            instances = listOf(instance()),
        )

        assertEquals("2026-11-24T13:00:00Z", snapshot.capturedAt)
        assertEquals("2026-11-24T13:00:00Z", snapshot.rangeStart)
        assertEquals("2026-12-08T13:00:00Z", snapshot.rangeEnd)
        assertEquals(-180, snapshot.tzOffsetMinutes)
        assertEquals(1, snapshot.calendars.size)
        assertEquals(1, snapshot.events.size)
    }

    @Test
    fun `a blank title is reported as-is so the UI can fall back`() {
        val event = CalendarSnapshotMapping.toEvents(
            listOf(instance(title = "")),
            setOf("1"),
        ).single()

        assertEquals("", event.title)
    }

    @Test
    fun `event with equal start and end is kept`() {
        val event: PhoneCalendarEvent = CalendarSnapshotMapping.toEvents(
            listOf(instance(begin = 5_000L, endMillis = 5_000L)),
            setOf("1"),
        ).single()

        assertEquals("1970-01-01T00:00:05Z", event.start)
    }
}
