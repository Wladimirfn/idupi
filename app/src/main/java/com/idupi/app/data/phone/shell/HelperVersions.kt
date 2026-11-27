package com.idupi.app.data.phone.shell

/**
 * Version rules for the embedded helper (phone-shell T3b).
 *
 * The app embeds `idupi-shell-helper.apk`; it must install it only when the
 * phone has no helper or an older one, and must never "install" a broken asset
 * whose version could not be read.
 */
object HelperVersions {

    private val VERSION_CODE = Regex("versionCode=(\\d+)")

    /**
     * True when the embedded helper (versionCode [embedded]) must be installed
     * over [installed]. A null installed version means "not installed"; a
     * non-positive embedded version means the asset could not be parsed and
     * installing it would be worse than leaving the phone alone.
     */
    fun needsInstall(installed: Int?, embedded: Int): Boolean =
        embedded > 0 && (installed == null || installed < embedded)

    /** Reads `versionCode=` from `dumpsys package <helper>`; null when absent. */
    fun installedVersion(dumpsysPackage: String): Int? =
        VERSION_CODE.find(dumpsysPackage)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
