package com.idupi.app.data.phone.approval

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.data.phone.control.PollStep
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.model.PhoneApprovalsResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pending-approval poll (phone-approvals T2): a 10 s cadence, silent baseline,
 * fresh-id notification and notification clearing when an approval is gone.
 */
class PhoneApprovalPollerTest {

    private lateinit var fake: FakeIduPiClient
    private val fresh = mutableListOf<PhoneApproval>()
    private val resolved = mutableSetOf<String>()

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        fresh.clear()
        resolved.clear()
    }

    private fun approval(id: String, state: String = PhoneApprovalState.PENDING) =
        PhoneApproval(id = id, state = state)

    private fun poller(
        enabled: suspend () -> Boolean = { true },
        canPoll: () -> Boolean = { true },
    ) = PhoneApprovalPoller(
        clientSource = FakeClientSource(fake),
        isEnabled = enabled,
        canPoll = canPoll,
        onNewApprovals = { fresh += it },
        onResolved = { resolved += it },
    )

    @Test
    fun `stops when phone control is off`() = runTest {
        assertEquals(PollStep.Stop, poller(enabled = { false }).pollOnce())
        assertEquals(0, fake.approvalsCalls)
    }

    @Test
    fun `stops when no connection profile exists`() = runTest {
        assertEquals(PollStep.Stop, poller(canPoll = { false }).pollOnce())
        assertEquals(0, fake.approvalsCalls)
    }

    @Test
    fun `the first poll only records the baseline`() = runTest {
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval("apr-a")))

        assertEquals(PollStep.Continue, poller().pollOnce())

        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `a new pending id notifies once`() = runTest {
        val poller = poller()
        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval("apr-a")))
        poller.pollOnce()

        fake.approvalsToReturn =
            PhoneApprovalsResponse(listOf(approval("apr-a"), approval("apr-b")))
        poller.pollOnce()
        poller.pollOnce()

        assertEquals(listOf("apr-b"), fresh.map { it.id })
    }

    @Test
    fun `non-pending states are ignored`() = runTest {
        val poller = poller()
        fake.approvalsToReturn = PhoneApprovalsResponse(
            listOf(approval("apr-a"), approval("apr-b", state = PhoneApprovalState.APPROVED))
        )
        poller.pollOnce()

        fake.approvalsToReturn = PhoneApprovalsResponse(
            listOf(
                approval("apr-a"),
                approval("apr-b", state = PhoneApprovalState.APPROVED),
                approval("apr-c"),
            )
        )
        poller.pollOnce()

        assertEquals(listOf("apr-c"), fresh.map { it.id })
    }

    @Test
    fun `an approval that is no longer pending is reported resolved`() = runTest {
        val poller = poller()
        fake.approvalsToReturn =
            PhoneApprovalsResponse(listOf(approval("apr-a"), approval("apr-b")))
        poller.pollOnce()

        fake.approvalsToReturn = PhoneApprovalsResponse(listOf(approval("apr-a")))
        poller.pollOnce()

        assertEquals(setOf("apr-b"), resolved)
    }

    @Test
    fun `the poll asks for pending approvals only`() = runTest {
        poller().pollOnce()

        assertEquals(PhoneApprovalState.PENDING, fake.lastApprovalsState)
    }

    @Test
    fun `failures back off 5 10 20 30 and reset after a success`() = runTest {
        val poller = poller()
        val delays = mutableListOf<Long>()
        repeat(4) {
            fake.failApprovalsWith = IOException("offline")
            delays += (poller.pollOnce() as PollStep.Retry).delayMillis
        }

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), delays)
        assertEquals(PollStep.Continue, poller.pollOnce())

        fake.failApprovalsWith = IOException("offline")
        assertEquals(5_000L, (poller.pollOnce() as PollStep.Retry).delayMillis)
    }
}
