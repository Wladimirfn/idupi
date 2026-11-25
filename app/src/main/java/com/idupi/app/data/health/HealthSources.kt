package com.idupi.app.data.health

/**
 * Known health apps and how IDUPI can read each one (phone-health T2).
 *
 * The catalog is intentionally explicit instead of pattern based: a package is
 * either a health writer we have a route for or it stays unknown. Health
 * Connect writers are read by [HealthConnectReader]; the hidden-display apps
 * are read on the PC through ADB ([HealthRoute.HIDDEN_DISPLAY]), because
 * Huawei Health never publishes to Health Connect.
 */
enum class HealthRoute(val wire: String, val label: String) {
    HEALTH_CONNECT("health_connect", "Health Connect"),
    HIDDEN_DISPLAY("hidden_display", "Lectura desde el PC"),
    NOT_SUPPORTED("not_supported", "No compatible"),
}

/** One app in the built-in catalog, with the route IDUPI uses for it. */
data class KnownHealthApp(
    val packageName: String,
    val label: String,
    val route: HealthRoute,
)

/** The health-app catalog (labels are the official app names). */
object HealthAppCatalog {

    val APPS: List<KnownHealthApp> = listOf(
        KnownHealthApp("com.sec.android.app.shealth", "Samsung Health", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.mi.health", "Mi Fitness", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.xiaomi.wearable", "Mi Fitness", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.xiaomi.hm.health", "Zepp Life", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp(
            "com.huami.watch.hmwatchmanager",
            "Zepp",
            HealthRoute.HEALTH_CONNECT,
        ),
        KnownHealthApp(
            "com.google.android.apps.fitness",
            "Google Fit",
            HealthRoute.HEALTH_CONNECT,
        ),
        KnownHealthApp("com.fitbit.FitbitMobile", "Fitbit", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp(
            "com.garmin.android.apps.connectmobile",
            "Garmin Connect",
            HealthRoute.HEALTH_CONNECT,
        ),
        KnownHealthApp("com.withings.wiscale2", "Withings", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("fi.polar.polarflow", "Polar Flow", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.ouraring.oura", "Oura", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.strava", "Strava", HealthRoute.HEALTH_CONNECT),
        KnownHealthApp("com.huawei.health", "Huawei Health", HealthRoute.HIDDEN_DISPLAY),
        KnownHealthApp("com.hihonor.health", "Honor Health", HealthRoute.HIDDEN_DISPLAY),
    )

    fun entryFor(packageName: String): KnownHealthApp? =
        APPS.firstOrNull { it.packageName == packageName }
}

/**
 * Turns the installed catalog hits plus the Health Connect data origins into
 * the upload's `installedApps` list.
 *
 * Rules pinned by the tests:
 * - Only packages actually installed/hit are reported; the catalog alone is
 *   not a claim about the phone.
 * - A catalog app keeps its catalog route even when it also appears as a
 *   Health Connect origin.
 * - An origin outside the catalog is reported with route `health_connect`,
 *   labelled through PackageManager when possible and by package name
 *   otherwise, so a health app the catalog does not know still shows up.
 * - Package names are filtered to the server's accepted shape and the list is
 *   capped at the server's 40 entries.
 */
object HealthSourceDetection {

    const val MAX_INSTALLED_APPS = 40
    private const val MAX_LABEL_CHARS = 80

    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_.]{1,120}$")

    fun build(
        catalogInstalled: List<KnownHealthApp>,
        originPackages: Collection<String>,
        labelFor: (String) -> String?,
    ): List<HealthInstalledApp> {
        val byPackage = LinkedHashMap<String, HealthInstalledApp>()

        for (app in catalogInstalled) {
            if (!isValidPackageName(app.packageName)) continue
            byPackage[app.packageName] = HealthInstalledApp(
                packageName = app.packageName,
                label = clipLabel(app.label, app.packageName),
                route = app.route.wire,
            )
        }

        for (origin in originPackages) {
            if (!isValidPackageName(origin)) continue
            if (byPackage.containsKey(origin)) continue
            val label = labelFor(origin)?.let { clipLabel(it, origin) } ?: origin
            byPackage[origin] = HealthInstalledApp(
                packageName = origin,
                label = label,
                route = HealthRoute.HEALTH_CONNECT.wire,
            )
        }

        return byPackage.values
            .sortedBy { it.packageName }
            .take(MAX_INSTALLED_APPS)
    }

    fun isValidPackageName(packageName: String): Boolean =
        PACKAGE_PATTERN.matches(packageName)

    private fun clipLabel(label: String?, fallback: String): String {
        val trimmed = label?.trim().orEmpty()
        return (trimmed.ifEmpty { fallback }).take(MAX_LABEL_CHARS)
    }
}
