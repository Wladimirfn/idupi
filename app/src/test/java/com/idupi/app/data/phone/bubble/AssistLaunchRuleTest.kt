package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure launch rule of the system assistant entry point (assistant-key T1).
 *
 * The exported activity only decides between delegating to the live bubble and
 * opening the app with the accessibility hint; that decision is pinned here so
 * a long press of the side key can never end in a silent no-op.
 */
class AssistLaunchRuleTest {

    @Test
    fun `accessibility running and a live overlay open the bubble in dictation`() {
        assertEquals(
            AssistLaunch.OpenBubbleDictation,
            AssistLaunchRule.decide(
                accessibilityRunning = true,
                bubbleOverlayAttached = true,
            ),
        )
    }

    @Test
    fun `accessibility running with the service not bound yet still targets the bubble`() {
        assertEquals(
            AssistLaunch.OpenBubbleDictation,
            AssistLaunchRule.decide(
                accessibilityRunning = true,
                bubbleOverlayAttached = false,
            ),
        )
    }

    @Test
    fun `a live overlay is enough even when the settings read lags`() {
        assertEquals(
            AssistLaunch.OpenBubbleDictation,
            AssistLaunchRule.decide(
                accessibilityRunning = false,
                bubbleOverlayAttached = true,
            ),
        )
    }

    @Test
    fun `without accessibility the app opens with the hint`() {
        assertEquals(
            AssistLaunch.OpenAppWithHint,
            AssistLaunchRule.decide(
                accessibilityRunning = false,
                bubbleOverlayAttached = false,
            ),
        )
    }
}
