package com.idupi.app

import com.idupi.app.data.calendar.CalendarInsertValues
import com.idupi.app.data.calendar.CalendarStore
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.model.PhoneCalendarSnapshot

/**
 * In-memory [CalendarStore] for JVM tests: no Android provider, every call
 * overridable, and the inserted values are recorded for assertions.
 */
class FakeCalendarStore(
    var canRead: Boolean = true,
    var canWrite: Boolean = true,
    var snapshot: PhoneCalendarSnapshot? = null,
    var writable: List<PhoneCalendarRef> = emptyList(),
    var localEvents: List<PhoneCalendarEvent> = emptyList(),
    var insertResult: String? = "ev-inserted",
) : CalendarStore {

    val inserted = mutableListOf<CalendarInsertValues>()
    var insertCalls: Int = 0
    var readSnapshotCalls: Int = 0
    val eventWindowQueries = mutableListOf<Pair<Long, Long>>()

    override fun canRead(): Boolean = canRead

    override fun canWrite(): Boolean = canWrite

    override fun readSnapshot(nowMillis: Long): PhoneCalendarSnapshot? {
        readSnapshotCalls++
        return if (canRead) snapshot else null
    }

    override fun writableCalendars(): List<PhoneCalendarRef> =
        if (canRead) writable.filter { it.writable } else emptyList()

    override fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneCalendarEvent> {
        eventWindowQueries += startMillis to endMillis
        return localEvents
    }

    override fun insertEvent(values: CalendarInsertValues): String? {
        insertCalls++
        if (!canWrite) return null
        inserted += values
        return insertResult
    }
}
