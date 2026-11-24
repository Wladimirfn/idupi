package com.idupi.app.data.calendar

/** Runtime calendar permissions, as the platform reports them right now. */
data class CalendarPermissionState(
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
) {
    val granted: Boolean get() = canRead && canWrite
}
