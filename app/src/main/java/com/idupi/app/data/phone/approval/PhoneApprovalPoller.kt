package com.idupi.app.data.phone.approval

import android.util.Log
import com.idupi.app.data.phone.control.PollBackoff
import com.idupi.app.data.phone.control.PollStep
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException

/**
 * One iteration of the pending-approval poll (phone-approvals T2).
 *
 * The first successful poll only records the baseline: approvals that were
 * already pending when the app started must not notify again on every launch.
 * Afterwards a new pending id notifies once, and an id that leaves the pending
 * set cancels its notification.
 *
 * Every successful poll also publishes the full pending list through
 * [onPending], which feeds the shared [PendingApprovalsStore] the Alertas and
 * Ajustes cards read.
 *
 * Extraction from the Android host keeps the decision logic and the backoff
 * behavior unit-testable.
 */
class PhoneApprovalPoller(
    private val clientSource: IduPiClientSource,
    private val isEnabled: suspend () -> Boolean,
    private val canPoll: () -> Boolean,
    private val onNewApprovals: (List<PhoneApproval>) -> Unit = {},
    private val onResolved: (Set<String>) -> Unit = {},
    private val onPending: (List<PhoneApproval>) -> Unit = {},
    private val limit: Int = DEFAULT_LIMIT,
    private val backoff: PollBackoff = PollBackoff(),
    private val log: (String) -> Unit = { message -> Log.w(TAG, message) },
    private val onTransportFailure: (Throwable) -> Unit = {},
    private val onTransportSuccess: () -> Unit = {},
) {

    /** Pending ids seen on the last successful poll; null until the baseline. */
    private var knownIds: Set<String>? = null

    suspend fun pollOnce(): PollStep {
        if (!isEnabled() || !canPoll()) return PollStep.Stop

        val response = try {
            clientSource.client.getPhoneApprovals(PhoneApprovalState.PENDING, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("approval poll failed: ${error.javaClass.simpleName}")
            onTransportFailure(error)
            return PollStep.Retry(backoff.delayMillisForFailure())
        }

        backoff.reset()
        onTransportSuccess()
        val pending = response.approvals.filter { it.state == PhoneApprovalState.PENDING }
        val ids = pending.mapTo(mutableSetOf()) { it.id }
        val previous = knownIds
        knownIds = ids

        onPending(pending.sortedByDescending { it.createdAt })

        if (previous != null) {
            val gone = previous - ids
            if (gone.isNotEmpty()) onResolved(gone)
            val fresh = pending.filter { it.id !in previous }
            if (fresh.isNotEmpty()) onNewApprovals(fresh.sortedBy { it.createdAt })
        }
        return PollStep.Continue
    }

    companion object {
        private const val TAG = "PhoneApprovalPoller"
        const val DEFAULT_LIMIT = 50
    }
}
