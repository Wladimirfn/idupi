package com.idupi.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Phone approval wire models (phone-approvals T2).
 *
 * Shapes mirror `GET /api/v1/phone/approvals` in `idupi-server/docs/phone.md`
 * ("Approvals"). Every field has a default so a document from a newer server
 * decodes instead of failing (the client uses `encodeDefaults = false`).
 */
@Serializable
data class PhoneApprovalApp(
    val packageName: String = "",
    val label: String = "",
)

/** The command an approval guards: its type and normalized arguments. */
@Serializable
data class PhoneApprovalCommand(
    val type: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PhoneApproval(
    val id: String = "",
    val state: String = PhoneApprovalState.PENDING,
    val command: PhoneApprovalCommand = PhoneApprovalCommand(),
    val reason: String = "",
    /**
     * Additive detail the server sends for a destructive step that needs its
     * own wording (phone-dashboard T6). Null for every other approval and for
     * an older server.
     */
    val riskKind: String? = null,
    val target: String? = null,
    val foregroundApp: PhoneApprovalApp? = null,
    val requestedBy: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
    val decidedAt: String? = null,
    /**
     * The bubble turn that created the command, when any (phone-dashboard
     * T4). A destructive approval is voiced only while this exact turn is
     * still active on the device, then the same condition as T23 applies.
     */
    val ownerTurnId: String? = null,
    val commandId: String? = null,
    val resultCommandId: String? = null,
)

/** `GET /api/v1/phone/approvals?state=pending`. */
@Serializable
data class PhoneApprovalsResponse(
    val approvals: List<PhoneApproval> = emptyList(),
)

/** Approval lifecycle values the server uses. */
object PhoneApprovalState {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
}

/** Why the server gated the command. */
object PhoneApprovalReason {
    const val RISKY_TEXT = "risky_text"
    const val TYPE_SUBMIT = "type_submit"
    const val SENSITIVE_APP = "sensitive_app"
    const val PHONE_DETECTED = "phone_detected_risky_target"

    /** Shell risk categories (phone-shell T4) used as approval reasons. */
    const val OPTIMIZATION = "optimization"
    const val DESTRUCTIVE = "destructive"
}

/**
 * Optional `riskKind` values (phone-dashboard T6). They refine a destructive
 * approval so the bubble can name what is lost; the approval is still a normal
 * gate and runs only with a grant.
 */
object PhoneApprovalRiskKind {
    const val DATA_WIPE = "data_wipe"
}

/**
 * Typed answer to `POST /phone/approvals/{id}/decision`. The client maps the
 * HTTP status and error code here so callers never branch on exceptions.
 */
sealed interface PhoneApprovalDecisionResult {
    data class Decided(val approval: PhoneApproval) : PhoneApprovalDecisionResult

    /** 409: [code] is [CODE_EXPIRED] or [CODE_ALREADY_DECIDED]. */
    data class Conflict(val code: String) : PhoneApprovalDecisionResult

    /** 404: the approval does not exist (or an already pruned id). */
    data object NotFound : PhoneApprovalDecisionResult

    companion object {
        const val CODE_EXPIRED = "EXPIRED"
        const val CODE_ALREADY_DECIDED = "ALREADY_DECIDED"
    }
}
