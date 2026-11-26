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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * After a decision, every surface must drop the approval and its notification
 * (phone-approvals T4). A decided / already-decided / gone approval is final; a
 * transport failure changes nothing so the owner can retry.
 */
class ApprovalDecisionFlowTest {

    private lateinit var fake: FakeIduPiClient
    private lateinit var ledger: FileApprovalLedger
    private lateinit var store: PendingApprovalsStore
    private lateinit var flow: ApprovalDecisionFlow
    private val cancelled = mutableListOf<String>()

    private val now = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        ledger = FileApprovalLedger(file = null, clock = { now })
        store = PendingApprovalsStore()
        cancelled.clear()
        flow = ApprovalDecisionFlow(
            decider = PhoneApprovalDecider(FakeClientSource(fake), ledger, clock = { now }),
            store = store,
            cancelNotification = { cancelled += it },
        )
        store.replace(listOf(approval()))
    }

    private fun approval(
        id: String = "apr-123456789abc",
        expiresAt: String = "2026-11-26T12:10:00Z",
    ) = PhoneApproval(
        id = id,
        state = PhoneApprovalState.PENDING,
        command = PhoneApprovalCommand(type = "tap"),
        reason = PhoneApprovalReason.RISKY_TEXT,
        target = "Enviar",
        expiresAt = expiresAt,
    )

    @Test
    fun `approving removes the row and cancels the notification`() = runTest {
        val result = flow.decide(approval(), approve = true)

        assertTrue(result is PhoneApprovalDecisionResult.Decided)
        assertEquals(listOf("apr-123456789abc" to true), fake.approvalDecisions)
        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `rejecting removes the row and cancels the notification`() = runTest {
        val result = flow.decide(approval(), approve = false)

        assertTrue(result is PhoneApprovalDecisionResult.Decided)
        assertEquals(listOf("apr-123456789abc" to false), fake.approvalDecisions)
        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `a conflict on approve removes the row and cancels the notification`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("EXPIRED")

        val result = flow.decide(approval(), approve = true)

        assertEquals(PhoneApprovalDecisionResult.Conflict("EXPIRED"), result)
        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `a conflict on reject also removes the row`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("ALREADY_DECIDED")

        flow.decide(approval(), approve = false)

        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `a not found on approve removes the row`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.NotFound

        flow.decide(approval(), approve = true)

        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `a not found on reject removes the row`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.NotFound

        flow.decide(approval(), approve = false)

        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `a transport failure keeps everything for a retry`() = runTest {
        fake.failDecisionWith = IOException("pc offline")

        val failure = runCatching { flow.decide(approval(), approve = true) }

        assertTrue(failure.isFailure)
        assertEquals(listOf("apr-123456789abc"), store.pending.value.map { it.id })
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `a decided approval never comes back from a stale poll`() = runTest {
        flow.decide(approval(), approve = true)

        store.replace(listOf(approval()))

        assertTrue(store.pending.value.isEmpty())
    }
}
