package com.idupi.app.data.phone.control

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Accessibility grant state for the control service (phone-agent T3).
 *
 * Accessibility is a special access granted in the system settings, not a
 * runtime permission. The system exposes it as a colon-separated list of
 * enabled `package/class` components in [Settings.Secure]. The pure
 * [isEnabled] half is unit-tested; the Android half only reads and builds the
 * intent to open the settings screen.
 */
object AccessibilityStatus {

    /**
     * True when [componentName] appears in the platform's enabled-services
     * string. Matching is case-insensitive and tolerates surrounding spaces,
     * because some ROMs rewrite the list with different casing.
     */
    fun isEnabled(enabledServices: String?, componentName: String): Boolean {
        if (enabledServices.isNullOrBlank() || componentName.isBlank()) return false
        return enabledServices
            .split(':')
            .any { it.trim().equals(componentName.trim(), ignoreCase = true) }
    }
}

/** The `package/class` component the system lists once accessibility is granted. */
fun accessibilityControlComponent(context: Context): String =
    "${context.packageName}/${IdupiAccessibilityService::class.java.name}"

/** True when the owner granted accessibility to this app's control service. */
fun isAccessibilityControlEnabled(context: Context): Boolean {
    val enabledServices = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    } catch (e: Exception) {
        null
    }
    return AccessibilityStatus.isEnabled(enabledServices, accessibilityControlComponent(context))
}

/** The system screen where the owner grants accessibility. */
fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
