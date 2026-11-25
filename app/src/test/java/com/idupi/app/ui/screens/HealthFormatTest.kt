package com.idupi.app.ui.screens

import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthReportState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ajustes > SALUD copy (phone-health T2): neutral Spanish, relative time. */
class HealthFormatTest {

    private val now = 1_000_000_000L

    @Test
    fun `no attempt yet`() {
        assertEquals(
            "Todavía no se enviaron datos de salud.",
            healthLastReportLabel(HealthReportState(), now),
        )
    }

    @Test
    fun `a recent success names the sample count`() {
        assertEquals(
            "Último envío hace 5 min (3 muestras).",
            healthLastReportLabel(
                HealthReportState(lastAttemptAtMillis = now - 5 * 60_000L, success = true, sampleCount = 3),
                now,
            ),
        )
    }

    @Test
    fun `an hour-old report uses hours`() {
        assertEquals(
            "Último envío hace 2 h (7 muestras).",
            healthLastReportLabel(
                HealthReportState(lastAttemptAtMillis = now - 2 * 60 * 60_000L, success = true, sampleCount = 7),
                now,
            ),
        )
    }

    @Test
    fun `a failure says so without hiding the time`() {
        assertEquals(
            "El último envío falló hace menos de un minuto.",
            healthLastReportLabel(
                HealthReportState(lastAttemptAtMillis = now - 10_000L, success = false),
                now,
            ),
        )
    }

    @Test
    fun `route labels come from the route ids`() {
        assertEquals("Health Connect", healthRouteLabel("health_connect"))
        assertEquals("Lectura desde el PC", healthRouteLabel("hidden_display"))
        assertEquals("No compatible", healthRouteLabel("not_supported"))
        assertEquals("No compatible", healthRouteLabel("something_else"))
    }

    @Test
    fun `availability copy distinguishes the three states`() {
        assertEquals(
            "Health Connect disponible",
            healthConnectStatusText(HealthConnectAvailability.AVAILABLE),
        )
        assertEquals(
            "Health Connect necesita actualizarse",
            healthConnectStatusText(HealthConnectAvailability.NEEDS_UPDATE),
        )
        assertEquals(
            "Health Connect no está en este teléfono",
            healthConnectStatusText(HealthConnectAvailability.UNAVAILABLE),
        )
    }
}
