package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One day of the local Agenda view; only days with events are returned. */
data class AgendaDay(
    val date: LocalDate,
    val events: List<PhoneCalendarEvent>,
)

/**
 * Groups the reader's events into the next [DEFAULT_DAYS] local days.
 *
 * Timed events belong to every local day they overlap; all-day events belong to
 * the UTC dates the provider stored, because their instants are UTC midnight
 * boundaries and converting them to the device zone would shift the day west of
 * UTC. Pure so the grouping is unit-tested without a device.
 */
object AgendaGrouping {

    const val DEFAULT_DAYS = 7

    fun days(
        events: List<PhoneCalendarEvent>,
        nowMillis: Long,
        days: Int = DEFAULT_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgendaDay> {
        if (days <= 0) return emptyList()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val window = (0 until days).map { today.plusDays(it.toLong()) }
        if (window.isEmpty()) return emptyList()
        val byDay = window.associateWith { mutableListOf<PhoneCalendarEvent>() }

        for (event in events) {
            val start = CalendarOverlap.parseInstant(event.start) ?: continue
            val end = CalendarOverlap.parseInstant(event.end) ?: continue
            if (event.allDay) {
                val startDate = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
                val parsedEnd = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                // The provider stores the exclusive next-midnight end; a
                // zero-length row still belongs to its start day.
                val endDate = if (parsedEnd.isAfter(startDate)) parsedEnd else startDate.plusDays(1)
                for (date in window) {
                    if (!date.isBefore(startDate) && date.isBefore(endDate)) {
                        byDay.getValue(date) += event
                    }
                }
                continue
            }
            if (end <= start) continue
            for (date in window) {
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (CalendarOverlap.overlaps(start, end, dayStart, dayEnd)) {
                    byDay.getValue(date) += event
                }
            }
        }

        return byDay.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { (date, dayEvents) ->
                AgendaDay(
                    date = date,
                    events = dayEvents.sortedWith(
                        compareBy(
                            { !it.allDay },
                            { CalendarOverlap.parseInstant(it.start) ?: Long.MAX_VALUE },
                            { it.id },
                        )
                    ),
                )
            }
    }
}
