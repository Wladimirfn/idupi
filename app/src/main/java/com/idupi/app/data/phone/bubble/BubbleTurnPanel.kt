package com.idupi.app.data.phone.bubble

/** What the panel does when a bubble turn changes phase (phone-dashboard T7). */
enum class BubbleTurnPanelAction { NONE, COLLAPSE, EXPAND }

/**
 * The panel gets out of the agent's way (phone-dashboard T7).
 *
 * The owner's request starts from the expanded panel, which covered the screen
 * the agent had to read (owner live check, turn pat-9ee16fd0e46a). The panel
 * therefore collapses to the circle as soon as the turn starts and opens again
 * when the turn ends with an answer or a failure, so the owner reads it without
 * tapping anything. Tapping the circle during the turn still opens the panel:
 * the collapse only fires on the transition into a busy phase.
 *
 * A close (the owner's Minimizar) also leaves the phase at IDLE, which is not a
 * finished turn: it never reopens anything.
 */
object BubbleTurnPanel {

    /** Description of the circle while a turn runs (phone-dashboard T7). */
    const val WORKING_DESCRIPTION = "IDUPI está trabajando en tu pedido"

    private fun isBusy(phase: BubbleChatPhase): Boolean =
        phase == BubbleChatPhase.SENDING || phase == BubbleChatPhase.RUNNING

    fun onPhaseChange(
        previous: BubbleChatPhase,
        current: BubbleChatPhase,
        mode: BubbleMode,
    ): BubbleTurnPanelAction {
        val started = isBusy(current) && !isBusy(previous)
        if (started && mode == BubbleMode.EXPANDED) return BubbleTurnPanelAction.COLLAPSE

        // A failure is also a result the owner must see: the panel carries the
        // error and the Reintentar button.
        val finished = isBusy(previous) &&
            (current == BubbleChatPhase.DONE || current == BubbleChatPhase.FAILED)
        if (finished && mode == BubbleMode.COLLAPSED) return BubbleTurnPanelAction.EXPAND

        return BubbleTurnPanelAction.NONE
    }
}
