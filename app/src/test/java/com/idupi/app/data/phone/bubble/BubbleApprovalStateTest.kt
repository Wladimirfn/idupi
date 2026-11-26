package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approval mapping for the bubble (phone-bubble T7).
 *
 * The bubble is a mirror, never a decision surface: while the PC waits for the
 * owner it shows the banner and sends her to Alertas; the pill carries a badge
 * so a pending approval is visible without expanding the panel.
 */
class BubbleApprovalStateTest {

    @Test
    fun `a running turn with pending approvals waits for the owner`() {
        val ui = BubbleApprovalState.of(BubbleChatPhase.RUNNING, pendingCount = 2)

        assertTrue(ui.waitingForApproval)
        assertTrue(ui.badgeVisible)
        assertEquals(2, ui.pendingCount)
    }

    @Test
    fun `a running turn with nothing pending shows nothing`() {
        val ui = BubbleApprovalState.of(BubbleChatPhase.RUNNING, pendingCount = 0)

        assertFalse(ui.waitingForApproval)
        assertFalse(ui.badgeVisible)
        assertEquals(0, ui.pendingCount)
    }

    @Test
    fun `pending approvals with a finished turn keep the badge but not the banner`() {
        listOf(BubbleChatPhase.DONE, BubbleChatPhase.FAILED, BubbleChatPhase.IDLE).forEach { phase ->
            val ui = BubbleApprovalState.of(phase, pendingCount = 1)

            assertFalse("$phase must not show the waiting banner", ui.waitingForApproval)
            assertTrue("$phase must keep the pill badge", ui.badgeVisible)
        }
    }

    @Test
    fun `sending is not yet a running turn`() {
        val ui = BubbleApprovalState.of(BubbleChatPhase.SENDING, pendingCount = 1)

        assertFalse(ui.waitingForApproval)
        assertTrue(ui.badgeVisible)
    }

    @Test
    fun `a negative count is clamped`() {
        val ui = BubbleApprovalState.of(BubbleChatPhase.RUNNING, pendingCount = -3)

        assertEquals(0, ui.pendingCount)
        assertFalse(ui.waitingForApproval)
        assertFalse(ui.badgeVisible)
    }
}
