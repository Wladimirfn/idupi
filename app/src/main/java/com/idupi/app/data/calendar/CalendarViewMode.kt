package com.idupi.app.data.calendar

import java.util.Locale

/**
 * How the Agenda screen shows the calendar (phone-calendar T5).
 *
 * [storageValue] is what the settings DataStore keeps, so a rename of the enum
 * constant never loses the owner's last choice. [label] is the neutral Spanish
 * label of the segmented selector.
 */
enum class CalendarViewMode(val storageValue: String, val label: String) {
    LIST("list", "Lista"),
    WEEK("week", "Semana"),
    MONTH("month", "Mes"),
    ;

    companion object {
        val DEFAULT = MONTH

        /** Parses a stored value; anything unknown falls back to [DEFAULT]. */
        fun fromStorage(raw: String?): CalendarViewMode {
            val wanted = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.storageValue == wanted } ?: DEFAULT
        }
    }
}
