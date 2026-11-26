package com.idupi.app.ui.screens.settings

/**
 * Screens inside Ajustes (settings redesign T1).
 *
 * The app-level navigation keeps one SETTINGS destination; this small route
 * model decides which settings surface is visible. Every route except [Hub]
 * has a parent, so system back and the top-bar arrow walk the same table.
 */
enum class SettingsRoute {
    Hub,
    Appearance,
    Alerts,
    FollowedApps,
    SilencedChats,
    AlertAnalyst,
    CaptureStatus,
    PhoneAssistant,
    Calendar,
    Health,
    Actions;

    companion object {
        /** Restores a route saved by name; an unknown name falls back to [Hub]. */
        fun fromName(name: String?): SettingsRoute =
            entries.firstOrNull { it.name == name } ?: Hub
    }
}

/**
 * Parent of [route], or null on the hub. Back from the hub is not consumed
 * here: it keeps the app-level behaviour (leave Ajustes).
 */
fun parent(route: SettingsRoute): SettingsRoute? = when (route) {
    SettingsRoute.Hub -> null
    SettingsRoute.Appearance -> SettingsRoute.Hub
    SettingsRoute.Alerts -> SettingsRoute.Hub
    SettingsRoute.FollowedApps -> SettingsRoute.Alerts
    SettingsRoute.SilencedChats -> SettingsRoute.Alerts
    SettingsRoute.AlertAnalyst -> SettingsRoute.Alerts
    SettingsRoute.CaptureStatus -> SettingsRoute.Alerts
    SettingsRoute.PhoneAssistant -> SettingsRoute.Hub
    SettingsRoute.Calendar -> SettingsRoute.Hub
    SettingsRoute.Health -> SettingsRoute.Hub
    SettingsRoute.Actions -> SettingsRoute.Hub
}

/** Route the back arrow and system back return to; null on the hub. */
fun back(route: SettingsRoute): SettingsRoute? = parent(route)
