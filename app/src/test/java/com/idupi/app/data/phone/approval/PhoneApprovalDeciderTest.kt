package com.idupi.app.data.phone.approval

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The owner's approve/reject path (phone-approvals T2). Approval is recorded in
 * the local ledger BEFORE the decision reaches the server, because a PC process
 * holding the token could also call the decision route: only the phone's own
 * ledger proves the owner approved it here.
 */
class PhoneApprovalDeciderTest {

    private lateinit var fake: FakeIduPiClient
    private lateinit var ledger: FileApprovalLedger
    private lateinit var decider: PhoneApprovalDecider

    private val now = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        ledger = FileApprovalLedger(file = null, clock = { now })
        decider = PhoneApprovalDecider(FakeClientSource(fake), ledger, clock = { now })
    }

    private fun approval(
        id: String = "apr-123456789abc",
        reason: String = PhoneApprovalReason.RISKY_TEXT,
        target: String? = "Enviar",
        expiresAt: String = "2026-11-24T12:10:00Z",
        state: String = PhoneApprovalState.PENDING,
    ) = PhoneApproval(
        id = id,
        state = state,
        command = PhoneApprovalCommand(type = "tap"),
        reason = reason,
        target = target,
        expiresAt = expiresAt,
    )

    @Test
    fun `approve records the grant and posts the decision`() = runTest {
        val approval = approval()

        val result = decider.approve(approval)

        val decided = result as PhoneApprovalDecisionResult.Decided
        assertEquals(PhoneApprovalState.APPROVED, decided.approval.state)
        assertEquals(listOf("apr-123456789abc" to true), fake.approvalDecisions)
        assertNotNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `the grant is recorded before the post, so a failed post keeps it`() = runTest {
        fake.failDecisionWith = IOException("pc offline")

        val failure = runCatching { decider.approve(approval()) }

        assertEquals(true, failure.isFailure)
        assertNotNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `a 409 removes the grant and reports it no longer valid`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("EXPIRED")

        val result = decider.approve(approval())

        assertEquals(PhoneApprovalDecisionResult.Conflict("EXPIRED"), result)
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `a 404 removes the grant`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.NotFound

        val result = decider.approve(approval())

        assertEquals(PhoneApprovalDecisionResult.NotFound, result)
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `reject never records a grant`() = runTest {
        val result = decider.reject(approval())

        assertEquals(true, result is PhoneApprovalDecisionResult.Decided)
        assertEquals(listOf("apr-123456789abc" to false), fake.approvalDecisions)
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `an expired approval records nothing`() = runTest {
        val expired = approval(expiresAt = "2026-11-24T11:59:00Z")

        decider.approve(expired)

        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `a blank id is never posted`() = runTest {
        val result = decider.approve(approval(id = ""))

        assertEquals(PhoneApprovalDecisionResult.NotFound, result)
        assertEquals(0, fake.approvalDecisions.size)
    }
}
