package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.control.CommandApproval
import com.idupi.app.data.phone.shell.ShellRisk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Phone-side gate for typed shell commands (phone-shell T4).
 *
 * Free runs; optimization accepts an owner-turn auto-approval exactly like a
 * message step or a local grant; destructive only accepts a grant the owner
 * actually gave on this phone.
 */
class ApprovalGateShellTest {

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

    private fun approval(
        id: String = "apr-shell-123456",
        category: String? = "optimization",
        turnId: String? = OWNER_TURN,
        expiresAt: String? = Instant.ofEpochMilli(now + 600_000L).toString(),
        reason: String = "optimization",
    ) = CommandApproval(
        approvalId = id,
        reason = reason,
        ownerTurnId = turnId,
        category = category,
        expiresAt = expiresAt,
    )

    private fun grant(
        id: String,
        reason: String = "destructive",
        expiresAt: Long = now + 600_000L,
    ) = ApprovalGrant(
        approvalId = id,
        reason = reason,
        target = null,
        expiresAtMillis = expiresAt,
        grantedAtMillis = now,
    )

    private suspend fun check(
        approval: CommandApproval?,
        risk: ShellRisk,
    ) = ApprovalGate.checkShell(approval, risk, ledger, now)

    @Test
    fun `free shell commands run without an approval`() = runTest {
        assertEquals(ApprovalGateResult.Allowed, check(null, ShellRisk.FREE))
    }

    @Test
    fun `optimization and destructive need an approval`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED),
            check(null, ShellRisk.OPTIMIZATION),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED),
            check(null, ShellRisk.DESTRUCTIVE),
        )
    }

    @Test
    fun `an owner turn auto-approves an optimization once`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        val optimization = approval()

        assertEquals(ApprovalGateResult.Allowed, check(optimization, ShellRisk.OPTIMIZATION))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(optimization, ShellRisk.OPTIMIZATION),
        )
    }

    @Test
    fun `an optimization approval without a live turn is refused`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(approval(), ShellRisk.OPTIMIZATION),
        )
    }

    @Test
    fun `an optimization approval needs the optimization category`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(approval(category = "message"), ShellRisk.OPTIMIZATION),
        )
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(approval(category = null), ShellRisk.OPTIMIZATION),
        )
    }

    @Test
    fun `an expired optimization approval is refused`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        val expired = approval(
            expiresAt = Instant.ofEpochMilli(now - 1L).toString(),
        )

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(expired, ShellRisk.OPTIMIZATION),
        )
    }

    @Test
    fun `destructive is never auto-approved from the wire`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        val destructive = approval(category = "destructive", reason = "destructive")

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(destructive, ShellRisk.DESTRUCTIVE),
        )
    }

    @Test
    fun `destructive runs with the grant the owner gave on this phone`() = runTest {
        ledger.record(grant("apr-shell-destructive"))
        val destructive = approval(
            id = "apr-shell-destructive",
            category = "destructive",
            reason = "destructive",
        )

        assertEquals(ApprovalGateResult.Allowed, check(destructive, ShellRisk.DESTRUCTIVE))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(destructive, ShellRisk.DESTRUCTIVE),
        )
    }

    @Test
    fun `optimization also accepts a manual local grant`() = runTest {
        ledger.record(grant("apr-shell-manual", reason = "optimization"))
        val manual = approval(id = "apr-shell-manual", category = null, turnId = null)

        assertEquals(ApprovalGateResult.Allowed, check(manual, ShellRisk.OPTIMIZATION))
    }

    @Test
    fun `a plan always needs the owner grant, never an owner-turn auto-approval`() = runTest {
        OwnerTurnRegistry.register(OWNER_TURN)
        val plan = approval(
            id = "apr-shell-plan",
            category = "plan",
            reason = "shell_plan",
        )

        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(plan, ShellRisk.PLAN),
        )

        ledger.record(grant("apr-shell-plan", reason = "shell_plan"))
        assertEquals(ApprovalGateResult.Allowed, check(plan, ShellRisk.PLAN))
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.NOT_GRANTED_ON_PHONE),
            check(plan, ShellRisk.PLAN),
        )
    }

    @Test
    fun `a plan without an approval is refused`() = runTest {
        assertEquals(
            ApprovalGateResult.Refused(ApprovalGate.APPROVAL_REQUIRED),
            check(null, ShellRisk.PLAN),
        )
    }

    private companion object {
        const val OWNER_TURN = "pat-0123456789ab"
    }
}
