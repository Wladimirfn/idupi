package com.idupi.app.data.health

import android.content.Context
import android.content.pm.PackageManager

/**
 * Detects which health apps are installed and how IDUPI reads them
 * (phone-health T2).
 *
 * Package visibility on Android 11+ makes the check explicit: the manifest
 * lists a `<queries><package>` entry for every catalog app (never
 * QUERY_ALL_PACKAGES), and this class only asks for those. Apps that only
 * appear as Health Connect data origins are labelled through PackageManager
 * when visible and fall back to the package name otherwise.
 */
class AndroidHealthSources(private val context: Context) {

    /** Catalog entries whose package is actually installed. */
    fun installedCatalogApps(): List<KnownHealthApp> =
        HealthAppCatalog.APPS.filter { isInstalled(it.packageName) }

    /** The upload's `installedApps`: catalog hits plus Health Connect origins. */
    fun installedApps(originPackages: Collection<String> = emptyList()): List<HealthInstalledApp> =
        HealthSourceDetection.build(
            catalogInstalled = installedCatalogApps(),
            originPackages = originPackages,
            labelFor = ::labelFor,
        )

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun labelFor(packageName: String): String? = runCatching {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrNull()
}
