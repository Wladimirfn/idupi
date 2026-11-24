package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.model.PhoneCalendarSnapshot

/** Values an insert needs; built by the pure event builder, written by the adapter. */
data class CalendarInsertValues(
    val calendarId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZoneId: String,
    val location: String = "",
)

/**
 * The calendar surface the app needs. [AndroidCalendarStore] is the only
 * production implementation; tests and ViewModels use fakes of this interface.
 */
interface CalendarStore {

    /** `READ_CALENDAR` is granted right now. */
    fun canRead(): Boolean

    /** `WRITE_CALENDAR` is granted right now. */
    fun canWrite(): Boolean

    /** Snapshot for the next 14 days, or null without permission / on failure. */
    fun readSnapshot(nowMillis: Long): PhoneCalendarSnapshot?

    /** Visible calendars the owner may write to, sorted by account then name. */
    fun writableCalendars(): List<PhoneCalendarRef>

    /**
     * Every visible calendar, for the UI's names and colors (phone-calendar
     * T5). Sorted by name; empty without read permission.
     */
    fun visibleCalendars(): List<PhoneCalendarRef>

    /** Visible events overlapping `[startMillis, endMillis]`, for conflict checks. */
    fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneCalendarEvent>

    /**
     * Inserts one event through `CalendarContract.Events`.
     *
     * @return the provider's event id, or null when the insert failed (no
     *   permission, unknown calendar, provider error).
     */
    fun insertEvent(values: CalendarInsertValues): String?
}
