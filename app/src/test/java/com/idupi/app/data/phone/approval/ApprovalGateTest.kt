package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.control.CommandApproval
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pure decision logic for the phone-side second check (phone-approvals T2).
 *
 * Nothing here touches Android: the executor feeds the already-resolved node
 * text and the local ledger, and the gate answers whether the command may run.
 */
class ApprovalGateTest {

    private val now = 1_000_000L
    private lateinit var ledger: FileApprovalLedger

    @Before
    fun setUp() {
        ledger = FileApprovalLedger(file = null, clock = { now })
    }

    private fun grant(
        id: String,
        reason: String = "risky_text",
        target: String? = "Enviar",
        expiresAt: Long = now + 600_000L,
    ) = ApprovalGrant(
        approvalId = id,
        reason = reason,
        target = target,
        expiresAtMillis = expiresAt,
        grantedAtMillis = now,
    )

    private fun approval(
        id: String = "apr-123456789abc",
        reason: String = "risky_text",
        target: String? = "Enviar",
    ) = CommandApproval(approvalId = id, approvedTarget = target, reason = reason)

    private suspend fun checkNode(
        approval: CommandApproval?,
        vararg texts: String,
    ) = ApprovalGate.checkNode(
        approval = approval,
        nodeTexts = texts.toList(),
        ledger = ledger,
        nowMillis = now,
    )

    @Test
    fun `a free tap runs untouched`() = runTest {
        assertEquals(ApprovalGateResult.Allowed, checkNode(null, "Chats", "Abrir chats"))
    }

    @Test
    fun `an unapproved risky node is refused with the node text`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED, "Enviar mensaje"),
            checkNode(null, "", "Enviar mensaje"),
        )
    }

    @Test
    fun `an approval not granted on this phone never executes`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(), "Enviar"),
        )
    }

    @Test
    fun `a granted approval lets the command run and is consumed`() = runTest {
        ledger.record(grant("apr-123456789abc"))

        assertEquals(ApprovalGateResult.Allowed, checkNode(approval(), "Enviar"))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(), "Enviar"),
        )
    }

    @Test
    fun `an approved target that no longer matches is refused`() = runTest {
        ledger.record(grant("apr-123456789abc"))

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.TARGET_MISMATCH, "Enviar ahora"),
            checkNode(approval(), "Enviar ahora"),
        )
    }

    @Test
    fun `the approved target is compared after normalizing case and accents`() = runTest {
        ledger.record(grant("apr-123456789abc"))

        assertEquals(ApprovalGateResult.Allowed, checkNode(approval(), "ENVIÁR"))
    }

    @Test
    fun `a phone-detected target also has to match`() = runTest {
        ledger.record(
            grant(
                "apr-123456789abc",
                reason = "phone_detected_risky_target",
                target = "Pagar",
            )
        )

        assertEquals(
            ApprovalGateResult.Allowed,
            checkNode(approval(reason = "phone_detected_risky_target", target = "Pagar"), "Pagar"),
        )
    }

    @Test
    fun `a sensitive-app approval is bound to the approved coordinates`() = runTest {
        ledger.record(grant("apr-123456789abc", reason = "sensitive_app", target = "540,2100"))

        assertEquals(
            ApprovalGateResult.Allowed,
            ApprovalGate.checkNode(
                approval(reason = "sensitive_app", target = "540,2100"),
                listOf("Chat"),
                ledger,
                now,
                commandTarget = "540,2100",
            ),
        )
    }

    @Test
    fun `a sensitive-app approval refuses a different id`() = runTest {
        ledger.record(
            grant("apr-123456789abc", reason = "sensitive_app", target = "com.whatsapp:id/send")
        )

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.TARGET_MISMATCH, "Chat"),
            ApprovalGate.checkNode(
                approval(reason = "sensitive_app", target = "com.whatsapp:id/send"),
                listOf("Chat"),
                ledger,
                now,
                commandTarget = "com.whatsapp:id/photo",
            ),
        )
    }

    @Test
    fun `a type approval cannot authorize a tap`() = runTest {
        ledger.record(grant("apr-123456789abc", reason = "type_submit", target = null))

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(reason = "type_submit", target = null), "Enviar"),
        )
    }

    @Test
    fun `type with submit requires an approval`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED),
            ApprovalGate.checkSubmit(approval = null, ledger = ledger, nowMillis = now),
        )
    }

    @Test
    fun `type with submit runs only with a granted type approval and consumes it`() = runTest {
        ledger.record(grant("apr-123456789abc", reason = "type_submit", target = null))
        val approval = approval(reason = "type_submit", target = null)

        assertEquals(
            ApprovalGateResult.Allowed,
            ApprovalGate.checkSubmit(approval, ledger, now),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            ApprovalGate.checkSubmit(approval, ledger, now),
        )
    }

    @Test
    fun `a risky-text approval cannot authorize a submit`() = runTest {
        ledger.record(grant("apr-123456789abc", reason = "risky_text", target = "Enviar"))

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            ApprovalGate.checkSubmit(
                approval(reason = "risky_text", target = "Enviar"),
                ledger,
                now,
            ),
        )
    }

    @Test
    fun `an expired grant leaves the command refused`() = runTest {
        ledger.record(grant("apr-123456789abc", expiresAt = now - 1))

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(), "Enviar"),
        )
    }
}
