package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.control.PhoneCommandLog
import com.idupi.app.data.phone.control.PhoneCommandLogEntry
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Feedback for the manual "Enviar ahora" action. */
data class PhoneAgentReportState(
    val isSending: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
)

/**
 * Ajustes > Asistente del teléfono (phone-agent T2/T3): the persisted
 * "send status to the PC" switch, the "control from the PC" master switch, the
 * manual report feedback and the recent command log.
 *
 * The accessibility and READ_PHONE_STATE grants are platform state, not durable
 * settings, so the screen re-reads them on resume instead of this ViewModel.
 *
 * [reportNow] is injectable so JVM tests drive a real [com.idupi.app.data.phone.PhoneStatusReporter]
 * over [com.idupi.app.FakeIduPiClient] instead of the Android collectors;
 * [commandLog] is injectable for the same reason.
 */
class PhoneAgentSettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    commandLog: StateFlow<List<PhoneCommandLogEntry>> = PhoneCommandLog.entries,
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

    companion object {
        private const val TAG = "PhoneAgentVM"

        const val MESSAGE_SENT = "Estado enviado a la PC."
        const val MESSAGE_FAILED = "No se pudo enviar. Revisá la conexión con la PC."
    }
}
