package com.idupi.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ajustes hub navigation (settings redesign T1): the parent/back table and the
 * route-name round-trip used by `rememberSaveable`.
 */
class SettingsRouteTest {

    @Test
    fun `hub has no parent so system back leaves Ajustes`() {
        assertNull(parent(SettingsRoute.Hub))
        assertNull(back(SettingsRoute.Hub))
    }

    @Test
    fun `categories return to the hub`() {
        listOf(
            SettingsRoute.Appearance,
            SettingsRoute.Alerts,
            SettingsRoute.PhoneAssistant,
            SettingsRoute.Calendar,
            SettingsRoute.Health,
            SettingsRoute.Actions,
        ).forEach { route ->
            assertEquals(SettingsRoute.Hub, parent(route))
            assertEquals(SettingsRoute.Hub, back(route))
        }
    }

    @Test
    fun `alerts blocks return to Alertas`() {
        listOf(
            SettingsRoute.FollowedApps,
            SettingsRoute.SilencedChats,
            SettingsRoute.AlertAnalyst,
            SettingsRoute.CaptureStatus,
        ).forEach { route ->
            assertEquals(SettingsRoute.Alerts, parent(route))
            assertEquals(SettingsRoute.Alerts, back(route))
        }
    }

    @Test
    fun `every non-hub route has a parent`() {
        SettingsRoute.entries
            .filter { it != SettingsRoute.Hub }
            .forEach { route -> assertNotNull("missing parent for $route", parent(route)) }
    }

    @Test
    fun `saved route names round-trip`() {
        SettingsRoute.entries.forEach { route ->
            assertEquals(route, SettingsRoute.fromName(route.name))
        }
    }

    @Test
    fun `unknown or missing saved names fall back to the hub`() {
        assertEquals(SettingsRoute.Hub, SettingsRoute.fromName(null))
        assertEquals(SettingsRoute.Hub, SettingsRoute.fromName(""))
        assertEquals(SettingsRoute.Hub, SettingsRoute.fromName("SomethingElse"))
    }
}
