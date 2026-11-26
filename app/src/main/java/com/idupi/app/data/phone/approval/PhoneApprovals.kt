package com.idupi.app.data.phone.approval

import android.content.Context
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.phone.control.PollStep
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide access to the local approval ledger, the shared decider and the
 * pending list (phone-approvals T2).
 *
 * The ledger is one file-backed instance, so the accessibility executor and the
 * notification receiver always see the same grants.
 */
object PhoneApprovals {

    @Volatile
    private var ledgerInstance: ApprovalLedger? = null

    fun ledger(context: Context): ApprovalLedger =
        ledgerInstance ?: synchronized(this) {
            ledgerInstance ?: FileApprovalLedger(
                File(
                    context.applicationContext.filesDir,
                    FileApprovalLedger.FILE_NAME,
                )
            ).also { ledgerInstance = it }
        }

    fun decider(context: Context): PhoneApprovalDecider =
        PhoneApprovalDecider(IduPiClientProvider, ledger(context))

    /** Pending approvals, newest first. Failures propagate to the caller. */
    suspend fun pending(limit: Int = PhoneApprovalPoller.DEFAULT_LIMIT): List<PhoneApproval> =
        IduPiClientProvider.client.getPhoneApprovals(PhoneApprovalState.PENDING, limit)
            .approvals
            .filter { it.state == PhoneApprovalState.PENDING }
}

/**
 * Hosts the pending-approval poll (phone-approvals T2) while "Control desde la
 * PC" is on. Started from the accessibility service, the only component that is
 * alive when the screen is off; idempotent and process-wide, like the other
 * reporters.
 */
object PhoneApprovalReporting {

    /** Poll cadence while control is enabled. */
    const val TICK_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val notifier = ApprovalNotifier(appContext)
        val poller = PhoneApprovalPoller(
            clientSource = IduPiClientProvider,
            isEnabled = { SettingsProvider.repository.phoneControlEnabled.first() },
            canPoll = { IduPiClientProvider.configureFromStorage(appContext) },
            onNewApprovals = { approvals -> approvals.forEach(notifier::show) },
            onResolved = { ids -> ids.forEach(notifier::cancel) },
        )
        job = scope.launch {
            while (isActive) {
                when (val step = poller.pollOnce()) {
                    PollStep.Stop -> break
                    PollStep.Continue -> delay(TICK_MS)
                    is PollStep.Retry -> delay(step.delayMillis)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
