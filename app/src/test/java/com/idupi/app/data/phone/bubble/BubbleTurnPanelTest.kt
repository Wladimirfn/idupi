package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the bubble panel gets out of the agent's way (phone-dashboard T7).
 *
 * The panel covered the screen while the agent ran, which is one of the causes
 * of the lost optimization (owner live check, turn pat-9ee16fd0e46a). The
 * decision is pure so the transitions are pinned without the service.
 */
class BubbleTurnPanelTest {

    private fun action(
        previous: BubbleChatPhase,
        current: BubbleChatPhase,
        mode: BubbleMode = BubbleMode.EXPANDED,
    ): BubbleTurnPanelAction = BubbleTurnPanel.onPhaseChange(previous, current, mode)

    @Test
    fun `the panel collapses when the owner's request starts`() {
        assertEquals(
            BubbleTurnPanelAction.COLLAPSE,
            action(BubbleChatPhase.IDLE, BubbleChatPhase.SENDING),
        )
        // Retrying a failed turn is a new start too.
        assertEquals(
            BubbleTurnPanelAction.COLLAPSE,
            action(BubbleChatPhase.FAILED, BubbleChatPhase.SENDING),
        )
    }

    @Test
    fun `a collapsed panel stays collapsed when the turn starts`() {
        assertEquals(
            BubbleTurnPanelAction.NONE,
            action(BubbleChatPhase.IDLE, BubbleChatPhase.SENDING, BubbleMode.COLLAPSED),
        )
    }

    @Test
    fun `staying busy never toggles the panel`() {
        assertEquals(
            BubbleTurnPanelAction.NONE,
            action(BubbleChatPhase.SENDING, BubbleChatPhase.RUNNING),
        )
        assertEquals(
            BubbleTurnPanelAction.NONE,
            action(BubbleChatPhase.RUNNING, BubbleChatPhase.RUNNING, BubbleMode.COLLAPSED),
        )
    }

    @Test
    fun `the answer opens the panel again`() {
        assertEquals(
            BubbleTurnPanelAction.EXPAND,
            action(BubbleChatPhase.RUNNING, BubbleChatPhase.DONE, BubbleMode.COLLAPSED),
        )
    }

    @Test
    fun `a failed turn opens the panel so the owner sees the error`() {
        assertEquals(
            BubbleTurnPanelAction.EXPAND,
            action(BubbleChatPhase.RUNNING, BubbleChatPhase.FAILED, BubbleMode.COLLAPSED),
        )
    }

    @Test
    fun `an answer never reopens a panel the owner opened herself`() {
        assertEquals(
            BubbleTurnPanelAction.NONE,
            action(BubbleChatPhase.RUNNING, BubbleChatPhase.DONE, BubbleMode.EXPANDED),
        )
    }

    @Test
    fun `closing the panel never counts as a finished turn`() {
        // collapse() closes the poll, which moves the phase to IDLE; that must
        // not reopen the panel the owner just minimized.
        assertEquals(
            BubbleTurnPanelAction.NONE,
            action(BubbleChatPhase.RUNNING, BubbleChatPhase.IDLE, BubbleMode.COLLAPSED),
        )
    }
}
