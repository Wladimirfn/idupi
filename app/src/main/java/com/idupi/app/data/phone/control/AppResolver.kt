package com.idupi.app.data.phone.control

/** One installed, launchable app as the accessibility service sees it. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/**
 * Pure half of the `open` command (phone-agent T3): the Android side enumerates
 * installed launcher activities and this decides which one the PC meant.
 *
 * Resolution is case-insensitive and ordered:
 * 1. exact package name (`com.whatsapp`),
 * 2. exact visible label (`WhatsApp`),
 * 3. exactly one app whose label or package contains the query (`whats`).
 * Anything else returns null: more than one partial match is ambiguous and
 * opening the wrong app would be worse than a reported failure.
 */
object AppResolver {

    fun resolve(query: String, apps: List<LaunchableApp>): LaunchableApp? {
        val wanted = query.trim()
        if (wanted.isEmpty()) return null

        apps.firstOrNull { it.packageName.equals(wanted, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.label.equals(wanted, ignoreCase = true) }?.let { return it }

        val partial = apps.filter {
            it.label.contains(wanted, ignoreCase = true) ||
                it.packageName.contains(wanted, ignoreCase = true)
        }
        return partial.singleOrNull()
    }
}
