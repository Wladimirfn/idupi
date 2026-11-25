package com.idupi.app.data.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Owner-local day math for the health history (phone-health T2).
 *
 * The PC stores one sample per owner-local day, keyed by the phone's
 * `tzOffsetMinutes`. Everything here is pure and offset-driven on purpose: the
 * phone and the PC can be in different time zones, and a device whose clock
 * crosses midnight must not file today's steps under tomorrow's date.
 */
object HealthDays {

    /** How many days the phone uploads on every report. */
    const val HISTORY_DAYS = 7

    /** `YYYY-MM-DD` of the owner-local day that contains [epochMillis]. */
    fun ownerDayKey(epochMillis: Long, tzOffsetMinutes: Int): String =
        Instant.ofEpochMilli(epochMillis + tzOffsetMinutes * 60_000L)
            .atOffset(ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    /** The last [count] day keys, oldest first, ending at [todayKey]. */
    fun lastNDays(todayKey: String, count: Int): List<String> {
        val today = LocalDate.parse(todayKey)
        return (count - 1 downTo 0).map { offset ->
            today.minusDays(offset.toLong()).toString()
        }
    }

    /** First millisecond of the owner-local day [dayKey]. */
    fun dayStartMillis(dayKey: String, tzOffsetMinutes: Int): Long =
        LocalDate.parse(dayKey)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli() - tzOffsetMinutes * 60_000L

    /** First millisecond of the day after [dayKey] (exclusive window end). */
    fun dayEndMillis(dayKey: String, tzOffsetMinutes: Int): Long =
        dayStartMillis(LocalDate.parse(dayKey).plusDays(1).toString(), tzOffsetMinutes)

    /** The phone's UTC offset in minutes at [epochMillis]. */
    fun offsetMinutesAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        (Instant.ofEpochMilli(epochMillis).atZone(zone).offset.totalSeconds / 60)

    /** ISO-8601 instant for the wire (`capturedAt`, `sleepStart`, ...). */
    fun isoInstant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
}
