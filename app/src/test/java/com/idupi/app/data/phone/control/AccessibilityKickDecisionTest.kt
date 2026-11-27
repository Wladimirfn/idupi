package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility kick decision (phone-shell T7b): only a grant recorded in
 * Settings but not bound triggers a kick; the live helper is preferred and adb
 * is the paired fallback.
 */
class AccessibilityKickDecisionTest {

    @Test
    fun `kicks only when accessibility is enabled but not connected`() {
        assertTrue(AccessibilityKickDecision.shouldKick(enabledInSettings = true, connected = false))
        assertFalse(AccessibilityKickDecision.shouldKick(enabledInSettings = true, connected = true))
        assertFalse(AccessibilityKickDecision.shouldKick(enabledInSettings = false, connected = false))
        assertFalse(AccessibilityKickDecision.shouldKick(enabledInSettings = false, connected = true))
    }

    @Test
    fun `prefers the helper, then adb when paired, otherwise none`() {
        assertEquals(
            AccessibilityKickPath.HELPER,
            AccessibilityKickDecision.path(bridgeActive = true, paired = false),
        )
        assertEquals(
            AccessibilityKickPath.ADB,
            AccessibilityKickDecision.path(bridgeActive = false, paired = true),
        )
        assertEquals(
            AccessibilityKickPath.NONE,
            AccessibilityKickDecision.path(bridgeActive = false, paired = false),
        )
        // A live helper wins even when the phone is paired.
        assertEquals(
            AccessibilityKickPath.HELPER,
            AccessibilityKickDecision.path(bridgeActive = true, paired = true),
        )
    }
}
