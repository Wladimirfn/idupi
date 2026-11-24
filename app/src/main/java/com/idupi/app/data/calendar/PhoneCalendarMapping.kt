package com.idupi.app.data.calendar

import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.model.PhoneCalendarSnapshot
import java.time.Instant
import java.util.Locale

/**
 * One calendar row read from `CalendarContract.Calendars`.
 *
 * Android-free on purpose: this project has no Robolectric, so everything that
 * decides what leaves the phone is a pure function that JVM tests can drive.
 */
data class CalendarRow(
    val id: String,
    val name: String,
    val accountType: String,
    val accountName: String,
    val colorArgb: Int?,
    val accessLevel: Int,
)

/**
 * One instance row read from `CalendarContract.Instances`. Recurring events
 * appear once per occurrence, which is exactly what the reporter must send.
 */
data class CalendarInstanceRow(
    val eventId: String,
    val calendarId: String,
    val title: String?,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val status: Int,
    val deleted: Boolean,
)

/**
 * Pure mapping from provider rows to the snapshot the PC receives.
 *
 * Caps and clipping are repeated here from the server contract on purpose: the
 * server rejects an oversized upload with a 400, and a single long subject must
 * not cost the owner the whole agenda report.
 */
object CalendarSnapshotMapping {

    const val MAX_CALENDARS = 50
    const val MAX_EVENTS = 1000
    const val MAX_TITLE_CHARS = 200
    const val MAX_LOCATION_CHARS = 200
    const val MAX_DESCRIPTION_CHARS = 500
    const val MAX_CALENDAR_NAME_CHARS = 120
    const val MAX_ACCOUNT_TYPE_CHARS = 40
    const val MAX_ACCOUNT_NAME_CHARS = 120
    const val MAX_COLOR_CHARS = 32

    /** `CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR`, kept Android-free. */
    const val CAL_ACCESS_CONTRIBUTOR = 500

    /** `CalendarContract.Events.STATUS_CANCELED`, kept Android-free. */
    const val STATUS_CANCELED = 2

    const val REPORT_DAYS = 14L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** The owner may write when the provider grants contributor or better. */
    fun isWritable(accessLevel: Int): Boolean = accessLevel >= CAL_ACCESS_CONTRIBUTOR

    /** `#RRGGBB` for the wire; null when the provider had no color. */
    fun colorHex(argb: Int?): String? =
        argb?.let { String.format(Locale.US, "#%06X", it and 0xFFFFFF) }

    /**
     * Calendar rows to report, sorted by name and capped. A null/blank name is
     * kept as-is; the UI shows a fallback.
     */
    fun toCalendarRefs(rows: List<CalendarRow>): List<PhoneCalendarRef> = rows
        .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.id }))
        .take(MAX_CALENDARS)
        .map { row ->
            PhoneCalendarRef(
                id = row.id.take(MAX_CALENDARS * 4),
                name = row.name.take(MAX_CALENDAR_NAME_CHARS),
                accountType = row.accountType.take(MAX_ACCOUNT_TYPE_CHARS),
                accountName = row.accountName.take(MAX_ACCOUNT_NAME_CHARS),
                color = colorHex(row.colorArgb)?.take(MAX_COLOR_CHARS),
                writable = isWritable(row.accessLevel),
            )
        }

    /**
     * Maps provider instances to wire events, dropping cancelled/deleted rows
     * and rows of calendars that are not part of the snapshot.
     *
     * All-day rows keep the provider's UTC boundaries: `Instant.toString()`
     * already yields `...T00:00:00Z`, which is the ISO date the server expects.
     * Recurring occurrences share `EVENT_ID`, so the instance begin is part of
     * the reported id to keep it unique.
     */
    fun toEvents(
        rows: List<CalendarInstanceRow>,
        calendarIds: Set<String> = emptySet(),
    ): List<PhoneCalendarEvent> = rows.asSequence()
        .filter { row ->
            !row.deleted &&
                row.status != STATUS_CANCELED &&
                row.endMillis >= row.beginMillis &&
                (calendarIds.isEmpty() || row.calendarId in calendarIds)
        }
        .map { row ->
            PhoneCalendarEvent(
                id = "${row.eventId}@${row.beginMillis}".take(MAX_TITLE_CHARS),
                calendarId = row.calendarId,
                title = row.title.orEmpty().take(MAX_TITLE_CHARS),
                start = Instant.ofEpochMilli(row.beginMillis).toString(),
                end = Instant.ofEpochMilli(row.endMillis).toString(),
                allDay = row.allDay,
                location = row.location.orEmpty().take(MAX_LOCATION_CHARS),
                description = row.description.orEmpty().take(MAX_DESCRIPTION_CHARS),
            )
        }
        .sortedWith(compareBy({ it.start }, { it.id }))
        .toList()

    /** Builds the exact body `POST /api/v1/phone/calendar` expects. */
    fun buildSnapshot(
        nowMillis: Long,
        tzOffsetMinutes: Int,
        calendars: List<CalendarRow>,
        instances: List<CalendarInstanceRow>,
    ): PhoneCalendarSnapshot {
        val refs = toCalendarRefs(calendars)
        val events = toEvents(instances, refs.mapTo(mutableSetOf()) { it.id })
            .take(MAX_EVENTS)
        return PhoneCalendarSnapshot(
            capturedAt = Instant.ofEpochMilli(nowMillis).toString(),
            rangeStart = Instant.ofEpochMilli(nowMillis).toString(),
            rangeEnd = Instant.ofEpochMilli(nowMillis + REPORT_DAYS * DAY_MS).toString(),
            tzOffsetMinutes = tzOffsetMinutes,
            calendars = refs,
            events = events,
        )
    }

    /** Query window for the reporter: now through [REPORT_DAYS] later. */
    fun reportWindow(nowMillis: Long): LongRange =
        nowMillis..(nowMillis + REPORT_DAYS * DAY_MS)
}
