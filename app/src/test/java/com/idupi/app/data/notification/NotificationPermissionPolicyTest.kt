package com.idupi.app.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification gate is pure so the "ask once" rule is provable without an
 * Activity: asking on every launch is exactly the kind of nag the owner would
 * mute, and asking before a PC is configured is noise.
 */
class NotificationPermissionPolicyTest {

    private fun plan(
        sdkInt: Int = 33,
        granted: Boolean = false,
        enabled: Boolean = true,
        server: Boolean = true,
        asked: Boolean = false
    ) = notificationPermissionPlan(
        sdkInt = sdkInt,
        permissionGranted = granted,
        notificationsEnabled = enabled,
        hasConfiguredServer = server,
        alreadyAsked = asked
    )

    @Test
    fun `below API 33 there is no runtime request at all`() {
        val result = plan(sdkInt = 32, granted = true)

        assertFalse(result.requestOnLaunch)
        assertFalse(result.showBanner)
    }

    @Test
    fun `first launch with a configured server asks once and shows the banner`() {
        val result = plan()

        assertTrue(result.requestOnLaunch)
        assertTrue(result.showBanner)
    }

    @Test
    fun `without a configured server the dialog waits`() {
        val result = plan(server = false)

        assertFalse(result.requestOnLaunch)
        assertTrue(result.showBanner)
    }

    @Test
    fun `an already answered permission is never asked again`() {
        val result = plan(asked = true)

        assertFalse(result.requestOnLaunch)
        assertTrue(result.showBanner)
    }

    @Test
    fun `granted permission hides everything until the channel is muted`() {
        assertFalse(plan(granted = true).requestOnLaunch)
        assertFalse(plan(granted = true).showBanner)

        val muted = plan(granted = true, enabled = false)
        assertFalse(muted.requestOnLaunch)
        assertTrue(muted.showBanner)
    }
}
