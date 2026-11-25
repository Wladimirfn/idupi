package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import java.time.Instant

/**
 * Deterministic time-overlap math shared by the Agenda screen, the local
 * conflict check in the add-event sheet and the tests.
 *
 * Rules mirror the server (`phone-calendar-store.mjs`): all-day events never
 * conflict on either side, and touching endpoints do not overlap (an event
 * ending at 14:00 does not clash with one starting at 14:00).
 */
object CalendarOverlap {

    fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart < bEnd && bStart < aEnd

    /** Epoch millis of an ISO-8601 string, or null when it does not parse. */
    fun parseInstant(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    /**
     * Existing events that overlap a candidate event, sorted by start. An
     * all-day candidate or an all-day existing event never conflicts.
     */
    fun conflictsFor(
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        events: List<PhoneCalendarEvent>,
    ): List<PhoneCalendarEvent> {
        if (allDay || endMillis <= startMillis) return emptyList()
        return events.asSequence()
            .filter { !it.allDay }
            .mapNotNull { event ->
                val start = parseInstant(event.start) ?: return@mapNotNull null
                val end = parseInstant(event.end) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null
                if (!overlaps(startMillis, endMillis, start, end)) return@mapNotNull null
                event
            }
            .sortedWith(compareBy({ parseInstant(it.start) ?: 0L }, { it.id }))
            .toList()
    }

    /**
     * Ids of every timed event that overlaps at least one other timed event.
     * The Agenda screen uses it to flag rows without recomputing pairs per row.
     */
    fun conflictingEventIds(events: List<PhoneCalendarEvent>): Set<String> {
        val timed = events.asSequence()
            .filter { !it.allDay }
            .mapNotNull { event ->
                val start = parseInstant(event.start) ?: return@mapNotNull null
                val end = parseInstant(event.end) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null
                TimedEvent(event.id, start, end)
            }
            .sortedWith(compareBy({ it.start }, { it.id }))
            .toList()

        val conflicting = mutableSetOf<String>()
        for (index in timed.indices) {
            val current = timed[index]
            for (next in index + 1 until timed.size) {
                val other = timed[next]
                if (other.start >= current.end) break
                if (overlaps(current.start, current.end, other.start, other.end)) {
                    conflicting += current.id
                    conflicting += other.id
                }
            }
        }
        return conflicting
    }

    private data class TimedEvent(val id: String, val start: Long, val end: Long)
}
