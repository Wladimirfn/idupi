package com.idupi.app.ui.screens

import com.idupi.app.data.calendar.CalendarOverlap
import com.idupi.app.data.calendar.CalendarReportState
import com.idupi.app.domain.model.InboxEvent
import com.idupi.app.domain.model.InboxEventConflict
import com.idupi.app.domain.model.PhoneCalendarConflictRef
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure Spanish formatting for the calendar surfaces (phone-calendar T2/T3).
 * UI copy is neutral Latin American Spanish, so it stays testable without a
 * device and never depends on the JVM's default locale.
 */
private val WEEKDAYS = arrayOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
private val MONTHS = arrayOf(
    "ene", "feb", "mar", "abr", "may", "jun",
    "jul", "ago", "sep", "oct", "nov", "dic",
)

/** "lun 25 nov"; deterministic, no Locale dependency. */
fun shortDateLabel(date: LocalDate): String =
    "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

private val SPANISH_LOCALE: Locale = Locale.forLanguageTag("es-419")
private val MONTH_TITLE_FORMAT = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", SPANISH_LOCALE)
private val DAY_MONTH_FORMAT = DateTimeFormatter.ofPattern("d 'de' MMMM", SPANISH_LOCALE)
private val FULL_DATE_FORMAT = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH_LOCALE)

/** "Noviembre de 2026" for the month header. */
fun calendarMonthTitle(month: YearMonth): String =
    month.atDay(1).format(MONTH_TITLE_FORMAT)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(SPANISH_LOCALE) else it.toString() }

/** Monday-first weekday labels: "lun", "mar", ... "dom". */
fun weekdayShortLabels(): List<String> =
    (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, SPANISH_LOCALE).lowercase(SPANISH_LOCALE) }

/** Week header: "23 – 29 de noviembre de 2026", compact across months and years. */
fun calendarWeekTitle(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    return when {
        monday.year != sunday.year ->
            "${monday.format(FULL_DATE_FORMAT)} – ${sunday.format(FULL_DATE_FORMAT)}"
        monday.month == sunday.month ->
            "${monday.dayOfMonth} – ${sunday.dayOfMonth} de ${monthName(sunday.month)} de ${sunday.year}"
        else ->
            "${monday.format(DAY_MONTH_FORMAT)} – ${sunday.format(DAY_MONTH_FORMAT)} de ${sunday.year}"
    }
}

private fun monthName(month: Month): String = month.getDisplayName(TextStyle.FULL, SPANISH_LOCALE)

/** "Hoy", "Mañana" or "lun 25 nov". */
fun dayHeadingLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Hoy"
    today.plusDays(1) -> "Mañana"
    else -> shortDateLabel(date)
}

/** Clock in the device zone: "13:05". Null when the instant does not parse. */
fun clockLabel(iso: String?, zone: ZoneId): String? {
    val millis = CalendarOverlap.parseInstant(iso) ?: return null
    val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
    return String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
}

/** "13:00–14:30" for timed events, "todo el día" for all-day ones. */
fun timeRangeLabel(startIso: String, endIso: String, allDay: Boolean, zone: ZoneId): String {
    if (allDay) return "todo el día"
    val start = clockLabel(startIso, zone) ?: return ""
    val end = clockLabel(endIso, zone) ?: return start
    return "$start–$end"
}

/** Row time in the Agenda list; the day is already the section header. */
fun agendaEventWhen(event: PhoneCalendarEvent, zone: ZoneId): String =
    timeRangeLabel(event.start, event.end, event.allDay, zone)

/** "Hoy 13:00–14:30" / "mié 26 nov todo el día". */
fun proposalWhenLabel(proposal: PhoneCalendarProposal, zone: ZoneId, nowMillis: Long): String {
    val startMillis = CalendarOverlap.parseInstant(proposal.start)
    val date = when {
        startMillis == null -> null
        proposal.allDay -> Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        else -> Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    } ?: return ""
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val heading = dayHeadingLabel(date, today)
    val whenPart = timeRangeLabel(proposal.start, proposal.end, proposal.allDay, zone)
    return if (whenPart.isBlank()) heading else "$heading $whenPart"
}

/** Card chip: "📅 lun 25 nov 13:00 · Reunión". */
fun inboxEventChipLabel(event: InboxEvent, zone: ZoneId): String {
    val startMillis = CalendarOverlap.parseInstant(event.start)
    val day = when {
        startMillis == null -> ""
        event.allDay -> {
            val date = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            " ${shortDateLabel(date)}"
        }
        else -> {
            val local = Instant.ofEpochMilli(startMillis).atZone(zone)
            " ${shortDateLabel(local.toLocalDate())}"
        }
    }
    val whenPart = if (event.allDay) {
        "todo el día"
    } else {
        clockLabel(event.start, zone).orEmpty()
    }
    val title = event.title.ifBlank { "Evento sin título" }
    return "📅$day $whenPart · $title"
}

/** "Choca con: Reunión (13:00); Otro (14:30)"; null when nothing overlaps. */
fun inboxConflictLine(conflicts: List<InboxEventConflict>, zone: ZoneId): String? {
    if (conflicts.isEmpty()) return null
    val entries = conflicts.map { conflict ->
        val title = conflict.title.ifBlank { "Otro evento" }
        val time = clockLabel(conflict.start, zone)
        if (time == null) title else "$title ($time)"
    }
    return "Choca con: ${entries.joinToString("; ")}"
}

/** "Choca con: Reunión (13:00), Otro (14:30)" for local Agenda rows. */
fun agendaConflictLine(conflicts: List<PhoneCalendarEvent>, zone: ZoneId): String? =
    conflictLine(conflicts.map { it.title to it.start }, zone)

/** Same line for the server's proposal conflicts. */
fun proposalConflictLine(conflicts: List<PhoneCalendarConflictRef>, zone: ZoneId): String? =
    conflictLine(conflicts.map { it.title to it.start }, zone)

private fun conflictLine(conflicts: List<Pair<String, String>>, zone: ZoneId): String? {
    if (conflicts.isEmpty()) return null
    val entries = conflicts.map { (title, start) ->
        val name = title.ifBlank { "Otro evento" }
        val time = clockLabel(start, zone)
        if (time == null) name else "$name ($time)"
    }
    return "Choca con: ${entries.joinToString(", ")}"
}

/** Ajustes label: last calendar report time and result. */
fun calendarLastReportLabel(state: CalendarReportState, nowMillis: Long): String {
    val attempt = state.lastAttemptAtMillis ?: return "Último envío: ninguno todavía"
    val whenPart = relativeTimeLabel(attempt, nowMillis)
    return when (state.success) {
        true -> {
            val events = if (state.eventCount == 1) "1 evento" else "${state.eventCount} eventos"
            "Último envío: ok $whenPart ($events)"
        }
        false -> "Último envío: falló $whenPart"
        null -> "Último envío: ninguno todavía"
    }
}
