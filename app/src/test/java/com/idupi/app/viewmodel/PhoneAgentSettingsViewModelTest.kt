package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.phone.PhoneStatus
import com.idupi.app.data.phone.PhoneStatusReporter
import com.idupi.app.data.phone.approval.ApprovalDecisionFlow
import com.idupi.app.data.phone.approval.FileApprovalLedger
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovalDecider
import com.idupi.app.data.phone.control.PhoneCommandLogEntry
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.model.PhoneApprovalsResponse
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Ajustes > Asistente del teléfono (phone-agent T2): persisted switch, manual
 * send feedback and the duplicate-tap guard. The manual action goes through a
 * real [PhoneStatusReporter] over [FakeIduPiClient], so the test also pins that
 * "Enviar ahora" actually posts instead of only flipping a flag.
 */
class PhoneAgentSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var repository: InMemorySettingsRepository

    private val snapshot = PhoneStatus(
        collectedAt = "2026-11-24T12:00:00Z",
        appVersion = "test",
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        repository = InMemorySettingsRepository()
    }

    private fun viewModel(
        canReport: () -> Boolean = { true },
        enabled: suspend () -> Boolean = { true },
    ): PhoneAgentSettingsViewModel {
        val reporter = PhoneStatusReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { snapshot },
            isEnabled = enabled,
            canReport = canReport,
        )
        return PhoneAgentSettingsViewModel(repository) { reporter.reportNow() }
    }

    @Test
    fun `status reporting is on by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.phoneStatusEnabled.value)
        assertTrue(repository.phoneStatusEnabled.first())
    }

    @Test
    fun `turning the switch off persists`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setPhoneStatusEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.phoneStatusEnabled.value)
        assertFalse(repository.phoneStatusEnabled.first())
    }

    @Test
    fun `the view model follows an external setting change`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.phoneStatusEnabled.value)

        repository.setPhoneStatusEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.phoneStatusEnabled.value)
    }

    @Test
    fun `sendNow posts a snapshot and reports success`() = runTest {
        val viewModel = viewModel()

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, fake.postedPhoneStatuses.size)
        assertEquals(snapshot, fake.postedPhoneStatuses.single())
        assertTrue(viewModel.reportState.value.success == true)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_SENT, viewModel.reportState.value.message)
        assertFalse(viewModel.reportState.value.isSending)
    }

    @Test
    fun `sendNow reports failure when no profile is saved and posts nothing`() = runTest {
        val viewModel = viewModel(canReport = { false })

        viewModel.sendNow()
        advanceUntilIdle()

        assertTrue(fake.postedPhoneStatuses.isEmpty())
        assertEquals(false, viewModel.reportState.value.success)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_FAILED, viewModel.reportState.value.message)
        assertFalse(viewModel.reportState.value.isSending)
    }

    @Test
    fun `sendNow reports a client failure`() = runTest {
        fake.failWith = RuntimeException("pc off")
        val viewModel = viewModel()

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(false, viewModel.reportState.value.success)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_FAILED, viewModel.reportState.value.message)
    }

    @Test
    fun `sendNow ignores a second tap while one is in flight`() = runTest {
        var calls = 0
        val viewModel = PhoneAgentSettingsViewModel(repository) {
            calls++
            delay(1_000)
            true
        }

        viewModel.sendNow()
        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue(viewModel.reportState.value.success == true)
    }

    @Test
    fun `clearFeedback removes the previous message`() = runTest {
        val viewModel = viewModel()
        viewModel.sendNow()
        advanceUntilIdle()

        viewModel.clearFeedback()

        assertNull(viewModel.reportState.value.message)
        assertNull(viewModel.reportState.value.success)
        assertFalse(viewModel.reportState.value.isSending)
    }

    @Test
    fun `control from the PC is off by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.phoneControlEnabled.value)
        assertFalse(repository.phoneControlEnabled.first())
    }

    @Test
    fun `turning control on persists without touching status reporting`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setPhoneControlEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.phoneControlEnabled.value)
        assertTrue(repository.phoneControlEnabled.first())
        assertTrue(viewModel.phoneStatusEnabled.value)
    }

    @Test
    fun `the view model follows an external control change`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.setPhoneControlEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.phoneControlEnabled.value)
    }

    @Test
    fun `the bubble is off by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.phoneBubbleEnabled.value)
        assertFalse(repository.phoneBubbleEnabled.first())
    }

    @Test
    fun `turning the bubble on persists without touching phone control`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setPhoneBubbleEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.phoneBubbleEnabled.value)
        assertTrue(repository.phoneBubbleEnabled.first())
        assertFalse(viewModel.phoneControlEnabled.value)
    }

    @Test
    fun `recent commands are exposed to the UI`() = runTest {
        val log = MutableStateFlow<List<PhoneCommandLogEntry>>(emptyList())
        val viewModel = PhoneAgentSettingsViewModel(repository, commandLog = log) { true }
        val entry = PhoneCommandLogEntry(
            atMillis = 1L,
            type = "screen",
            target = "pantalla",
            ok = true,
        )

        log.value = listOf(entry)

        assertEquals(listOf(entry), viewModel.recentCommands.value)
    }

    // --- Pending approvals (phone-approvals T2/T4) ------------------------

    private val now = 1_766_000_000_000L

    private fun approval(id: String = "apr-123456789abc") = PhoneApproval(
        id = id,
        state = PhoneApprovalState.PENDING,
        command = PhoneApprovalCommand(type = "tap"),
        reason = "risky_text",
        target = "Enviar",
        expiresAt = "2026-12-16T22:30:00Z",
    )

    private fun approvalViewModel(
        ledger: FileApprovalLedger,
        store: PendingApprovalsStore = PendingApprovalsStore(),
        cancelled: MutableList<String> = mutableListOf(),
    ): PhoneAgentSettingsViewModel {
        val decider = PhoneApprovalDecider(FakeClientSource(fake), ledger, clock = { now })
        return PhoneAgentSettingsViewModel(
            settingsRepository = repository,
            pendingStore = store,
            approvalDecision = {
                ApprovalDecisionFlow(decider, store) { cancelled += it }
            },
            refreshPendingApprovals = {
                store.replace(
                    fake.getPhoneApprovals().approvals.filter {
                        it.state == PhoneApprovalState.PENDING
                    }
                )
                true
            },
            reportNow = { true },
        )
    }

    private fun newLedger() = FileApprovalLedger(file = null, clock = { now })

    private fun TestScope.loadOnce(viewModel: PhoneAgentSettingsViewModel) {
        viewModel.startApprovalPolling()
        runCurrent()
    }

    @Test
    fun `the view model exposes the shared pending store`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val store = PendingApprovalsStore()
        val viewModel = approvalViewModel(newLedger(), store)

        loadOnce(viewModel)

        assertEquals(listOf("apr-123456789abc"), viewModel.approvals.value.pending.map { it.id })
        assertEquals(PhoneApprovalState.PENDING, fake.lastApprovalsState)
    }

    @Test
    fun `approve posts the decision and removes the row`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val ledger = newLedger()
        val store = PendingApprovalsStore()
        val viewModel = approvalViewModel(ledger, store)
        loadOnce(viewModel)

        viewModel.approveApproval(approval())
        advanceUntilIdle()

        assertEquals(listOf("apr-123456789abc" to true), fake.approvalDecisions)
        assertTrue(viewModel.approvals.value.pending.isEmpty())
        assertNotNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `reject posts the decision and removes the row without a grant`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val ledger = newLedger()
        val viewModel = approvalViewModel(ledger)
        loadOnce(viewModel)

        viewModel.rejectApproval(approval())
        advanceUntilIdle()

        assertEquals(listOf("apr-123456789abc" to false), fake.approvalDecisions)
        assertTrue(viewModel.approvals.value.pending.isEmpty())
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `a decision here removes the row from the shared store too`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val store = PendingApprovalsStore()
        val cancelled = mutableListOf<String>()
        val viewModel = approvalViewModel(newLedger(), store, cancelled)
        loadOnce(viewModel)

        viewModel.approveApproval(approval())
        advanceUntilIdle()

        assertTrue(store.pending.value.isEmpty())
        assertEquals(listOf("apr-123456789abc"), cancelled)
    }

    @Test
    fun `a 409 removes the row and explains it is no longer valid`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("EXPIRED")
        val viewModel = approvalViewModel(newLedger())
        loadOnce(viewModel)

        viewModel.approveApproval(approval())
        advanceUntilIdle()

        assertTrue(viewModel.approvals.value.pending.isEmpty())
        assertEquals(
            PhoneAgentSettingsViewModel.MESSAGE_NO_LONGER_VALID,
            viewModel.approvals.value.message,
        )
    }

    @Test
    fun `a 404 also removes the row`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        fake.decisionToReturn = PhoneApprovalDecisionResult.NotFound
        val viewModel = approvalViewModel(newLedger())
        loadOnce(viewModel)

        viewModel.rejectApproval(approval())
        advanceUntilIdle()

        assertTrue(viewModel.approvals.value.pending.isEmpty())
        assertEquals(
            PhoneAgentSettingsViewModel.MESSAGE_NO_LONGER_VALID,
            viewModel.approvals.value.message,
        )
    }

    @Test
    fun `a transport failure keeps the row and shows a message`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        fake.failDecisionWith = IOException("pc offline")
        val viewModel = approvalViewModel(newLedger())
        loadOnce(viewModel)

        viewModel.approveApproval(approval())
        advanceUntilIdle()

        assertEquals(1, viewModel.approvals.value.pending.size)
        assertEquals(
            PhoneAgentSettingsViewModel.MESSAGE_DECISION_FAILED,
            viewModel.approvals.value.message,
        )
    }

    @Test
    fun `a second tap while a decision is in flight is ignored`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        val viewModel = approvalViewModel(newLedger())
        loadOnce(viewModel)

        viewModel.approveApproval(approval())
        viewModel.approveApproval(approval())
        advanceUntilIdle()

        assertEquals(1, fake.approvalDecisions.size)
    }

    @Test
    fun `clearApprovalsMessage removes the message`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval()))
        fake.decisionToReturn = PhoneApprovalDecisionResult.Conflict("ALREADY_DECIDED")
        val viewModel = approvalViewModel(newLedger())
        loadOnce(viewModel)
        viewModel.approveApproval(approval())
        advanceUntilIdle()

        viewModel.clearApprovalsMessage()

        assertNull(viewModel.approvals.value.message)
    }
}
