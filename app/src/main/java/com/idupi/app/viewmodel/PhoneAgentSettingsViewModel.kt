package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.ApprovalCardText
import com.idupi.app.data.phone.approval.ApprovalDecisionFlow
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.phone.control.PhoneCommandLog
import com.idupi.app.data.phone.control.PhoneCommandLogEntry
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 * Pending approvals come from the process-wide [PendingApprovalsStore]
 * (phone-approvals T4), the same list Alertas reads, so a decision made in
 * either place removes the row from both. The poll that fills it lives in
 * [PhoneApprovals] and is not duplicated here.
 *
 * [reportNow] and [approvalDecision] are injectable so JVM tests drive a real
 * reporter/decider over [com.idupi.app.FakeIduPiClient] instead of the Android
 * collectors; [commandLog] is injectable for the same reason.
 */
class PhoneAgentSettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    commandLog: StateFlow<List<PhoneCommandLogEntry>> = PhoneCommandLog.entries,
    private val pendingStore: PendingApprovalsStore = PhoneApprovals.pendingStore,
    private val approvalDecision: () -> ApprovalDecisionFlow = {
        PhoneApprovals.decisionFlow(IduPiApp.instance)
    },
    private val refreshPendingApprovals: suspend () -> Boolean = {
        PhoneApprovals.refreshPending()
    },
    private val reportNow: suspend () -> Boolean = { PhoneStatusReporting.reportNow() },
) : ViewModel() {

    val phoneStatusEnabled: StateFlow<Boolean> = settingsRepository.phoneStatusEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val phoneControlEnabled: StateFlow<Boolean> = settingsRepository.phoneControlEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val phoneBubbleEnabled: StateFlow<Boolean> = settingsRepository.phoneBubbleEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Newest first, capped by [PhoneCommandLog]. Payload-free by construction. */
    val recentCommands: StateFlow<List<PhoneCommandLogEntry>> = commandLog

    private val _reportState = MutableStateFlow(PhoneAgentReportState())
    val reportState: StateFlow<PhoneAgentReportState> = _reportState.asStateFlow()

    private val _approvals = MutableStateFlow(PhoneApprovalsState())
    val approvals: StateFlow<PhoneApprovalsState> = _approvals.asStateFlow()

    init {
        // The store is process-wide (phone-approvals T4): mirroring it here
        // keeps the Ajustes card in step with a decision made in Alertas or in
        // the notification, with no second poll.
        viewModelScope.launch {
            pendingStore.pending.collect { pending ->
                _approvals.update { it.copy(pending = pending) }
            }
        }
    }

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

    fun setPhoneBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPhoneBubbleEnabled(enabled)
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

    // --- Pending approvals (phone-approvals T2/T4) ------------------------

    /**
     * Refreshes the shared pending list once when the section becomes visible.
     * The 10 s poll lives in [PhoneApprovals] while phone control is on, so this
     * only shortens the wait for a card the owner is already looking at.
     */
    fun startApprovalPolling() {
        viewModelScope.launch { refreshApprovalsNow() }
    }

    /**
     * Swallows a refused refresh: the PC may be off, and the card keeps the last
     * list instead of dropping it.
     */
    private suspend fun refreshApprovalsNow() {
        try {
            refreshPendingApprovals()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pending approval refresh failed: ${e.javaClass.simpleName}")
        }
    }

    fun approveApproval(approval: PhoneApproval) = decide(approval, approve = true)

    fun rejectApproval(approval: PhoneApproval) = decide(approval, approve = false)

    fun clearApprovalsMessage() {
        _approvals.update { it.copy(message = null) }
    }

    private fun decide(approval: PhoneApproval, approve: Boolean) {
        val id = approval.id
        if (id.isBlank() || id in _approvals.value.decidingIds) return
        _approvals.update { it.copy(decidingIds = it.decidingIds + id) }
        viewModelScope.launch {
            try {
                val result = approvalDecision().decide(approval, approve)
                val message = when (result) {
                    is PhoneApprovalDecisionResult.Decided ->
                        if (approve) ApprovalCardText.MESSAGE_APPROVED
                        else ApprovalCardText.MESSAGE_REJECTED
                    is PhoneApprovalDecisionResult.Conflict,
                    PhoneApprovalDecisionResult.NotFound,
                    -> ApprovalCardText.MESSAGE_NO_LONGER_VALID
                }
                _approvals.update { it.copy(message = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "approval decision failed: ${e.javaClass.simpleName}")
                _approvals.update { it.copy(message = ApprovalCardText.MESSAGE_DECISION_FAILED) }
            } finally {
                _approvals.update { it.copy(decidingIds = it.decidingIds - id) }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneAgentVM"

        const val MESSAGE_SENT = "Estado enviado a la PC."
        const val MESSAGE_FAILED = "No se pudo enviar. Revisa la conexión con la PC."

        /** Same cadence as the calendar proposal poll. */
        const val APPROVAL_POLL_MS = 10_000L

        const val MESSAGE_NO_LONGER_VALID = ApprovalCardText.MESSAGE_NO_LONGER_VALID
        const val MESSAGE_DECISION_FAILED = ApprovalCardText.MESSAGE_DECISION_FAILED
        const val MESSAGE_LOAD_FAILED = "No se pudieron cargar las aprobaciones."
        const val MESSAGE_APPROVED = ApprovalCardText.MESSAGE_APPROVED
        const val MESSAGE_REJECTED = ApprovalCardText.MESSAGE_REJECTED
    }
}
