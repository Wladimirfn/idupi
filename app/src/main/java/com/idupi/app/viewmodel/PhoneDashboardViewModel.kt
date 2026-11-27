package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.phone.AndroidPhoneDashboardSource
import com.idupi.app.data.phone.PhoneDashboardSnapshot
import com.idupi.app.data.phone.PhoneDashboardSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Dashboard's phone page (phone-dashboard T1/T2).
 *
 * It reads locally on the phone: the collector answers from the platform, so
 * this screen never asks the PC for phone data and the PC poll never runs while
 * this page is visible. The screen owns the lifecycle the same way it does for
 * the PC page: [startPolling] is idempotent and [stopPolling] cancels the loop.
 *
 * Every tick refreshes RAM, battery, network and today's usage; the expensive
 * per-app storage list is reused for about a minute by the collector, and
 * [refreshNow] (the top-bar action) forces it. A transient failure keeps the
 * last good snapshot on screen, exactly like the PC page.
 */
class PhoneDashboardViewModel(
    private val source: PhoneDashboardSource = AndroidPhoneDashboardSource,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ViewModel() {

    data class UiState(
        val snapshot: PhoneDashboardSnapshot? = null,
        val isLoading: Boolean = true,
        /** Set only when [snapshot] is null: there is nothing to show. */
        val errorMessage: String? = null,
        /** True when the newest read failed but a previous snapshot is shown. */
        val refreshFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Starts the 5 s loop; a second call while it runs is a no-op. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                fetch(forceAppStorage = false)
                delay(pollIntervalMs)
            }
        }
    }

    /** Stops the loop when the phone page is not the visible one. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** The top-bar refresh: one immediate read that also re-reads app storage. */
    fun refreshNow() {
        viewModelScope.launch { fetch(forceAppStorage = true) }
    }

    private suspend fun fetch(forceAppStorage: Boolean) {
        try {
            val snapshot = source.collect(forceAppStorage)
            _state.value = UiState(snapshot = snapshot, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh the phone snapshot", e)
            val current = _state.value
            _state.value = if (current.snapshot != null) {
                current.copy(isLoading = false, refreshFailed = true)
            } else {
                UiState(
                    snapshot = null,
                    isLoading = false,
                    errorMessage = "No se pudo leer el estado del teléfono: ${e.localizedMessage}",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        private const val TAG = "PhoneDashboardViewModel"
    }
}
