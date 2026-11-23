package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.SystemMetrics
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live PC metrics for the Dashboard.
 *
 * Polling is owned by the screen lifecycle, not by [init]: [startPolling] is
 * idempotent and [stopPolling] cancels the loop, so the phone stops asking for
 * samples the moment the screen leaves composition or the app is stopped.
 *
 * Transient failures keep the last good sample on screen (a dropped Wi-Fi poll
 * must not blank the dashboard); [UiState.errorMessage] is only set when
 * nothing was ever loaded, which is the "no data at all" state.
 */
class DashboardViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ViewModel() {

    data class UiState(
        val metrics: SystemMetrics? = null,
        val isLoading: Boolean = true,
        /** Set only when [metrics] is null: there is nothing to show. */
        val errorMessage: String? = null,
        /** True when the newest poll failed but a previous sample is on screen. */
        val refreshFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Starts the 2 s poll loop; a second call while it runs is a no-op. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                fetchMetrics()
                delay(pollIntervalMs)
            }
        }
    }

    /** Stops the loop when the screen leaves composition or the app backgrounds. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** The top-bar refresh action: one immediate sample without touching the loop. */
    fun refreshNow() {
        viewModelScope.launch { fetchMetrics() }
    }

    private suspend fun fetchMetrics() {
        try {
            val metrics = clientSource.client.getSystemMetrics()
            _state.value = UiState(metrics = metrics, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh system metrics", e)
            val current = _state.value
            _state.value = if (current.metrics != null) {
                current.copy(isLoading = false, refreshFailed = true)
            } else {
                UiState(
                    metrics = null,
                    isLoading = false,
                    errorMessage = "No se pudieron cargar las métricas: ${e.localizedMessage}",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val TAG = "DashboardViewModel"
    }
}
