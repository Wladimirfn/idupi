package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.phone.PhoneStatusReporting
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
 * Ajustes > Asistente del teléfono (phone-agent T2): the persisted
 * "send status to the PC" switch and the manual report feedback.
 *
 * T3 extends this same section with the control settings; the usage-access
 * check stays in the screen because it is a platform permission that must be
 * re-read on resume, not durable state.
 *
 * [reportNow] is injectable so JVM tests drive a real [com.idupi.app.data.phone.PhoneStatusReporter]
 * over [com.idupi.app.FakeIduPiClient] instead of the Android collectors.
 */
class PhoneAgentSettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    private val reportNow: suspend () -> Boolean = { PhoneStatusReporting.reportNow() },
) : ViewModel() {

    val phoneStatusEnabled: StateFlow<Boolean> = settingsRepository.phoneStatusEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _reportState = MutableStateFlow(PhoneAgentReportState())
    val reportState: StateFlow<PhoneAgentReportState> = _reportState.asStateFlow()

    fun setPhoneStatusEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPhoneStatusEnabled(enabled)
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
