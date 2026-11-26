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
    val target: String? = null,
    val foregroundApp: PhoneApprovalApp? = null,
    val requestedBy: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
    val decidedAt: String? = null,
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
