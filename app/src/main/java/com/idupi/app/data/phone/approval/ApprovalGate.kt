package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.control.CommandApproval
import com.idupi.app.data.phone.shell.SHELL_DESTRUCTIVE_CATEGORY
import com.idupi.app.data.phone.shell.SHELL_OPTIMIZATION_CATEGORY
import com.idupi.app.data.phone.shell.SHELL_PLAN_CATEGORY
import com.idupi.app.data.phone.shell.ShellRisk
import com.idupi.app.domain.model.PhoneApprovalReason
import java.time.Instant

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
 * Since phone-bubble T18 an approval may also carry `ownerTurnId`: the server
 * approved a messaging step because the owner asked for it from THIS phone's
 * bubble. That is a second, equally local proof: the grant is recorded only
 * while [OwnerTurnRegistry] says the turn is still queued/running here, only
 * for the `message` category and only for the reasons a bubble turn can
 * produce. Everything else keeps refusing exactly as before.
 *
 * Pure except for the ledger and registry lookups, so the decision table is
 * unit-tested.
 */
object ApprovalGate {

    /** The phone refused; the server turns it into an approval request. */
    const val APPROVAL_REQUIRED = "APPROVAL_REQUIRED"

    /** An approval id arrived, but the owner never approved it on this phone. */
    const val NOT_GRANTED_ON_PHONE = "APPROVAL_NOT_GRANTED_ON_PHONE"

    /** The approved text no longer matches the node the command would touch. */
    const val TARGET_MISMATCH = "APPROVED_TARGET_MISMATCH"

    /** The only category an owner bubble turn may auto-approve. */
    private const val OWNER_REQUEST_CATEGORY = "message"

    /** Reasons the server issues for a step an owner turn approved. */
    private val OWNER_REQUEST_REASONS = setOf(
        PhoneApprovalReason.TYPE_SUBMIT,
        PhoneApprovalReason.RISKY_TEXT,
        PhoneApprovalReason.SENSITIVE_APP,
    )

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

        acceptOwnerRequest(approval, ledger, nowMillis)
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
     * Checks a typed `shell` command (phone-shell T4/T4b/T4c).
     *
     * The phone classifies the risk itself: info/scan/enable are free,
     * trim-caches/force-stop/background are optimization, disable/uninstall are
     * destructive, and a batch is a plan. Free runs.
     *
     * An optimization, destructive or plan approval auto-grants in an active
     * owner turn when it carries the category the server attached to that
     * auto-approval (T4c: the owner's own shell request is the consent). The
     * grant is one use and needs a live turn and a future expiry. Outside an
     * owner turn, a plan and a destructive step only accept the one-use local
     * grant the owner gave on this phone; a plan is never auto-approved there.
     */
    suspend fun checkShell(
        approval: CommandApproval?,
        risk: ShellRisk,
        ledger: ApprovalLedger,
        nowMillis: Long,
    ): ApprovalGateResult {
        if (risk == ShellRisk.FREE) return ApprovalGateResult.Allowed
        if (approval == null) return ApprovalGateResult.Refused(APPROVAL_REQUIRED)

        acceptOwnerShellRequest(approval, risk, ledger, nowMillis)
        ledger.consume(approval.approvalId, nowMillis)
            ?: return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        return ApprovalGateResult.Allowed
    }

    /**
     * Materializes the grant an owner-turn shell approval carries. Fail closed
     * on anything the server would not send for such a step: no turn id, a
     * category that does not match the command's risk, an inactive turn, a
     * missing or expired deadline, or a replayed approval id.
     */
    private suspend fun acceptOwnerShellRequest(
        approval: CommandApproval,
        risk: ShellRisk,
        ledger: ApprovalLedger,
        nowMillis: Long,
    ) {
        val category = when (risk) {
            ShellRisk.OPTIMIZATION -> SHELL_OPTIMIZATION_CATEGORY
            ShellRisk.DESTRUCTIVE -> SHELL_DESTRUCTIVE_CATEGORY
            ShellRisk.PLAN -> SHELL_PLAN_CATEGORY
            ShellRisk.FREE -> return
        }
        if (approval.category != category) return
        val turnId = approval.ownerTurnId?.trim().orEmpty()
        if (turnId.isEmpty()) return
        if (!OwnerTurnRegistry.isActive(turnId)) return
        val expiresAt = runCatching { Instant.parse(approval.expiresAt.orEmpty()) }
            .getOrNull() ?: return
        val expiresAtMillis = expiresAt.toEpochMilli()
        if (expiresAtMillis <= nowMillis) return
        if (!OwnerTurnRegistry.claimOwnerApproval(approval.approvalId)) return
        ledger.record(
            ApprovalGrant(
                approvalId = approval.approvalId,
                reason = approval.reason,
                target = null,
                expiresAtMillis = expiresAtMillis,
                grantedAtMillis = nowMillis,
            )
        )
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

        acceptOwnerRequest(approval, ledger, nowMillis)
        val grant = ledger.consume(approval.approvalId, nowMillis)
            ?: return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        val reason = grant.reason.ifBlank { approval.reason }
        if (reason != PhoneApprovalReason.TYPE_SUBMIT) {
            return ApprovalGateResult.Refused(NOT_GRANTED_ON_PHONE)
        }
        return ApprovalGateResult.Allowed
    }

    /**
     * Records the local grant an owner-request approval already carries
     * (phone-bubble, T18), so the normal consume/target checks below apply
     * unchanged and the grant is one use, exactly like a manual tap.
     *
     * Fail closed on anything the server would not send for a bubble turn: no
     * turn id, a non-`message` category, an unknown reason, a turn that is not
     * active here, a missing/unparseable expiry or an expired one. The command
     * then keeps being refused with [NOT_GRANTED_ON_PHONE].
     */
    private suspend fun acceptOwnerRequest(
        approval: CommandApproval,
        ledger: ApprovalLedger,
        nowMillis: Long,
    ) {
        val turnId = approval.ownerTurnId?.trim().orEmpty()
        if (turnId.isEmpty()) return
        if (approval.category != OWNER_REQUEST_CATEGORY) return
        val reason = approval.reason.trim()
        if (reason !in OWNER_REQUEST_REASONS) return
        if (!OwnerTurnRegistry.isActive(turnId)) return
        val expiresAt = runCatching { Instant.parse(approval.expiresAt.orEmpty()) }
            .getOrNull() ?: return
        val expiresAtMillis = expiresAt.toEpochMilli()
        if (expiresAtMillis <= nowMillis) return
        // One server approval yields one one-use ledger grant, exactly like the
        // owner's tap does; a replayed command is refused.
        if (!OwnerTurnRegistry.claimOwnerApproval(approval.approvalId)) return

        ledger.record(
            ApprovalGrant(
                approvalId = approval.approvalId,
                reason = reason,
                target = approval.approvedTarget,
                expiresAtMillis = expiresAtMillis,
                grantedAtMillis = nowMillis,
            )
        )
    }

    private fun targetMatches(approvedTarget: String?, nodeTexts: List<String>): Boolean {
        val target = approvedTarget?.trim().orEmpty()
        if (target.isEmpty()) return false
        val normalized = PhoneRiskText.normalize(target)
        return nodeTexts.any { PhoneRiskText.normalize(it.trim()) == normalized }
    }
}
