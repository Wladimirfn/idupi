package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import java.util.Locale

/**
 * One launchable app the owner can follow (phone-inbox T3).
 *
 * [label] is the PackageManager label; a blank one falls back to the package
 * name so a row is never rendered empty.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
) {
    /** Preset when the package is one of the known apps. */
    val preset: InboxApp? get() = InboxApp.fromPackageName(packageName)

    /** Follow key: the preset id, or the package name for any other app. */
    val appId: String get() = preset?.id ?: packageName

    /** Owner-facing name: the launcher label, else the package name. */
    val displayLabel: String get() = label.trim().ifBlank { packageName }
}

/** Installed apps split the way Ajustes > Alertas renders them. */
data class InstalledAppInventory(
    val presets: List<InstalledApp> = emptyList(),
    val others: List<InstalledApp> = emptyList(),
) {
    val all: List<InstalledApp> get() = presets + others

    val isEmpty: Boolean get() = presets.isEmpty() && others.isEmpty()

    /** Followed apps the owner can actually see (and switch) on this phone. */
    fun followingCount(followedApps: Set<String>): Int = followed(followedApps).size

    /**
     * Collapsed "Apps a seguir" subset (phone-calendar T5): only the followed
     * apps present on this phone, kept in the inventory's preset-first order.
     */
    fun followed(followedApps: Set<String>): List<InstalledApp> =
        all.filter { it.appId in followedApps }
}

/**
 * Pure inventory rules for "Apps a seguir" (phone-inbox T3), kept Android-free
 * so the ordering and merge are unit-tested without Robolectric.
 *
 * - one row per package: an app can publish several launcher activities, and
 *   the PackageManager returns one entry per activity;
 * - IDUPI itself is never offered, because following our own notifications
 *   would loop the inbox;
 * - presets (WhatsApp, Gmail, Messenger...) come first, then the rest, each
 *   group sorted by the visible label and the package name as a tie-breaker;
 * - a blank label falls back to the package name.
 */
object InstalledApps {

    /** [launchable] is (packageName, label) as enumerated from the launcher intent. */
    fun inventory(
        launchable: List<Pair<String, String>>,
        ownPackageName: String = NotificationParser.OWN_PACKAGE,
    ): InstalledAppInventory {
        val byPackage = LinkedHashMap<String, InstalledApp>(launchable.size)
        for ((rawPackage, label) in launchable) {
            val packageName = rawPackage.trim()
            if (packageName.isEmpty()) continue
            if (packageName == ownPackageName) continue
            if (byPackage.containsKey(packageName)) continue
            byPackage[packageName] = InstalledApp(packageName = packageName, label = label)
        }

        val sorted = byPackage.values.sortedWith(
            compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.displayLabel }
                .thenBy { it.packageName }
        )
        return InstalledAppInventory(
            presets = sorted.filter { it.preset != null },
            others = sorted.filter { it.preset == null },
        )
    }

    /** Case-insensitive filter over both the label and the package name. */
    fun matching(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val wanted = query.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return apps
        return apps.filter {
            it.displayLabel.lowercase(Locale.ROOT).contains(wanted) ||
                it.packageName.lowercase(Locale.ROOT).contains(wanted)
        }
    }
}
