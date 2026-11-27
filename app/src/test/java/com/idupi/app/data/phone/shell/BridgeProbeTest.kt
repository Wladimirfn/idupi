package com.idupi.app.data.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge heartbeat freshness (phone-shell T3b): a bridge is alive only when the
 * `idupi_shell_probe` value changed after the start and is not the absent
 * sentinel.
 */
class BridgeProbeTest {

    @Test
    fun `a changed value is fresh`() {
        assertTrue(BridgeProbe.isFresh("2026-11-27 12:00:00", "2026-11-27 12:00:05"))
        assertTrue(BridgeProbe.isFresh(null, "2026-11-27 12:00:05"))
        assertTrue(BridgeProbe.isFresh("null", "2026-11-27 12:00:05"))
    }

    @Test
    fun `the same value is not fresh`() {
        assertFalse(BridgeProbe.isFresh("2026-11-27 12:00:00", "2026-11-27 12:00:00"))
        assertFalse(BridgeProbe.isFresh("2026-11-27 12:00:00", " 2026-11-27 12:00:00 "))
    }

    @Test
    fun `absent or blank values are not fresh`() {
        assertFalse(BridgeProbe.isFresh("old", "null"))
        assertFalse(BridgeProbe.isFresh("old", ""))
        assertFalse(BridgeProbe.isFresh("old", "   "))
        assertFalse(BridgeProbe.isFresh("old", null))
    }
}
