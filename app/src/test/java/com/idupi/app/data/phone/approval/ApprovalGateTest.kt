package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.control.CommandApproval
import kotlinx.coroutines.test.runTest
import org.junit.After
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
        OwnerTurnRegistry.clear()
    }

    @After
    fun tearDown() {
        OwnerTurnRegistry.clear()
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

    /** The shape the server sends when an owner bubble turn approved a step. */
    private fun ownerApproval(
        id: String = "apr-123456789abc",
        reason: String = "risky_text",
        target: String? = "Enviar",
        turnId: String? = OWNER_TURN,
        category: String? = "message",
        expiresAt: String? = java.time.Instant.ofEpochMilli(now + 600_000L).toString(),
    ) = CommandApproval(
        approvalId = id,
        approvedTarget = target,
        reason = reason,
        ownerTurnId = turnId,
        category = category,
        expiresAt = expiresAt,
    )

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
    fun `a data wipe node is a gated destructive step, not a hard block`() = runTest {
        // Owner correction (phone-dashboard T6): deleting data needs an
        // approval that names what is lost, never a deterministic refusal.
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED, "Borrar datos"),
            checkNode(null, "Borrar datos"),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED, "Clear data"),
            checkNode(null, "Clear data"),
        )

        // A grant the owner gave on this phone runs it once, like any other
        // destructive target.
        ledger.record(grant("apr-123456789abc", target = "Borrar datos"))
        assertEquals(
            ApprovalGateResult.Allowed,
            checkNode(approval(target = "Borrar datos"), "Borrar datos"),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(target = "Borrar datos"), "Borrar datos"),
        )
    }

    @Test
    fun `clearing the cache still goes through the normal approval gate`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED, "Borrar caché"),
            checkNode(null, "Borrar caché"),
        )
    }

    @Test
    fun `uninstalling requires approval like any destructive step`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED, "Desinstalar"),
            checkNode(null, "Desinstalar"),
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

    // --- owner-request approvals (phone-bubble, T18) ----------------------

    @Test
    fun `an owner-request approval runs while its turn is active on this phone`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(ApprovalGateResult.Allowed, checkNode(ownerApproval(), "Enviar"))
    }

    @Test
    fun `an owner-request approval is one use like a manual grant`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(ApprovalGateResult.Allowed, checkNode(ownerApproval(), "Enviar"))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(), "Enviar"),
        )
    }

    @Test
    fun `an owner-request approval is refused once the turn is terminal`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        OwnerTurnRegistry.remove(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(), "Enviar"),
        )
    }

    @Test
    fun `an owner-request approval for an unknown turn is refused`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(), "Enviar"),
        )
    }

    @Test
    fun `an owner-request approval without a turn id is a manual approval`() = runTest {
        // No local grant: the registry is irrelevant for the manual shape.
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(turnId = null), "Enviar"),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(approval(), "Enviar"),
        )
    }

    @Test
    fun `an owner-request approval outside the message category is refused`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(category = "money"), "Pagar"),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(category = null), "Pagar"),
        )
    }

    @Test
    fun `an owner-request approval with an unknown reason is refused`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(reason = ""), "Enviar"),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(ownerApproval(reason = "phone_detected_risky_target"), "Enviar"),
        )
    }

    @Test
    fun `an expired owner-request approval is refused`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            checkNode(
                ownerApproval(
                    expiresAt = java.time.Instant.ofEpochMilli(now - 1).toString(),
                ),
                "Enviar",
            ),
        )
    }

    @Test
    fun `an owner-request tap still has to match the approved target`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.TARGET_MISMATCH, "Enviar ahora"),
            checkNode(ownerApproval(), "Enviar ahora"),
        )
    }

    @Test
    fun `an owner-request submit runs only with the type approval`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        val submit = ownerApproval(reason = "type_submit", target = null)

        assertEquals(ApprovalGateResult.Allowed, ApprovalGate.checkSubmit(submit, ledger, now))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            ApprovalGate.checkSubmit(submit, ledger, now),
        )
    }

    @Test
    fun `an owner-request tap approval cannot authorize a submit`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            ApprovalGate.checkSubmit(ownerApproval(), ledger, now),
        )
    }

    /** The turn ids are process-wide, so each test starts clean. */
    private companion object {
        const val OWNER_TURN = "pat-0123456789ab"
    }
}
