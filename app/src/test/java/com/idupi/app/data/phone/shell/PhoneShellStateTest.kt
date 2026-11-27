package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status copy and action gating (phone-shell T3). The Ajustes row and the
 * sub-screen must show exactly the five owner-facing states, and "Iniciar
 * puente" is only available after pairing.
 */
class PhoneShellStateTest {

    @Test
    fun `maps every stage to its owner-facing label`() {
        val labels = mapOf(
            PhoneShellStage.CHECKING to "Consultando…",
            PhoneShellStage.UNAVAILABLE to "No disponible en este teléfono (API < 30)",
            PhoneShellStage.NOT_PAIRED to "No vinculado",
            PhoneShellStage.PAIRED to "Vinculado",
            PhoneShellStage.CONNECTED to "Conectado",
            PhoneShellStage.BRIDGE_ACTIVE to "Puente activo",
        )
        PhoneShellStage.entries.forEach { stage ->
            assertEquals("label for $stage", labels.getValue(stage), phoneShellStatusLabel(stage))
        }
    }

    @Test
    fun `only paired stages can start the bridge`() {
        assertTrue(PhoneShellStage.PAIRED.isPaired())
        assertTrue(PhoneShellStage.CONNECTED.isPaired())
        assertTrue(PhoneShellStage.BRIDGE_ACTIVE.isPaired())
        assertFalse(PhoneShellStage.NOT_PAIRED.isPaired())
        assertFalse(PhoneShellStage.CHECKING.isPaired())
        assertFalse(PhoneShellStage.UNAVAILABLE.isPaired())
    }

    @Test
    fun `the default state is checking and not busy`() {
        val state = PhoneShellState()
        assertEquals(PhoneShellStage.CHECKING, state.stage)
        assertFalse(state.busy)
        assertEquals(null, state.message)
    }
}
