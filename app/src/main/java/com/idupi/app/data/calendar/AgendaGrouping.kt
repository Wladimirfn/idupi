package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of the local Agenda view; only days with events are returned. */
data class AgendaDay(
    val date: LocalDate,
    val events: List<PhoneCalendarEvent>,
)

/**
 * Groups the reader's events into the next [DEFAULT_DAYS] local days.
 *
 * Delegates the day placement to [CalendarBucketing], so the list and the
 * month/week views share one multi-day and all-day rule.
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
        return CalendarBucketing.bucket(events = events, dates = window, zone = zone)
            .filterValues { it.isNotEmpty() }
            .map { (date, dayEvents) -> AgendaDay(date = date, events = dayEvents) }
    }
}
