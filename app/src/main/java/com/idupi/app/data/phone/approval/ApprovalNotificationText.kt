package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalReason
import java.time.Instant
import kotlin.math.ceil

/**
 * Copy for the pending-approval notification and card (phone-approvals T2):
 * neutral Latin American Spanish, safe with a null app label or target.
 */
object ApprovalNotificationText {

    const val TITLE = "IDUPI pide tu aprobación"

    private const val FALLBACK = "Acción pendiente en el teléfono"

    fun describe(approval: PhoneApproval): String {
        if (approval.command.type == "shell") return ApprovalCardText.actionLine(approval)
        val app = approval.foregroundApp?.label?.takeIf { it.isNotBlank() }
        return when (approval.reason) {
            PhoneApprovalReason.TYPE_SUBMIT ->
                if (app != null) "Escribir y enviar en $app" else "Escribir y enviar"
            PhoneApprovalReason.SENSITIVE_APP ->
                if (app != null) "Tocar en $app" else "Tocar en el teléfono"
            PhoneApprovalReason.RISKY_TEXT,
            PhoneApprovalReason.PHONE_DETECTED,
            -> tapDescription(approval, app)
            else -> FALLBACK
        }
    }

    /** "Vence en 7 min", "Vence en menos de 1 min", "Venció", or null. */
    fun remainingLabel(expiresAt: String, nowMillis: Long): String? {
        val expiry = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
            ?: return null
        val remaining = expiry - nowMillis
        return when {
            remaining <= 0L -> "Venció"
            remaining < 60_000L -> "Vence en menos de 1 min"
            else -> "Vence en ${ceil(remaining / 60_000.0).toLong()} min"
        }
    }

    private fun tapDescription(approval: PhoneApproval, app: String?): String {
        val target = approval.target?.takeIf { it.isNotBlank() }
        val action = when {
            target == null -> "Tocar"
            approval.command.type == "long_press" -> "Mantener presionado «$target»"
            else -> "Tocar «$target»"
        }
        return when {
            app != null -> "$action en $app"
            target != null -> action
            else -> "$action en el teléfono"
        }
    }
}
