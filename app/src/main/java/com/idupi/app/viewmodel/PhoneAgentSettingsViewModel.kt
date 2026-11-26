package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.PhoneApprovalDecider
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.phone.control.PhoneCommandLog
import com.idupi.app.data.phone.control.PhoneCommandLogEntry
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Feedback for the manual "Enviar ahora" action. */
data class PhoneAgentReportState(
    val isSending: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
)

/** Pending phone approvals shown in Ajustes (phone-approvals T2). */
data class PhoneApprovalsState(
    val pending: List<PhoneApproval> = emptyList(),
    val decidingIds: Set<String> = emptySet(),
    val message: String? = null,
)

/**
 * Ajustes > Asistente del teléfono (phone-agent T2/T3 + phone-approvals T2): the
 * persisted "send status to the PC" switch, the "control from the PC" master
 * switch, the manual report feedback, the recent command log and the pending
 * approval list.
 *
 * The accessibility and READ_PHONE_STATE grants are platform state, not durable
 * settings, so the screen re-reads them on resume instead of this ViewModel.
 *
 * [reportNow], [pendingApprovals] and [approvalDecider] are injectable so JVM
 * tests drive a real reporter/decider over [com.idupi.app.FakeIduPiClient]
 * instead of the Android collectors; [commandLog] is injectable for the same
 * reason.
 */
class PhoneAgentSettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    commandLog: StateFlow<List<PhoneCommandLogEntry>> = PhoneCommandLog.entries,
    private val pendingApprovals: suspend () -> List<PhoneApproval> = { PhoneApprovals.pending() },
    private val approvalDecider: () -> PhoneApprovalDecider = {
        PhoneApprovals.decider(IduPiApp.instance)
    },
    private val reportNow: suspend () -> Boolean = { PhoneStatusReporting.reportNow() },
) : ViewModel() {

    val phoneStatusEnabled: StateFlow<Boolean> = settingsRepository.phoneStatusEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val phoneControlEnabled: StateFlow<Boolean> = settingsRepository.phoneControlEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Newest first, capped by [PhoneCommandLog]. Payload-free by construction. */
    val recentCommands: StateFlow<List<PhoneCommandLogEntry>> = commandLog

    private val _reportState = MutableStateFlow(PhoneAgentReportState())
    val reportState: StateFlow<PhoneAgentReportState> = _reportState.asStateFlow()

    private val _approvals = MutableStateFlow(PhoneApprovalsState())
    val approvals: StateFlow<PhoneApprovalsState> = _approvals.asStateFlow()

    private var approvalPollingJob: Job? = null

    /**
     * Approvals already decided here. A poll that still sees the server's
     * pending copy must not bring the row back while the decision settles.
     */
    private val handledApprovalIds = mutableSetOf<String>()

    fun setPhoneStatusEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPhoneStatusEnabled(enabled)
        }
    }

    fun setPhoneControlEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPhoneControlEnabled(enabled)
        }
    }

    fun sendNow() {
        // Set synchronously: two taps in the same frame must not start two posts,
        // and the UI disables the button on the very next recomposition.
        if (_reportState.value.isSending) return
        _reportState.value = PhoneAgentReportState(isSending = true)
        viewModelScope.launch {
            val sent = try {
                reportNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log or display captured status values: only the kind of
                // failure is safe to record.
                Log.w(TAG, "manual phone status report failed: ${e.javaClass.simpleName}")
                false
            }
            _reportState.value = PhoneAgentReportState(
                isSending = false,
                success = sent,
                message = if (sent) MESSAGE_SENT else MESSAGE_FAILED,
            )
        }
    }

    fun clearFeedback() {
        _reportState.value = PhoneAgentReportState()
    }

    // --- Pending approvals (phone-approvals T2) --------------------------

    /** Starts the 10 s approval poll; idempotent while a poll loop lives. */
    fun startApprovalPolling() {
        if (approvalPollingJob?.isActive == true) return
        approvalPollingJob = viewModelScope.launch {
            while (isActive) {
                loadApprovals(reportErrors = false)
                delay(APPROVAL_POLL_MS)
            }
        }
    }

    fun stopApprovalPolling() {
        approvalPollingJob?.cancel()
        approvalPollingJob = null
    }

    /** Manual refresh; failures are surfaced this time. */
    fun refreshApprovals() {
        viewModelScope.launch { loadApprovals(reportErrors = true) }
    }

    fun approveApproval(approval: PhoneApproval) = decide(approval, approve = true)

    fun rejectApproval(approval: PhoneApproval) = decide(approval, approve = false)

    fun clearApprovalsMessage() {
        _approvals.update { it.copy(message = null) }
    }

    private suspend fun loadApprovals(reportErrors: Boolean) {
        val pending = try {
            pendingApprovals()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The PC may simply be off; the poll stays silent, a manual refresh
            // surfaces the failure.
            Log.w(TAG, "approval load failed: ${e.javaClass.simpleName}")
            if (reportErrors) {
                _approvals.update { it.copy(message = MESSAGE_LOAD_FAILED) }
            }
            return
        }
        _approvals.update { state ->
            state.copy(pending = pending.filter { it.id !in handledApprovalIds })
        }
    }

    private fun decide(approval: PhoneApproval, approve: Boolean) {
        val id = approval.id
        if (id.isBlank() || id in _approvals.value.decidingIds) return
        _approvals.update { it.copy(decidingIds = it.decidingIds + id) }
        viewModelScope.launch {
            try {
                val decider = approvalDecider()
                val result = if (approve) decider.approve(approval) else decider.reject(approval)
                when (result) {
                    is PhoneApprovalDecisionResult.Decided -> {
                        handledApprovalIds += id
                        removeApproval(id)
                        _approvals.update {
                            it.copy(message = if (approve) MESSAGE_APPROVED else MESSAGE_REJECTED)
                        }
                    }
                    is PhoneApprovalDecisionResult.Conflict,
                    PhoneApprovalDecisionResult.NotFound,
                    -> {
                        handledApprovalIds += id
                        removeApproval(id)
                        _approvals.update { it.copy(message = MESSAGE_NO_LONGER_VALID) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "approval decision failed: ${e.javaClass.simpleName}")
                _approvals.update { it.copy(message = MESSAGE_DECISION_FAILED) }
            } finally {
                _approvals.update { it.copy(decidingIds = it.decidingIds - id) }
            }
        }
    }

    private fun removeApproval(id: String) {
        _approvals.update { state ->
            state.copy(pending = state.pending.filterNot { it.id == id })
        }
    }

    companion object {
        private const val TAG = "PhoneAgentVM"

        const val MESSAGE_SENT = "Estado enviado a la PC."
        const val MESSAGE_FAILED = "No se pudo enviar. Revisa la conexión con la PC."

        /** Same cadence as the calendar proposal poll. */
        const val APPROVAL_POLL_MS = 10_000L

        const val MESSAGE_NO_LONGER_VALID = "La aprobación ya no está vigente."
        const val MESSAGE_DECISION_FAILED =
            "No se pudo enviar la decisión. Revisa la conexión con la PC."
        const val MESSAGE_LOAD_FAILED = "No se pudieron cargar las aprobaciones."
        const val MESSAGE_APPROVED = "Aprobado."
        const val MESSAGE_REJECTED = "Rechazado."
    }
}
