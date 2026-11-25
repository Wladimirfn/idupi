package com.idupi.app.data.phone.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility grant parsing (phone-agent T3). The platform exposes enabled
 * services as a colon-separated `package/class` list; a wrong read would either
 * hide the control card's real state or claim a grant the owner never gave.
 */
class AccessibilityStatusTest {

    private val component = "com.idupi.app/com.idupi.app.data.phone.control.IdupiAccessibilityService"

    @Test
    fun `detects the component among other services`() {
        val enabled = "com.other.app/com.other.Service:$component"

        assertTrue(AccessibilityStatus.isEnabled(enabled, component))
    }

    @Test
    fun `ignores case and surrounding spaces`() {
        val enabled = "  COM.IDUPI.APP/com.idupi.app.data.phone.control.IDUPIACCESSIBILITYSERVICE "

        assertTrue(AccessibilityStatus.isEnabled(enabled, component))
    }

    @Test
    fun `reports false for missing or unrelated grants`() {
        assertFalse(AccessibilityStatus.isEnabled(null, component))
        assertFalse(AccessibilityStatus.isEnabled("", component))
        assertFalse(AccessibilityStatus.isEnabled("com.other.app/com.other.Service", component))
        assertFalse(AccessibilityStatus.isEnabled(":", component))
    }

    @Test
    fun `reports false for a blank component`() {
        assertFalse(AccessibilityStatus.isEnabled(component, ""))
    }
}
