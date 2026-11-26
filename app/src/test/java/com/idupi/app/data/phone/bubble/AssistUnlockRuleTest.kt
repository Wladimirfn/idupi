package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyguard rule of the system assistant entry point (assistant-key T5).
 *
 * Firing ASSIST on a locked phone used to open the bubble over the lock screen,
 * and a bubble turn approves messaging out loud, so the launch decision must
 * never run before the owner is past the keyguard.
 */
class AssistUnlockRuleTest {

    @Test
    fun `a locked phone asks to unlock before anything is launched`() {
        assertEquals(
            AssistUnlockDecision.AskToUnlock,
            AssistUnlockRule.decide(keyguardLocked = true),
        )
    }

    @Test
    fun `an unlocked phone runs the launch rule directly`() {
        assertEquals(
            AssistUnlockDecision.Launch,
            AssistUnlockRule.decide(keyguardLocked = false),
        )
    }
}
