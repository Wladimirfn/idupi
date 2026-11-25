package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.calendar.CalendarPermissionState
import com.idupi.app.data.calendar.CalendarPermissions
import com.idupi.app.data.calendar.CalendarReportState
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Feedback for the manual "Enviar ahora" action in Ajustes. */
data class CalendarSettingsReportState(
    val isSending: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
)

/**
 * Ajustes > CALENDARIO (phone-calendar T2): the persisted "share my agenda"
 * switch, the runtime permission state, the last report and the manual send.
 *
 * Permissions are platform state, not a durable setting: the screen refreshes
 * them on every resume (and after the system dialog) instead of trusting the
 * first value.
 */
class CalendarSettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    reportStateFlow: StateFlow<CalendarReportState> = CalendarReporting.reportState,
    private val permissionProvider: () -> CalendarPermissionState = {
        CalendarPermissions.current(IduPiApp.instance)
    },
    private val reportNow: suspend () -> Boolean = { CalendarReporting.reportNow() },
) : ViewModel() {

    val shareEnabled: StateFlow<Boolean> = settingsRepository.calendarShareEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Last report observed from the process-wide reporter. */
    val reportState: StateFlow<CalendarReportState> = reportStateFlow

    private val _permission = MutableStateFlow(permissionProvider())
    val permission: StateFlow<CalendarPermissionState> = _permission.asStateFlow()

    private val _feedback = MutableStateFlow(CalendarSettingsReportState())
    val feedback: StateFlow<CalendarSettingsReportState> = _feedback.asStateFlow()

    fun refreshPermission() {
        _permission.value = permissionProvider()
    }

    fun updatePermission(permission: CalendarPermissionState) {
        _permission.value = permission
    }

    fun setShareEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCalendarShareEnabled(enabled)
        }
    }

    fun sendNow() {
        // Set synchronously so two taps in one frame cannot start two posts.
        if (_feedback.value.isSending) return
        _feedback.value = CalendarSettingsReportState(isSending = true)
        viewModelScope.launch {
            val sent = try {
                reportNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "manual calendar report failed: ${e.javaClass.simpleName}")
                false
            }
            _feedback.value = CalendarSettingsReportState(
                isSending = false,
                success = sent,
                message = if (sent) MESSAGE_SENT else MESSAGE_FAILED,
            )
        }
    }

    fun clearFeedback() {
        _feedback.value = CalendarSettingsReportState()
    }

    companion object {
        private const val TAG = "CalendarSettingsVM"

        const val MESSAGE_SENT = "Agenda enviada a la PC."
        const val MESSAGE_FAILED =
            "No se pudo enviar la agenda. Revisa la conexión y el permiso del calendario."
    }
}
