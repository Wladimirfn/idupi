package com.idupi.app.ui.screens

import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthReportState
import com.idupi.app.data.health.HealthRoute
import com.idupi.app.domain.model.HealthAdbStatus

/**
 * User-facing labels of Ajustes > SALUD (phone-health T2). Neutral Latin
 * American Spanish with "tú", matching the rest of the app's copy.
 */
internal fun healthConnectStatusText(availability: HealthConnectAvailability): String =
    when (availability) {
        HealthConnectAvailability.AVAILABLE -> "Health Connect disponible"
        HealthConnectAvailability.NEEDS_UPDATE -> "Health Connect necesita actualizarse"
        HealthConnectAvailability.UNAVAILABLE -> "Health Connect no está en este teléfono"
    }

internal fun healthConnectStatusDetail(availability: HealthConnectAvailability): String =
    when (availability) {
        HealthConnectAvailability.AVAILABLE ->
            "IDUPI lee tus datos de salud desde Health Connect."
        HealthConnectAvailability.NEEDS_UPDATE ->
            "Actualiza Health Connect desde Play Store para leer tus datos."
        HealthConnectAvailability.UNAVAILABLE ->
            "Instala Health Connect desde Play Store para leer tus datos de salud."
    }

internal fun healthRouteLabel(wire: String): String = when (wire) {
    HealthRoute.HEALTH_CONNECT.wire -> HealthRoute.HEALTH_CONNECT.label
    HealthRoute.HIDDEN_DISPLAY.wire -> HealthRoute.HIDDEN_DISPLAY.label
    else -> HealthRoute.NOT_SUPPORTED.label
}

/** PC hidden-reading headline for Ajustes > SALUD. */
internal fun healthAdbStatusText(status: HealthAdbStatus): String = if (status.connected) {
    val serial = status.serial?.takeIf { it.isNotBlank() }
    if (serial != null) "Teléfono conectado: $serial" else "Teléfono conectado"
} else {
    "Sin teléfono conectado por ADB"
}

/** adb/scrcpy/saved targets in one line, hiding what is missing. */
internal fun healthAdbDetail(status: HealthAdbStatus): String = buildList {
    add(if (status.adb.found) "ADB encontrado" else "ADB no encontrado")
    add(
        if (status.scrcpy.found) {
            val version = status.scrcpy.version?.takeIf { it.isNotBlank() }
            if (version != null) "scrcpy $version" else "scrcpy encontrado"
        } else {
            "scrcpy no encontrado"
        },
    )
    status.targets.firstOrNull()?.let { target ->
        val model = target.model?.takeIf { it.isNotBlank() }
        add("Guardado: ${target.serial}" + (model?.let { " · $it" } ?: ""))
    }
}.joinToString(" · ")

/**
 * Last health report, as the owner reads it: relative time, whether it
 * succeeded and how many samples travelled.
 */
internal fun healthLastReportLabel(report: HealthReportState, nowMillis: Long): String {
    val attemptedAt = report.lastAttemptAtMillis
        ?: return "Todavía no se enviaron datos de salud."
    val minutes = ((nowMillis - attemptedAt).coerceAtLeast(0L) / 60_000L).toInt()
    val ago = when {
        minutes < 1 -> "hace menos de un minuto"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (24 * 60)} días"
    }
    return if (report.success == false) {
        "El último envío falló $ago."
    } else {
        "Último envío $ago (${report.sampleCount} muestras)."
    }
}
