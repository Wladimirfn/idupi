package com.idupi.app.data.phone.shell

import android.content.Context

/**
 * Durable phone-shell facts (phone-shell T3/T3b).
 *
 * Only what the adb key cannot tell us lives here: whether the owner finished
 * a pairing, and which helper version was installed. The adb key itself stays
 * in [AdbKeyStore]; the connection and bridge states are runtime facts and are
 * re-checked instead of persisted.
 */
class PhoneShellStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** True after a successful pairing; cleared only with the app data. */
    var paired: Boolean
        get() = preferences.getBoolean(KEY_PAIRED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PAIRED, value).apply()
        }

    /** Last helper version code installed over adb; 0 means unknown. */
    var installedHelperVersion: Int
        get() = preferences.getInt(KEY_HELPER_VERSION, 0)
        set(value) {
            preferences.edit().putInt(KEY_HELPER_VERSION, value).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "phone_shell"
        const val KEY_PAIRED = "paired"
        const val KEY_HELPER_VERSION = "installed_helper_version"
    }
}
