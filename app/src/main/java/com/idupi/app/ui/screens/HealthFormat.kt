package com.idupi.app.ui.screens

import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthReportState
import com.idupi.app.data.health.HealthRoute

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
