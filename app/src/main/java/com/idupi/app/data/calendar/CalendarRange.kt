package com.idupi.app.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Local millis window `[startMillis, endExclusiveMillis)` for a provider query. */
data class CalendarQuery(val startMillis: Long, val endExclusiveMillis: Long)

/**
 * Visible calendar ranges (phone-calendar T5): Monday-first weeks and the full
 * month grid, including the leading/trailing days of the adjacent months.
 *
 * Pure so the grid shape and the provider query window are unit-tested without
 * a device. Weeks always start on Monday because that is the owner's calendar
 * convention; [weekStart] normalizes any date into that week.
 */
object CalendarRange {

    fun weekStart(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** Monday through Sunday of the week that contains [anchor]. */
    fun weekDates(anchor: LocalDate): List<LocalDate> {
        val monday = weekStart(anchor)
        return (0L until DAYS_PER_WEEK).map { monday.plusDays(it) }
    }

    /**
     * Rows of the month grid: whole Monday-first weeks that cover the month, so
     * the result is 5 weeks for a month that fits and 6 otherwise.
     */
    fun monthWeeks(month: YearMonth): List<List<LocalDate>> {
        val gridStart = weekStart(month.atDay(1))
        val gridEnd = weekStart(month.atEndOfMonth()).plusDays(DAYS_PER_WEEK - 1)
        val weeks = mutableListOf<List<LocalDate>>()
        var cursor = gridStart
        while (!cursor.isAfter(gridEnd)) {
            weeks += (0L until DAYS_PER_WEEK).map { cursor.plusDays(it) }
            cursor = cursor.plusWeeks(1)
        }
        // A 28-day month that starts on Monday would otherwise render four
        // rows; the month view keeps the familiar five-or-six row shape.
        while (weeks.size < MIN_WEEKS) {
            weeks += (0L until DAYS_PER_WEEK).map { cursor.plusDays(it) }
            cursor = cursor.plusWeeks(1)
        }
        return weeks
    }

    /** Every cell of the month grid, in reading order. */
    fun monthDates(month: YearMonth): List<LocalDate> = monthWeeks(month).flatten()

    /**
     * Provider window for the given visible dates: local start of the first day
     * through local start of the day after the last one. Null for no dates.
     */
    fun query(dates: List<LocalDate>, zone: ZoneId): CalendarQuery? {
        if (dates.isEmpty()) return null
        val first = dates.minOrNull() ?: return null
        val last = dates.maxOrNull() ?: return null
        return CalendarQuery(
            startMillis = first.atStartOfDay(zone).toInstant().toEpochMilli(),
            endExclusiveMillis = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    private const val DAYS_PER_WEEK = 7L
    private const val MIN_WEEKS = 5
}
