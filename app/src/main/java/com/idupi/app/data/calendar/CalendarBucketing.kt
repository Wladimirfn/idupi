package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Places events on the local days they cover (phone-calendar T5), shared by the
 * list, month and week views.
 *
 * Timed events belong to every local day they overlap; all-day events belong to
 * the UTC dates the provider stored, because their instants are UTC midnight
 * boundaries and converting them to the device zone would shift the day west of
 * UTC. Multi-day events therefore appear on each covered day.
 *
 * Pure so the placement is unit-tested without a device.
 */
object CalendarBucketing {

    /** One entry per requested date, in the same order, each list sorted for display. */
    fun bucket(
        events: List<PhoneCalendarEvent>,
        dates: List<LocalDate>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, List<PhoneCalendarEvent>> {
        if (dates.isEmpty()) return emptyMap()
        val byDay = LinkedHashMap<LocalDate, MutableList<PhoneCalendarEvent>>(dates.size)
        dates.forEach { byDay[it] = mutableListOf() }

        for (event in events) {
            val start = CalendarOverlap.parseInstant(event.start) ?: continue
            val end = CalendarOverlap.parseInstant(event.end) ?: continue
            if (event.allDay) {
                val startDate = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
                val parsedEnd = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                // The provider stores the exclusive next-midnight end; a
                // zero-length row still belongs to its start day.
                val endDate = if (parsedEnd.isAfter(startDate)) parsedEnd else startDate.plusDays(1)
                for (date in dates) {
                    if (!date.isBefore(startDate) && date.isBefore(endDate)) {
                        byDay.getValue(date) += event
                    }
                }
                continue
            }
            if (end <= start) continue
            for (date in dates) {
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (CalendarOverlap.overlaps(start, end, dayStart, dayEnd)) {
                    byDay.getValue(date) += event
                }
            }
        }

        return byDay.mapValues { (_, dayEvents) -> sorted(dayEvents) }
    }

    /** All-day events first, then by start instant, then id. */
    fun sorted(events: List<PhoneCalendarEvent>): List<PhoneCalendarEvent> =
        events.sortedWith(
            compareBy(
                { !it.allDay },
                { CalendarOverlap.parseInstant(it.start) ?: Long.MAX_VALUE },
                { it.id },
            )
        )

    /**
     * Local days touched by at least one event that overlaps another timed
     * event. A multi-day conflicting event marks every day it spans, so the
     * month grid can flag the whole span instead of only its first day.
     */
    fun conflictingDates(
        events: List<PhoneCalendarEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<LocalDate> {
        val conflictingIds = CalendarOverlap.conflictingEventIds(events)
        if (conflictingIds.isEmpty()) return emptySet()
        val dates = linkedSetOf<LocalDate>()
        for (event in events) {
            if (event.allDay || event.id !in conflictingIds) continue
            val start = CalendarOverlap.parseInstant(event.start) ?: continue
            val end = CalendarOverlap.parseInstant(event.end) ?: continue
            if (end <= start) continue
            var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            // The end is exclusive, so an event ending at local midnight does
            // not mark the next day.
            val lastDate = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
            while (!date.isAfter(lastDate)) {
                dates += date
                date = date.plusDays(1)
            }
        }
        return dates
    }
}
