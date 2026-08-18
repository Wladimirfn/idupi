package com.example.idupi.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.data.notification.SupervisorNotificationManager
import com.example.idupi.domain.model.AlertSeverity
import com.example.idupi.domain.model.SupervisorAlert
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlertsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _alerts = MutableStateFlow<List<SupervisorAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _selectedFilter = MutableStateFlow<AlertSeverity?>(null)
    val selectedFilter = _selectedFilter.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    val unreadCount: Int
        get() = _alerts.value.count { !it.isRead }

    init {
        refreshAlerts()
    }

    fun refreshAlerts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _alerts.value = client.getAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load alerts", e)
                _alerts.value = emptyList()
                _errorMessage.value = "No se pudieron cargar las alertas: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setFilter(severity: AlertSeverity?) {
        _selectedFilter.value = severity
    }

    fun markAsRead(alertId: String) {
        viewModelScope.launch {
            try {
                client.markAlertAsRead(alertId)
                refreshAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark alert $alertId as read", e)
                _errorMessage.value = "No se pudo marcar la alerta como leída: ${e.localizedMessage}"
            }
        }
    }

    fun approvePlan(planId: String) {
        viewModelScope.launch {
            try {
                client.approveMasterPlan(planId)
                refreshAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to approve master plan $planId", e)
                _errorMessage.value = "No se pudo aprobar el plan: ${e.localizedMessage}"
            }
        }
    }

    fun rejectPlan(planId: String, reason: String) {
        viewModelScope.launch {
            try {
                client.rejectMasterPlan(planId, reason)
                refreshAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reject master plan $planId", e)
                _errorMessage.value = "No se pudo rechazar el plan: ${e.localizedMessage}"
            }
        }
    }

    fun triggerTestNotification(context: Context) {
        val testAlert = SupervisorAlert(
            id = "test-${System.currentTimeMillis()}",
            title = "Alerta de Prueba del Supervisor",
            description = "El sistema de notificaciones de IDUPI funciona correctamente. Recibirás alertas del orquestador en tiempo real.",
            severity = AlertSeverity.WARNING,
            category = com.example.idupi.domain.model.AlertCategory.GOVERNANCE_GATE,
            timestamp = "Ahora",
            isRead = false,
            actionRequired = null
        )
        SupervisorNotificationManager(context).showNotification(testAlert)
    }

    companion object {
        private const val TAG = "AlertsViewModel"
    }
}
