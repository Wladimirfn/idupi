package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.repository.IduPiClientSource
import java.time.Instant

/**
 * The owner's approve / reject path, shared by the notification action and the
 * Ajustes card (phone-approvals T2).
 *
 * The approve flow is ledger-then-post on purpose: the local grant is written
 * BEFORE the decision reaches the server, so a command the server requeues can
 * only run here if this phone actually recorded the owner's tap.
 */
class PhoneApprovalDecider(
    private val clientSource: IduPiClientSource,
    private val ledger: ApprovalLedger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun approve(approval: PhoneApproval): PhoneApprovalDecisionResult {
        if (approval.id.isBlank()) return PhoneApprovalDecisionResult.NotFound

        grantFor(approval, clock())?.let { ledger.record(it) }
        val result = clientSource.client.decidePhoneApproval(approval.id, approve = true)
        when (result) {
            is PhoneApprovalDecisionResult.Decided -> Unit
            is PhoneApprovalDecisionResult.Conflict -> ledger.remove(approval.id)
            PhoneApprovalDecisionResult.NotFound -> ledger.remove(approval.id)
        }
        return result
    }

    suspend fun reject(approval: PhoneApproval): PhoneApprovalDecisionResult {
        if (approval.id.isBlank()) return PhoneApprovalDecisionResult.NotFound
        ledger.remove(approval.id)
        return clientSource.client.decidePhoneApproval(approval.id, approve = false)
    }

    private fun grantFor(approval: PhoneApproval, nowMillis: Long): ApprovalGrant? {
        if (approval.state != PhoneApprovalState.PENDING) return null
        val expiresAt = runCatching { Instant.parse(approval.expiresAt).toEpochMilli() }
            .getOrNull() ?: return null
        if (expiresAt <= nowMillis) return null
        return ApprovalGrant(
            approvalId = approval.id,
            reason = approval.reason,
            target = approval.target,
            expiresAtMillis = expiresAt,
            grantedAtMillis = nowMillis,
        )
    }
}
