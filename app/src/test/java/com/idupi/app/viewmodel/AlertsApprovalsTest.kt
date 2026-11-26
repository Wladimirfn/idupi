package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.phone.approval.ApprovalCardText
import com.idupi.app.data.phone.approval.ApprovalDecisionFlow
import com.idupi.app.data.phone.approval.FileApprovalLedger
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovalDecider
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.model.PhoneApprovalsResponse
import java.io.IOException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pending phone approvals in Alertas (phone-approvals T4): the section reads the
 * process-wide store, opening the screen refreshes it once, and a decision from
 * here removes the row and its notification through the shared flow.
 */
class AlertsApprovalsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var store: PendingApprovalsStore
    private val cancelled = mutableListOf<String>()

    private val now = 1_766_000_000_000L

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        store = PendingApprovalsStore()
        cancelled.clear()
    }

    private fun approval(id: String = "apr-123456789abc") = PhoneApproval(
        id = id,
        state = PhoneApprovalState.PENDING,
        command = PhoneApprovalCommand(type = "tap"),
        reason = PhoneApprovalReason.RISKY_TEXT,
        target = "Enviar",
        expiresAt = "2026-12-16T22:30:00Z",
        createdAt = "2026-12-16T22:20:00Z",
    )

    private fun viewModel(
        refreshPending: suspend () -> Boolean = {
            val pending = fake.getPhoneApprovals().approvals
                .filter { it.state == PhoneApprovalState.PENDING }
            store.replace(pending)
            true
        },
    ): AlertsViewModel {
        val decider = PhoneApprovalDecider(
            FakeClientSource(fake),
            FileApprovalLedger(file = null, clock = { now }),
            clock = { now },
        )
        return AlertsViewModel(
            clientSource = FakeClientSource(fake),
            settingsRepository = InMemorySettingsRepository(),
            approvalsStore = store,
            approvalDecision = {
                ApprovalDecisionFlow(decider, store) { cancelled += it }
            },
            refreshPendingApprovals = refreshPending,
        )
    }

    @Test
    fun `the store pending list is exposed to the screen`() = runTest {
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        assertEquals(listOf("apr-123456789abc"), viewModel.pendingApprovals.value.map { it.id })
    }

    @Test
    fun `opening Alertas refreshes the pending list immediately`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val viewModel = viewModel()

        viewModel.startPolling()
        runCurrent()
        viewModel.stopPolling()

        assertEquals(PhoneApprovalState.PENDING, fake.lastApprovalsState)
        assertEquals(listOf("apr-123456789abc"), viewModel.pendingApprovals.value.map { it.id })
    }

    @Test
    fun `a failed refresh keeps the last list silently`() = runTest {
        store.replace(listOf(approval()))
        fake.failApprovalsWith = IOException("pc offline")
        val viewModel = viewModel()

        viewModel.refreshApprovals()
        runCurrent()

        assertEquals(listOf("apr-123456789abc"), viewModel.pendingApprovals.value.map { it.id })
        assertNull(viewModel.approvalMessage.value)
    }

    @Test
    fun `approving removes the row, cancels its notification and reports it`() = runTest {
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.approveApproval(approval())
        runCurrent()

        assertEquals(listOf("apr-123456789abc" to true), fake.approvalDecisions)
        assertTrue(viewModel.pendingApprovals.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
        assertEquals(ApprovalCardText.MESSAGE_APPROVED, viewModel.approvalMessage.value)
    }

    @Test
    fun `rejecting removes the row and cancels its notification`() = runTest {
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.rejectApproval(approval())
        runCurrent()

        assertEquals(listOf("apr-123456789abc" to false), fake.approvalDecisions)
        assertTrue(viewModel.pendingApprovals.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
        assertEquals(ApprovalCardText.MESSAGE_REJECTED, viewModel.approvalMessage.value)
    }

    @Test
    fun `a conflict removes the row and explains it was decided or expired`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("ALREADY_DECIDED")
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.approveApproval(approval())
        runCurrent()

        assertTrue(viewModel.pendingApprovals.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
        assertEquals(ApprovalCardText.MESSAGE_NO_LONGER_VALID, viewModel.approvalMessage.value)
    }

    @Test
    fun `a not found removes the row too`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.NotFound
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.rejectApproval(approval())
        runCurrent()

        assertTrue(viewModel.pendingApprovals.value.isEmpty())
        assertEquals(ApprovalCardText.MESSAGE_NO_LONGER_VALID, viewModel.approvalMessage.value)
    }

    @Test
    fun `a transport failure keeps the row and cancels nothing`() = runTest {
        fake.failDecisionWith = IOException("pc offline")
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.approveApproval(approval())
        runCurrent()

        assertEquals(listOf("apr-123456789abc"), viewModel.pendingApprovals.value.map { it.id })
        assertTrue(cancelled.isEmpty())
        assertEquals(ApprovalCardText.MESSAGE_DECISION_FAILED, viewModel.approvalMessage.value)
    }

    @Test
    fun `a second tap while a decision is in flight is ignored`() = runTest {
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.approveApproval(approval())
        viewModel.approveApproval(approval())
        runCurrent()

        assertEquals(1, fake.approvalDecisions.size)
    }

    @Test
    fun `the deciding id is exposed while the decision is in flight`() = runTest {
        val viewModel = viewModel()
        store.replace(listOf(approval()))

        viewModel.approveApproval(approval())

        assertEquals(setOf("apr-123456789abc"), viewModel.decidingApprovals.value)
        runCurrent()
        assertTrue(viewModel.decidingApprovals.value.isEmpty())
    }

    @Test
    fun `clearApprovalMessage removes the message`() = runTest {
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("EXPIRED")
        val viewModel = viewModel()
        store.replace(listOf(approval()))
        viewModel.approveApproval(approval())
        runCurrent()

        viewModel.clearApprovalMessage()

        assertNull(viewModel.approvalMessage.value)
    }
}
