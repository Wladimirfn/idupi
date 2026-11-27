package com.idupi.app.data.phone.approval

import android.content.Context

/**
 * Resolves an installed package's display label for approval copy
 * (phone-shell T4b). A missing or hidden app falls back to null and the copy
 * shows the package id instead.
 */
object AppLabels {

    @Suppress("DEPRECATION")
    fun resolver(context: Context): (String) -> String? {
        val packageManager = context.applicationContext.packageManager
        return { packageName ->
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString().takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
