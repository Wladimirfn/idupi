package com.idupi.app.ui.screens.settings

import com.idupi.app.data.calendar.CalendarPermissionState
import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.ui.theme.ThemePalettes
import com.idupi.app.viewmodel.HealthSettingsState

/**
 * One-line summaries under the Ajustes hub categories (settings redesign T2).
 *
 * Pure functions: the hub reads the existing state objects and these map it to
 * neutral Spanish copy, so every string is JVM-tested without Compose.
 * Unknown values are omitted instead of invented.
 */
internal fun connectionSummary(profileName: String?, profileHost: String?): String {
    val name = profileName?.takeIf { it.isNotBlank() } ?: "Sin configurar"
    val host = profileHost?.takeIf { it.isNotBlank() }
    return if (host != null) "$name · $host" else name
}

internal fun appearanceSummary(settings: ThemeSettings): String = when (settings.mode) {
    ThemeMode.DARK -> "Oscuro · ${ThemePalettes.labelFor(settings.preset)}"
    ThemeMode.LIGHT -> "Claro · ${ThemePalettes.labelFor(settings.preset)}"
    ThemeMode.CUSTOM -> "Personalizado"
}

internal fun alertsSummary(settings: InboxSettings, notificationAccessEnabled: Boolean): String {
    if (!notificationAccessEnabled) return "Sin acceso a notificaciones"
    val count = settings.followedApps.size
    return if (count == 1) "1 app seguida" else "$count apps seguidas"
}

internal fun phoneAssistantSummary(controlEnabled: Boolean, pendingApprovals: Int): String {
    val status = if (controlEnabled) {
        "Control desde la PC activado"
    } else {
        "Control desde la PC desactivado"
    }
    return when (pendingApprovals) {
        0 -> status
        1 -> "$status · 1 aprobación pendiente"
        else -> "$status · $pendingApprovals aprobaciones pendientes"
    }
}

internal fun calendarSummary(shareEnabled: Boolean, permission: CalendarPermissionState): String =
    when {
        !permission.canRead -> "Sin permiso de calendario"
        shareEnabled -> "Compartiendo con la PC"
        else -> "Compartir desactivado"
    }

internal fun healthSummary(state: HealthSettingsState): String = when {
    state.availability == HealthConnectAvailability.AVAILABLE && state.hasAllPermissions ->
        "Health Connect · permisos al día"
    state.availability == HealthConnectAvailability.AVAILABLE ->
        "Health Connect · faltan permisos"
    state.availability == HealthConnectAvailability.NEEDS_UPDATE ->
        "Health Connect necesita actualizarse"
    else -> "Health Connect no está en el teléfono"
}

/** Fixed copy of the "Ayuda y comandos" hub row. */
internal const val HELP_CATEGORY_SUMMARY = "Referencia de comandos de la PC"

/** Fixed copy of the "Acciones y seguridad" hub row. */
internal const val ACTIONS_CATEGORY_SUMMARY = "Autodiagnóstico y cerrar sesión"
