package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.control.CommandApproval
import com.idupi.app.domain.model.PhoneApprovalReason

/** Whether a command may run on the phone, and why not when it may not. */
sealed interface ApprovalGateResult {
    data object Allowed : ApprovalGateResult

    /** @property nodeText risky text reported to the server for the approval. */
    data class Refused(val error: String, val nodeText: String? = null) : ApprovalGateResult
}

/**
 * Phone-side second check (phone-approvals T2).
 *
 * The server already gates outbound actions, but the PC holds the same bearer
 * token and could try to approve itself. This gate only accepts an approval id
 * that the owner granted on this phone, still valid, and matching the node the
 * command is about to touch.
 *
 * Pure except for the ledger lookup, so the decision table is unit-tested.
 */
object ApprovalGate {

    /** The phone refused; the server turns it into an approval request. */
    const val APPROVAL_REQUIRED = "APPROVAL_REQUIRED"

    /** An approval id arrived, but the owner never approved it on this phone. */
    const val NOT_GRANTED_ON_PHONE = "APPROVAL_NOT_GRANTED_ON_PHONE"

    /** The approved text no longer matches the node the command would touch. */
    const val TARGET_MISMATCH = "APPROVED_TARGET_MISMATCH"

    /**
     * Checks a tap / long_press against the resolved node texts.
     *
     * With no approval, a risky node is refused. With an approval, the grant is
     * consumed before any check (one use, even on a mismatch) and the reason is
     * bound to the action kind: a `type_submit` grant never authorizes a tap.
     *
     * @param commandTarget the target as the server normalized it (`text`, the
     *   view id, or `x,y`). A `sensitive_app` approval has no node text to
     *   compare, so it is bound to this exact target instead.
     */
    suspend fun checkNode(
        approval: CommandApproval?,
        nodeTexts: List<String>,
        ledger: ApprovalLedger,
        nowMillis: Long,
        commandTarget: String? = null,
    ): ApprovalGateResult {
        val riskyText = nodeTexts.firstOrNull { PhoneRiskText.isRisky(it) }

        if (approval == null) {
            return if (riskyText != null) {
                ApprovalGateResult.Refused(APPROVAL_REQUIRED, riskyText)
            } else {
                ApprovalGateResult.Allowed
            }
        }

        val grant = ledger.consume(approval.approvalId, nowMillis)
            ?: return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        val reason = grant.reason.ifBlank { approval.reason }
        if (reason == PhoneApprovalReason.TYPE_SUBMIT) {
            return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        }

        val needsTextMatch = reason == PhoneApprovalReason.RISKY_TEXT ||
            reason == PhoneApprovalReason.PHONE_DETECTED
        val mismatch = when {
            needsTextMatch -> !targetMatches(grant.target, nodeTexts)
            reason == PhoneApprovalReason.SENSITIVE_APP ->
                grant.target?.trim() != commandTarget?.trim()
            else -> false
        }
        if (mismatch) {
            return ApprovalGateResult.Refused(
                TARGET_MISMATCH,
                riskyText ?: nodeTexts.firstOrNull { it.isNotBlank() },
            )
        }
        return ApprovalGateResult.Allowed
    }

    /**
     * Checks a `type` with `submit: true`. Composing text is free; pressing the
     * send action is not, and only a `type_submit` grant authorizes it.
     */
    suspend fun checkSubmit(
        approval: CommandApproval?,
        ledger: ApprovalLedger,
        nowMillis: Long,
    ): ApprovalGateResult {
        if (approval == null) return ApprovalGateResult.Refused(APPROVAL_REQUIRED)

        val grant = ledger.consume(approval.approvalId, nowMillis)
            ?: return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        val reason = grant.reason.ifBlank { approval.reason }
        if (reason != PhoneApprovalReason.TYPE_SUBMIT) {
            return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        }
        return ApprovalGateResult.Allowed
    }

    private fun targetMatches(approvedTarget: String?, nodeTexts: List<String>): Boolean {
        val target = approvedTarget?.trim().orEmpty()
        if (target.isEmpty()) return false
        val normalized = PhoneRiskText.normalize(target)
        return nodeTexts.any { PhoneRiskText.normalize(it.trim()) == normalized }
    }
}
