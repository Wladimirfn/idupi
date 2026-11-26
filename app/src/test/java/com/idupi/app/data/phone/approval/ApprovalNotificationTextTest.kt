package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalApp
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Notification copy for a pending approval (phone-approvals T2): neutral Latin
 * American Spanish, and safe when the server had no foreground app or target.
 */
class ApprovalNotificationTextTest {

    private fun approval(
        reason: String,
        target: String?,
        appLabel: String?,
        type: String = "tap",
    ) = PhoneApproval(
        id = "apr-123456789abc",
        state = "pending",
        command = PhoneApprovalCommand(type = type),
        reason = reason,
        target = target,
        foregroundApp = appLabel?.let {
            PhoneApprovalApp(packageName = "com.example", label = it)
        },
    )

    @Test
    fun `a risky tap names the target and the app`() {
        assertEquals(
            "Tocar «Enviar» en WhatsApp",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.RISKY_TEXT, "Enviar", "WhatsApp")
            ),
        )
    }

    @Test
    fun `a risky tap without an app still names the target`() {
        assertEquals(
            "Tocar «Enviar»",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.RISKY_TEXT, "Enviar", null)
            ),
        )
    }

    @Test
    fun `a risky tap without a target names the app`() {
        assertEquals(
            "Tocar en WhatsApp",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.RISKY_TEXT, null, "WhatsApp")
            ),
        )
    }

    @Test
    fun `a risky tap with no app and no target is generic`() {
        assertEquals(
            "Tocar en el teléfono",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.RISKY_TEXT, null, null)
            ),
        )
    }

    @Test
    fun `a long press says so`() {
        assertEquals(
            "Mantener presionado «Mensaje» en WhatsApp",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.RISKY_TEXT, "Mensaje", "WhatsApp", type = "long_press")
            ),
        )
    }

    @Test
    fun `a phone-detected target reads like a risky tap`() {
        assertEquals(
            "Tocar «Pagar» en Mercado Pago",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.PHONE_DETECTED, "Pagar", "Mercado Pago")
            ),
        )
    }

    @Test
    fun `a sensitive-app tap does not print a resource id`() {
        assertEquals(
            "Tocar en WhatsApp",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.SENSITIVE_APP, "com.whatsapp:id/send", "WhatsApp")
            ),
        )
    }

    @Test
    fun `type with submit describes writing and sending`() {
        assertEquals(
            "Escribir y enviar en WhatsApp",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.TYPE_SUBMIT, null, "WhatsApp", type = "type")
            ),
        )
    }

    @Test
    fun `type with submit without an app is still clear`() {
        assertEquals(
            "Escribir y enviar",
            ApprovalNotificationText.describe(
                approval(PhoneApprovalReason.TYPE_SUBMIT, null, null, type = "type")
            ),
        )
    }

    @Test
    fun `an unknown reason falls back to a generic description`() {
        assertEquals(
            "Acción pendiente en el teléfono",
            ApprovalNotificationText.describe(approval("other_reason", null, null)),
        )
    }

    @Test
    fun `the remaining time is shown in whole minutes`() {
        val now = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

        assertEquals(
            "Vence en 7 min",
            ApprovalNotificationText.remainingLabel("2026-11-24T12:07:00Z", now),
        )
        assertEquals(
            "Vence en menos de 1 min",
            ApprovalNotificationText.remainingLabel("2026-11-24T12:00:30Z", now),
        )
        assertEquals(
            "Vence en 2 min",
            ApprovalNotificationText.remainingLabel("2026-11-24T12:01:30Z", now),
        )
        assertEquals(
            "Venció",
            ApprovalNotificationText.remainingLabel("2026-11-24T11:59:00Z", now),
        )
    }

    @Test
    fun `a missing or unreadable deadline has no label`() {
        assertNull(ApprovalNotificationText.remainingLabel("", 1_000L))
        assertNull(ApprovalNotificationText.remainingLabel("soon", 1_000L))
    }
}
