package com.idupi.app.data.phone.shell

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Access to the helper APK embedded in IDUPI's assets (phone-shell T3b).
 *
 * The app build copies `:shellhelper`'s release APK to
 * `assets/idupi-shell-helper.apk`; the bootstrap streams those same bytes over
 * adb, so the installed helper always matches the app that shipped it.
 */
object HelperApk {

    const val ASSET_NAME = "idupi-shell-helper.apk"

    fun readBytes(context: Context): ByteArray =
        context.assets.open(ASSET_NAME).use { it.readBytes() }

    /**
     * The embedded helper's versionCode, or -1 when the asset is missing or
     * unreadable. `getPackageArchiveInfo` needs a real file path, so the asset
     * is copied to the app cache first; this is also the copy the path
     * fallback reuses.
     */
    @Suppress("DEPRECATION")
    fun embeddedVersionCode(context: Context): Int {
        val file = copyTo(context, context.cacheDir) ?: return -1
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return -1
        return PackageInfoCompat.getLongVersionCode(info).toInt()
    }

    /**
     * Copy for the `pm install <path>` fallback. The shell (uid 2000) can read
     * the app's external files directory, not app-private storage.
     */
    fun copyToExternal(context: Context): File? {
        val directory = context.getExternalFilesDir(null) ?: return null
        return copyTo(context, directory)
    }

    private fun copyTo(context: Context, directory: File): File? {
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val target = File(directory, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (e: Exception) {
            null
        }
    }
}
