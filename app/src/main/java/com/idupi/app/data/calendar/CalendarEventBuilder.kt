package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Editable event fields the confirm sheet shows. All times are text so the
 * owner can correct them; the pure [CalendarEventBuilder] decides whether they
 * are valid and what instants they mean.
 */
data class CalendarEventDraft(
    val title: String = "",
    val date: String = "",
    val startTime: String = "09:00",
    val endTime: String = "10:00",
    val allDay: Boolean = false,
    val location: String = "",
    val calendarId: String = "",
)

/** Where the confirm sheet was opened from: an alert event or a PC proposal. */
data class EventPrefill(
    val title: String,
    val startIso: String,
    val endIso: String,
    val allDay: Boolean = false,
    val location: String = "",
    val calendarId: String? = null,
)

sealed interface CalendarDraftResult {
    data class Valid(val values: CalendarInsertValues) : CalendarDraftResult
    data class Invalid(val message: String) : CalendarDraftResult
}

/**
 * Pure add-event logic: field parsing, validation and the exact DTSTART/DTEND
 * an insert needs.
 *
 * Timed events use the device zone. All-day events use UTC midnight boundaries
 * because that is what `CalendarContract` stores for them; using the device
 * zone would shift the event a day west of UTC.
 */
object CalendarEventBuilder {

    const val MAX_TITLE_CHARS = 200
    const val MAX_LOCATION_CHARS = 200

    /** Prefills from an alert's extracted event. */
    fun prefillFor(event: com.idupi.app.domain.model.InboxEvent): EventPrefill =
        EventPrefill(
            title = event.title,
            startIso = event.start,
            endIso = event.end,
            allDay = event.allDay,
            location = event.location,
        )

    /** Prefills from a PC proposal; its calendar is only a hint. */
    fun prefillFor(proposal: PhoneCalendarProposal): EventPrefill =
        EventPrefill(
            title = proposal.title,
            startIso = proposal.start,
            endIso = proposal.end,
            allDay = proposal.allDay,
            location = proposal.location,
            calendarId = proposal.calendarId,
        )

    /**
     * Default calendar: the prefilled one, then the last one used, then the
     * first Google calendar, then the first writable one.
     */
    fun defaultCalendarId(
        calendars: List<PhoneCalendarRef>,
        preferredId: String?,
        lastUsedId: String?,
    ): String {
        val writable = calendars.filter { it.writable }
        val candidates = listOfNotNull(preferredId, lastUsedId)
        candidates.firstOrNull { candidate -> writable.any { it.id == candidate } }
            ?.let { return it }
        writable.firstOrNull { it.accountType == "com.google" }?.let { return it.id }
        return writable.firstOrNull()?.id.orEmpty()
    }

    /** Converts an existing event/proposal into the editable draft. */
    fun toDraft(
        prefill: EventPrefill,
        calendars: List<PhoneCalendarRef>,
        lastUsedId: String?,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone),
    ): CalendarEventDraft {
        val startMillis = CalendarOverlap.parseInstant(prefill.startIso)
        val endMillis = CalendarOverlap.parseInstant(prefill.endIso)
        val date = when {
            startMillis == null -> today
            prefill.allDay -> Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            else -> Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        }
        val startTime = when {
            prefill.allDay || startMillis == null -> "09:00"
            else -> localTime(Instant.ofEpochMilli(startMillis).atZone(zone).toLocalTime())
        }
        val endTime = when {
            prefill.allDay || endMillis == null -> "10:00"
            else -> localTime(Instant.ofEpochMilli(endMillis).atZone(zone).toLocalTime())
        }
        return CalendarEventDraft(
            title = prefill.title.take(MAX_TITLE_CHARS),
            date = date.toString(),
            startTime = startTime,
            endTime = endTime,
            allDay = prefill.allDay,
            location = prefill.location.take(MAX_LOCATION_CHARS),
            calendarId = defaultCalendarId(calendars, prefill.calendarId, lastUsedId),
        )
    }

    /** Validates the draft and builds the insert values, or explains the first error. */
    fun build(
        draft: CalendarEventDraft,
        calendars: List<PhoneCalendarRef>,
        zone: ZoneId,
    ): CalendarDraftResult {
        val title = draft.title.trim()
        if (title.isEmpty()) return CalendarDraftResult.Invalid("Escribe un título para el evento.")
        val calendar = calendars.firstOrNull { it.id == draft.calendarId }
            ?: return CalendarDraftResult.Invalid("Elige un calendario.")
        if (!calendar.writable) {
            return CalendarDraftResult.Invalid("Ese calendario no permite cambios.")
        }
        val date = parseDate(draft.date)
            ?: return CalendarDraftResult.Invalid("La fecha debe tener el formato AAAA-MM-DD.")

        val location = draft.location.trim().take(MAX_LOCATION_CHARS)
        if (draft.allDay) {
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return CalendarDraftResult.Valid(
                CalendarInsertValues(
                    calendarId = calendar.id,
                    title = title.take(MAX_TITLE_CHARS),
                    startMillis = start,
                    endMillis = end,
                    allDay = true,
                    timeZoneId = "UTC",
                    location = location,
                )
            )
        }

        val start = parseTime(draft.startTime)
            ?: return CalendarDraftResult.Invalid(
                "La hora de inicio debe tener el formato HH:MM."
            )
        val end = parseTime(draft.endTime)
            ?: return CalendarDraftResult.Invalid(
                "La hora de fin debe tener el formato HH:MM."
            )
        if (!end.isAfter(start)) {
            return CalendarDraftResult.Invalid(
                "La hora de fin debe ser después de la hora de inicio."
            )
        }
        val startMillis = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val endMillis = date.atTime(end).atZone(zone).toInstant().toEpochMilli()
        return CalendarDraftResult.Valid(
            CalendarInsertValues(
                calendarId = calendar.id,
                title = title.take(MAX_TITLE_CHARS),
                startMillis = startMillis,
                endMillis = endMillis,
                allDay = false,
                timeZoneId = zone.id,
                location = location,
            )
        )
    }

    /** `AAAA-MM-DD`, or null when the text is not a real date. */
    fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    }

    /** `H:mm` / `HH:mm`, or null outside 00:00..23:59. */
    fun parseTime(text: String): LocalTime? {
        val match = TIME_PATTERN.matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return LocalTime.of(hours, minutes)
    }

    /** "09:05" with Latin digits regardless of the device locale. */
    fun formatTime(time: LocalTime): String =
        String.format(Locale.US, "%02d:%02d", time.hour, time.minute)

    private fun localTime(time: LocalTime): String = formatTime(time)

    private val TIME_PATTERN = Regex("^(\\d{1,2}):(\\d{2})$")
}
