package com.idupi.app.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.model.PhoneCalendarSnapshot
import java.util.TimeZone

/**
 * Android adapter over `CalendarContract`: one source for Google, Samsung and
 * Outlook-synced calendars, without a cloud API or OAuth.
 *
 * Every platform call is guarded: a revoked permission or a ROM quirk leaves
 * the snapshot null / the list empty instead of crashing a service.
 */
class AndroidCalendarStore(private val context: Context) : CalendarStore {

    override fun canRead(): Boolean = hasPermission(Manifest.permission.READ_CALENDAR)

    override fun canWrite(): Boolean = hasPermission(Manifest.permission.WRITE_CALENDAR)

    override fun readSnapshot(nowMillis: Long): PhoneCalendarSnapshot? {
        if (!canRead()) return null
        return runCatching {
            val calendars = queryCalendars()
            val ids = calendars.mapTo(mutableSetOf()) { it.id }
            val instances = if (ids.isEmpty()) {
                emptyList()
            } else {
                queryInstances(
                    startMillis = nowMillis,
                    endMillis = CalendarSnapshotMapping.reportWindow(nowMillis).last,
                )
            }
            CalendarSnapshotMapping.buildSnapshot(
                nowMillis = nowMillis,
                tzOffsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60_000,
                calendars = calendars,
                instances = instances,
            )
        }.getOrNull()
    }

    override fun writableCalendars(): List<PhoneCalendarRef> {
        if (!canRead()) return emptyList()
        return runCatching {
            queryCalendars()
                .filter { CalendarSnapshotMapping.isWritable(it.accessLevel) }
                .let(CalendarSnapshotMapping::toCalendarRefs)
                .sortedWith(compareBy({ it.accountType }, { it.name }, { it.id }))
        }.getOrDefault(emptyList())
    }

    override fun visibleCalendars(): List<PhoneCalendarRef> {
        if (!canRead()) return emptyList()
        return runCatching {
            CalendarSnapshotMapping.toCalendarRefs(queryCalendars())
        }.getOrDefault(emptyList())
    }

    override fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneCalendarEvent> {
        if (!canRead()) return emptyList()
        if (endMillis <= startMillis) return emptyList()
        return runCatching {
            val ids = queryCalendars().mapTo(mutableSetOf()) { it.id }
            if (ids.isEmpty()) {
                emptyList()
            } else {
                CalendarSnapshotMapping.toEvents(
                    rows = queryInstances(startMillis, endMillis),
                    calendarIds = ids,
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun insertEvent(values: CalendarInsertValues): String? {
        if (!canWrite()) return null
        val calendarId = values.calendarId.toLongOrNull() ?: return null
        return runCatching {
            val contentValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, values.title)
                put(CalendarContract.Events.DTSTART, values.startMillis)
                put(CalendarContract.Events.DTEND, values.endMillis)
                put(CalendarContract.Events.ALL_DAY, if (values.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, values.timeZoneId)
                put(CalendarContract.Events.EVENT_END_TIMEZONE, values.timeZoneId)
                // A local insert must not buzz the owner on every report.
                put(CalendarContract.Events.HAS_ALARM, 0)
                if (values.location.isNotBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, values.location)
                }
            }
            val uri = context.contentResolver
                .insert(CalendarContract.Events.CONTENT_URI, contentValues)
                ?: return null
            uri.lastPathSegment
        }.getOrNull()
    }

    // --- Provider queries ------------------------------------------------

    private fun queryCalendars(): List<CalendarRow> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.DELETED,
        )
        val rows = mutableListOf<CalendarRow>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex =
                cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val colorIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
            val accessIndex =
                cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val deletedIndex = cursor.getColumnIndex(CalendarContract.Calendars.DELETED)
            while (cursor.moveToNext()) {
                val deleted = deletedIndex >= 0 && cursor.getInt(deletedIndex) != 0
                if (deleted) continue
                rows += CalendarRow(
                    id = cursor.getLong(idIndex).toString(),
                    name = cursor.getString(nameIndex).orEmpty(),
                    accountType = cursor.getString(typeIndex).orEmpty(),
                    accountName = cursor.getString(accountIndex).orEmpty(),
                    colorArgb = if (colorIndex >= 0 && !cursor.isNull(colorIndex)) {
                        cursor.getInt(colorIndex)
                    } else {
                        null
                    },
                    accessLevel = cursor.getInt(accessIndex),
                )
            }
        }
        return rows
    }

    private fun queryInstances(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarInstanceRow> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMillis)
            ContentUris.appendId(this, endMillis)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.STATUS,
        )
        val rows = mutableListOf<CalendarInstanceRow>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val eventIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val descriptionIndex = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            val statusIndex = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
            while (cursor.moveToNext()) {
                rows += CalendarInstanceRow(
                    eventId = cursor.getLong(eventIndex).toString(),
                    calendarId = cursor.getLong(calendarIndex).toString(),
                    title = if (titleIndex >= 0 && !cursor.isNull(titleIndex)) {
                        cursor.getString(titleIndex)
                    } else {
                        null
                    },
                    beginMillis = cursor.getLong(beginIndex),
                    endMillis = cursor.getLong(endIndex),
                    allDay = cursor.getInt(allDayIndex) != 0,
                    location = if (locationIndex >= 0 && !cursor.isNull(locationIndex)) {
                        cursor.getString(locationIndex)
                    } else {
                        null
                    },
                    description = if (descriptionIndex >= 0 && !cursor.isNull(descriptionIndex)) {
                        cursor.getString(descriptionIndex)
                    } else {
                        null
                    },
                    status = if (statusIndex >= 0) cursor.getInt(statusIndex) else 0,
                    deleted = false,
                )
            }
        }
        return rows
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
