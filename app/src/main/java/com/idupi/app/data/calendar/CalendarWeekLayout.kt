package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * One timed event placed in the week grid: [topMinutes] and [heightMinutes] are
 * measured from the grid's start hour, and [column]/[columnCount] split
 * overlapping events side by side.
 */
data class CalendarWeekBlock(
    val event: PhoneCalendarEvent,
    val date: LocalDate,
    val topMinutes: Int,
    val heightMinutes: Int,
    val column: Int,
    val columnCount: Int,
)

/**
 * Layout math for the Agenda week view (phone-calendar T5).
 *
 * Timed events are clipped to the day and to the visible [startHour]..
 * [endHour] window, then packed into columns by the standard interval
 * partitioning rule: events of one connected overlap cluster share the same
 * number of columns, and touching endpoints (one ends at 14:00, the next starts
 * at 14:00) never share a column because they do not overlap.
 */
object CalendarWeekLayout {

    const val START_HOUR = 7
    const val END_HOUR = 22

    fun blocks(
        eventsByDay: Map<LocalDate, List<PhoneCalendarEvent>>,
        zone: ZoneId = ZoneId.systemDefault(),
        startHour: Int = START_HOUR,
        endHour: Int = END_HOUR,
    ): List<CalendarWeekBlock> {
        if (endHour <= startHour) return emptyList()
        val gridStartMinute = startHour * MINUTES_PER_HOUR
        val gridEndMinute = endHour * MINUTES_PER_HOUR
        val result = mutableListOf<CalendarWeekBlock>()

        for ((date, events) in eventsByDay) {
            val dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val drafts = mutableListOf<Draft>()
            for (event in events) {
                if (event.allDay) continue
                val start = CalendarOverlap.parseInstant(event.start) ?: continue
                val end = CalendarOverlap.parseInstant(event.end) ?: continue
                if (end <= start) continue
                val clippedStart = maxOf(start, dayStartMillis)
                val clippedEnd = minOf(end, dayEndMillis)
                if (clippedEnd <= clippedStart) continue
                val startMinute = ((clippedStart - dayStartMillis) / MILLIS_PER_MINUTE).toInt()
                val endMinute = ((clippedEnd - dayStartMillis) / MILLIS_PER_MINUTE).toInt()
                val top = maxOf(startMinute, gridStartMinute)
                val bottom = minOf(endMinute, gridEndMinute)
                if (bottom <= top) continue
                drafts += Draft(
                    event = event,
                    date = date,
                    topMinutes = top - gridStartMinute,
                    heightMinutes = bottom - top,
                )
            }
            val ordered = drafts.sortedWith(
                compareBy({ it.topMinutes }, { -it.heightMinutes }, { it.event.id })
            )
            result += assignColumns(ordered)
        }
        return result
    }

    /**
     * Interval partitioning per connected overlap cluster: a block takes the
     * first column free at its start, and every block of the cluster reports the
     * cluster's width so the UI can draw them side by side.
     */
    private fun assignColumns(drafts: List<Draft>): List<CalendarWeekBlock> {
        if (drafts.isEmpty()) return emptyList()
        val result = ArrayList<CalendarWeekBlock>(drafts.size)
        var clusterStart = 0
        while (clusterStart < drafts.size) {
            var clusterEnd = clusterStart
            var clusterBottom = drafts[clusterStart].bottomMinutes()
            while (
                clusterEnd + 1 < drafts.size &&
                drafts[clusterEnd + 1].topMinutes < clusterBottom
            ) {
                clusterEnd++
                clusterBottom = maxOf(clusterBottom, drafts[clusterEnd].bottomMinutes())
            }

            val columnEnds = mutableListOf<Int>()
            val columns = IntArray(clusterEnd - clusterStart + 1)
            for (index in clusterStart..clusterEnd) {
                val draft = drafts[index]
                var column = columnEnds.indexOfFirst { it <= draft.topMinutes }
                if (column < 0) {
                    column = columnEnds.size
                    columnEnds += draft.bottomMinutes()
                } else {
                    columnEnds[column] = draft.bottomMinutes()
                }
                columns[index - clusterStart] = column
            }
            for (index in clusterStart..clusterEnd) {
                val draft = drafts[index]
                result += CalendarWeekBlock(
                    event = draft.event,
                    date = draft.date,
                    topMinutes = draft.topMinutes,
                    heightMinutes = draft.heightMinutes,
                    column = columns[index - clusterStart],
                    columnCount = columnEnds.size,
                )
            }
            clusterStart = clusterEnd + 1
        }
        return result
    }

    private data class Draft(
        val event: PhoneCalendarEvent,
        val date: LocalDate,
        val topMinutes: Int,
        val heightMinutes: Int,
    ) {
        fun bottomMinutes(): Int = topMinutes + heightMinutes
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MILLIS_PER_MINUTE = 60_000L
}
