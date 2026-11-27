package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window filter that keeps the agent out of IDUPI's own bubble panel
 * (phone-dashboard T7).
 *
 * On the owner's phone the expanded bubble took the active-window slot, so
 * `screen` dumped the panel and the agent tapped "MINIMIZAR" (turn
 * pat-9ee16fd0e46a). The filter is pure so every combination is pinned here
 * without an Android runtime.
 */
class PhoneWindowFilterTest {

    private val own = "com.idupi.app"
    private val overlay = PhoneWindowFilter.TYPE_ACCESSIBILITY_OVERLAY
    private val application = PhoneWindowFilter.TYPE_APPLICATION

    @Test
    fun `IDUPI's own accessibility overlay is never read`() {
        val windows = listOf(
            PhoneWindowRef(overlay, own, active = true),
            PhoneWindowRef(application, "com.android.settings", active = false),
        )
        assertEquals(listOf(1), PhoneWindowFilter.readIndices(windows, own))
    }

    @Test
    fun `another app's overlay stays readable`() {
        val windows = listOf(
            PhoneWindowRef(overlay, "com.android.systemui", active = true),
        )
        assertEquals(listOf(0), PhoneWindowFilter.readIndices(windows, own))
    }

    @Test
    fun `an application window of the same package is not an overlay`() {
        // Only TYPE_ACCESSIBILITY_OVERLAY is the bubble; IDUPI's activity
        // window (were it on screen) is a normal screen.
        val windows = listOf(PhoneWindowRef(application, own, active = true))
        assertEquals(listOf(0), PhoneWindowFilter.readIndices(windows, own))
    }

    @Test
    fun `when the panel steals the focus the readable windows are still read`() {
        val windows = listOf(
            PhoneWindowRef(overlay, own, active = true),
            PhoneWindowRef(application, "com.android.settings", active = false),
            PhoneWindowRef(
                PhoneWindowFilter.TYPE_INPUT_METHOD,
                "com.google.android.inputmethod.latin",
                active = false,
            ),
        )
        assertEquals(listOf(1, 2), PhoneWindowFilter.readIndices(windows, own))
    }

    @Test
    fun `an active readable window wins over the rest`() {
        val windows = listOf(
            PhoneWindowRef(application, "com.android.settings", active = false),
            PhoneWindowRef(application, "com.whatsapp", active = true),
        )
        assertEquals(listOf(1), PhoneWindowFilter.readIndices(windows, own))
    }

    @Test
    fun `only IDUPI's panel on screen leaves nothing to read`() {
        assertEquals(
            emptyList<Int>(),
            PhoneWindowFilter.readIndices(
                listOf(PhoneWindowRef(overlay, own, active = true)),
                own,
            ),
        )
    }

    @Test
    fun `a window with no package is not treated as IDUPI's`() {
        val windows = listOf(PhoneWindowRef(overlay, null, active = true))
        assertEquals(listOf(0), PhoneWindowFilter.readIndices(windows, own))
    }
}
