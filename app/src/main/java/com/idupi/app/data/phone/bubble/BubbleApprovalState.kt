package com.idupi.app.data.phone.bubble

/** What the bubble should show about the phone approval queue. */
data class BubbleApprovalUi(
    /** Banner inside the expanded panel: the PC is waiting for the owner. */
    val waitingForApproval: Boolean,
    /** Small badge on the collapsed pill, independent of the active turn. */
    val badgeVisible: Boolean,
    val pendingCount: Int,
)

/**
 * Maps the active turn and the process-wide pending approvals (phone-approvals
 * T4) into what the bubble renders (phone-bubble T7).
 *
 * The bubble never approves or rejects: it announces the wait and links to
 * Alertas, which owns the decision. The banner belongs to a running turn
 * because that is when the agent is blocked on the owner; the badge is shown
 * whenever something is pending so a minimized bubble still tells her.
 */
object BubbleApprovalState {

    fun of(phase: BubbleChatPhase, pendingCount: Int): BubbleApprovalUi {
        val count = pendingCount.coerceAtLeast(0)
        return BubbleApprovalUi(
            waitingForApproval = count > 0 && phase == BubbleChatPhase.RUNNING,
            badgeVisible = count > 0,
            pendingCount = count,
        )
    }
}
