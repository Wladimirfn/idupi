package com.idupi.app.ui.screens.settings

import com.idupi.app.data.calendar.CalendarPermissionState
import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.inbox.InboxCaptureState
import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.viewmodel.HealthSettingsState
import com.idupi.app.viewmodel.InboxAgentConfigState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ajustes hub summaries (settings redesign T2): neutral Spanish, honest state. */
class SettingsSummariesTest {

    @Test
    fun `connection names the profile and the host`() {
        assertEquals(
            "Trabajo · 192.168.1.10:4310",
            connectionSummary("Trabajo", "192.168.1.10:4310"),
        )
    }

    @Test
    fun `connection without a saved profile stays honest`() {
        assertEquals("Sin configurar", connectionSummary(null, null))
        assertEquals("Trabajo", connectionSummary("Trabajo", null))
    }

    @Test
    fun `appearance combines mode and accent`() {
        assertEquals(
            "Oscuro · Índigo",
            appearanceSummary(ThemeSettings(mode = ThemeMode.DARK, preset = ThemePreset.INDIGO)),
        )
        assertEquals(
            "Claro · Esmeralda",
            appearanceSummary(ThemeSettings(mode = ThemeMode.LIGHT, preset = ThemePreset.EMERALD)),
        )
        assertEquals(
            "Personalizado",
            appearanceSummary(ThemeSettings(mode = ThemeMode.CUSTOM)),
        )
    }

    @Test
    fun `alerts warn when notification access is missing`() {
        assertEquals(
            "Sin acceso a notificaciones",
            alertsSummary(
                InboxSettings(followedApps = setOf("com.whatsapp")),
                notificationAccessEnabled = false,
            ),
        )
    }

    @Test
    fun `alerts count the followed apps`() {
        assertEquals(
            "4 apps seguidas",
            alertsSummary(
                InboxSettings(followedApps = setOf("a", "b", "c", "d")),
                notificationAccessEnabled = true,
            ),
        )
        assertEquals(
            "1 app seguida",
            alertsSummary(InboxSettings(followedApps = setOf("a")), notificationAccessEnabled = true),
        )
        assertEquals(
            "0 apps seguidas",
            alertsSummary(InboxSettings(followedApps = emptySet()), notificationAccessEnabled = true),
        )
    }

    @Test
    fun `phone assistant reports the PC control switch`() {
        assertEquals("Control desde la PC desactivado", phoneAssistantSummary(false, 0))
        assertEquals("Control desde la PC activado", phoneAssistantSummary(true, 0))
    }

    @Test
    fun `phone assistant adds pending approvals when there are any`() {
        assertEquals(
            "Control desde la PC activado · 1 aprobación pendiente",
            phoneAssistantSummary(true, 1),
        )
        assertEquals(
            "Control desde la PC activado · 2 aprobaciones pendientes",
            phoneAssistantSummary(true, 2),
        )
    }

    @Test
    fun `digital assistant reports whether IDUPI holds the role`() {
        assertEquals(
            "IDUPI es tu asistente digital. Mantén presionado el botón lateral para dictar.",
            assistantRoleSummary(true),
        )
        assertEquals(
            "Elige IDUPI en Aplicación de asistente digital para abrirlo con el botón lateral.",
            assistantRoleSummary(false),
        )
    }

    @Test
    fun `calendar summarises permission and sharing`() {
        assertEquals(
            "Sin permiso de calendario",
            calendarSummary(true, CalendarPermissionState()),
        )
        assertEquals(
            "Compartiendo con la PC",
            calendarSummary(true, CalendarPermissionState(canRead = true, canWrite = true)),
        )
        assertEquals(
            "Compartir desactivado",
            calendarSummary(false, CalendarPermissionState(canRead = true)),
        )
    }

    @Test
    fun `health summarises Health Connect availability and permissions`() {
        assertEquals(
            "Health Connect · permisos al día",
            healthSummary(healthState(HealthConnectAvailability.AVAILABLE, granted = 10, required = 10)),
        )
        assertEquals(
            "Health Connect · faltan permisos",
            healthSummary(healthState(HealthConnectAvailability.AVAILABLE, granted = 2, required = 10)),
        )
        assertEquals(
            "Health Connect necesita actualizarse",
            healthSummary(healthState(HealthConnectAvailability.NEEDS_UPDATE)),
        )
        assertEquals(
            "Health Connect no está en el teléfono",
            healthSummary(healthState(HealthConnectAvailability.UNAVAILABLE)),
        )
    }

    // --- Alertas sub-screen rows (settings redesign T4) --------------------

    @Test
    fun `followed apps row counts the followed apps`() {
        assertEquals("0 apps seguidas", followedAppsSummary(0))
        assertEquals("1 app seguida", followedAppsSummary(1))
        assertEquals("4 apps seguidas", followedAppsSummary(4))
    }

    @Test
    fun `silenced chats row says none instead of zero`() {
        assertEquals("Ninguno", silencedChatsSummary(0))
        assertEquals("1 chat", silencedChatsSummary(1))
        assertEquals("3 chats", silencedChatsSummary(3))
    }

    @Test
    fun `analyst row names the agent and model once the PC answered`() {
        assertEquals(
            "OpenCode · opencode-go/deepseek-v4.1-flash",
            analystSummary(InboxAgentConfigState(model = "opencode-go/deepseek-v4.1-flash")),
        )
        assertEquals(
            "Claude · sonnet",
            analystSummary(InboxAgentConfigState(agent = "claude", model = "sonnet")),
        )
    }

    @Test
    fun `analyst row is omitted while the model is unknown`() {
        assertNull(analystSummary(InboxAgentConfigState()))
        assertNull(analystSummary(InboxAgentConfigState(model = "   ")))
    }

    @Test
    fun `capture row reads the listener diagnostics`() {
        assertEquals("Escuchando", captureStatusSummary(InboxCaptureState(listenerConnected = true)))
        assertEquals("Detenido", captureStatusSummary(InboxCaptureState(listenerConnected = false)))
    }

    @Test
    fun `static categories keep their copy`() {
        assertEquals("Referencia de comandos de la PC", HELP_CATEGORY_SUMMARY)
        assertEquals("Autodiagnóstico y cerrar sesión", ACTIONS_CATEGORY_SUMMARY)
    }

    private fun healthState(
        availability: HealthConnectAvailability,
        granted: Int = 0,
        required: Int = 0,
    ) = HealthSettingsState(
        availability = availability,
        grantedPermissions = (1..granted).map { "permission$it" }.toSet(),
        requiredPermissions = (1..required).map { "permission$it" }.toSet(),
    )
}
