package com.idupi.app.data.phone.bubble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The one runtime permission in-bubble dictation needs (phone-bubble T11).
 *
 * The overlay cannot request permissions itself (it lives in an accessibility
 * service), so it checks this from [MicPermissionActivity] and from Ajustes.
 */
object MicPermission {

    const val PERMISSION = Manifest.permission.RECORD_AUDIO

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
}
