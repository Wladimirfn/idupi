package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import kotlinx.coroutines.CancellationException

/**
 * One decision entry point for every surface (phone-approvals T4).
 *
 * The notification action, the Alertas card and the Ajustes card all call
 * [decide], so a decided approval leaves the shared list and its notification
 * goes away no matter where the owner tapped. The ledger semantics live in
 * [PhoneApprovalDecider] and are untouched: approve writes the local grant
 * before posting, Conflict / NotFound remove it, reject removes it.
 *
 * [cancelNotification] is injected so this is unit-testable without Android;
 * [PhoneApprovals] wires the real [ApprovalNotifier].
 */
class ApprovalDecisionFlow(
    private val decider: PhoneApprovalDecider,
    private val store: PendingApprovalsStore,
    private val cancelNotification: (String) -> Unit,
) {

    /**
     * Decides [approval] and clears it everywhere once the server answered.
     *
     * A transport failure propagates with the row and its notification still in
     * place, so the owner can retry; [PhoneApprovalDecider] already rolled the
     * ledger back in that case only through the server's Conflict / NotFound.
     */
    suspend fun decide(
        approval: PhoneApproval,
        approve: Boolean,
    ): PhoneApprovalDecisionResult {
        val result = try {
            if (approve) decider.approve(approval) else decider.reject(approval)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

        when (result) {
            is PhoneApprovalDecisionResult.Decided,
            is PhoneApprovalDecisionResult.Conflict,
            PhoneApprovalDecisionResult.NotFound,
            -> clear(approval.id)
        }
        return result
    }

    private fun clear(id: String) {
        if (id.isBlank()) return
        store.remove(id)
        runCatching { cancelNotification(id) }
    }
}
