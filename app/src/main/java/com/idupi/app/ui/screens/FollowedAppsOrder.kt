package com.idupi.app.ui.screens

import com.idupi.app.data.inbox.InstalledApp
import com.idupi.app.data.inbox.InstalledAppInventory
import com.idupi.app.data.inbox.InstalledApps

/**
 * Rows of the "Apps a seguir" screen (settings redesign T4): the search field
 * filters by label and package (see [InstalledApps.matching]), followed apps
 * come first so the active choices are visible without scrolling, and each
 * group keeps the inventory's preset-first order.
 *
 * Pure on purpose: the ordering is JVM-tested without Compose.
 */
internal fun followedFirstApps(
    inventory: InstalledAppInventory,
    followedApps: Set<String>,
    query: String,
): List<InstalledApp> {
    val matching = InstalledApps.matching(inventory.all, query)
    val (followed, rest) = matching.partition { it.appId in followedApps }
    return followed + rest
}
