package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthConnectPermissions
import com.idupi.app.data.health.HealthInstalledApp
import com.idupi.app.data.health.HealthReportState
import com.idupi.app.data.health.HealthReporting
import com.idupi.app.data.health.healthConnectAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Platform state of Ajustes > SALUD. */
data class HealthSettingsState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.UNAVAILABLE,
    val grantedPermissions: Set<String> = emptySet(),
    val requiredPermissions: Set<String> = emptySet(),
    val sources: List<HealthInstalledApp> = emptyList(),
    val isLoadingSources: Boolean = false,
) {
    /** Granted permissions that are actually part of the health read set. */
    val grantedCount: Int get() = grantedPermissions.count { it in requiredPermissions }
    val hasAllPermissions: Boolean
        get() = requiredPermissions.isNotEmpty() && grantedCount == requiredPermissions.size
}

/** Feedback for the manual "Enviar ahora" action in Ajustes. */
data class HealthSettingsFeedback(
    val isSending: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
)

/**
 * Ajustes > SALUD (phone-health T2); T3 extends the same screen.
 *
 * Health Connect status and permissions are platform state, not durable
 * settings: they are re-read on every resume and after the permission dialog.
 * Sources are detected from PackageManager plus Health Connect's data origins,
 * so a newly installed health app appears without restarting the app.
 */
class HealthSettingsViewModel(
    private val availabilityProvider: () -> HealthConnectAvailability = {
        healthConnectAvailability(IduPiApp.instance)
    },
    private val grantedProvider: suspend () -> Set<String> = {
        HealthConnectPermissions.granted(IduPiApp.instance)
    },
    private val sourcesProvider: suspend () -> List<HealthInstalledApp> = {
        HealthReporting.detectedSources()
    },
    reportStateFlow: StateFlow<HealthReportState> = HealthReporting.reportState,
    private val requiredPermissions: Set<String> = HealthConnectPermissions.READ_PERMISSIONS,
    private val reportNow: suspend () -> Boolean = { HealthReporting.reportNow() },
) : ViewModel() {

    val reportState: StateFlow<HealthReportState> = reportStateFlow

    private val _state = MutableStateFlow(
        HealthSettingsState(
            availability = availabilityProvider(),
            requiredPermissions = requiredPermissions,
        )
    )
    val state: StateFlow<HealthSettingsState> = _state.asStateFlow()

    private val _feedback = MutableStateFlow(HealthSettingsFeedback())
    val feedback: StateFlow<HealthSettingsFeedback> = _feedback.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads availability, granted permissions and detected sources. */
    fun refresh() {
        viewModelScope.launch {
            val availability = runCatching { availabilityProvider() }
                .getOrDefault(HealthConnectAvailability.UNAVAILABLE)
            val granted = if (availability == HealthConnectAvailability.AVAILABLE) {
                runCatching { grantedProvider() }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            _state.value = _state.value.copy(
                availability = availability,
                grantedPermissions = granted,
                isLoadingSources = true,
            )
            val sources = try {
                sourcesProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source detection failed: ${e.javaClass.simpleName}")
                _state.value.sources
            }
            _state.value = _state.value.copy(
                sources = sources,
                isLoadingSources = false,
            )
        }
    }

    /** The permission dialog answered; trust the platform's own grant list. */
    fun onPermissionResult(granted: Set<String>) {
        _state.value = _state.value.copy(grantedPermissions = granted)
        refresh()
    }

    fun sendNow() {
        // Set synchronously so two taps in one frame cannot start two posts.
        if (_feedback.value.isSending) return
        _feedback.value = HealthSettingsFeedback(isSending = true)
        viewModelScope.launch {
            val sent = try {
                reportNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "manual health report failed: ${e.javaClass.simpleName}")
                false
            }
            _feedback.value = HealthSettingsFeedback(
                isSending = false,
                success = sent,
                message = if (sent) MESSAGE_SENT else MESSAGE_FAILED,
            )
        }
    }

    fun clearFeedback() {
        _feedback.value = HealthSettingsFeedback()
    }

    companion object {
        private const val TAG = "HealthSettingsVM"

        const val MESSAGE_SENT = "Datos de salud enviados a la PC."
        const val MESSAGE_FAILED =
            "No se pudieron enviar los datos. Revisa la conexión con la PC."
    }
}
