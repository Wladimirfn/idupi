package com.idupi.app.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Runtime calendar permissions, checked at the point of use. */
object CalendarPermissions {

    /** Both permissions are requested together: the feature reads and writes. */
    val REQUEST: Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun hasRead(context: Context): Boolean = granted(context, Manifest.permission.READ_CALENDAR)

    fun hasWrite(context: Context): Boolean = granted(context, Manifest.permission.WRITE_CALENDAR)

    fun current(context: Context): CalendarPermissionState = CalendarPermissionState(
        canRead = hasRead(context),
        canWrite = hasWrite(context),
    )

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
