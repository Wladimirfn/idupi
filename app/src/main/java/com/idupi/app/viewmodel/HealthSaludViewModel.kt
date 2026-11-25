package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.health.HealthReporting
import com.idupi.app.data.health.HealthSalud
import com.idupi.app.data.health.HealthSaludView
import com.idupi.app.data.health.runHealthRefresh
import com.idupi.app.domain.model.HealthDigest
import com.idupi.app.domain.model.HealthRefreshJob
import com.idupi.app.domain.model.HealthSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** State of the Salud screen (phone-health T3). */
data class HealthSaludState(
    val isLoading: Boolean = true,
    val view: HealthSaludView = HealthSaludView(),
    val digest: HealthDigest = HealthDigest(),
    /** "Actualizar ahora" is running: the PC reads Huawei Health over ADB. */
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    /** The digest card is asking the agent or polling it. */
    val isDigestBusy: Boolean = false,
    val digestMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Salud screen (phone-health T3).
 *
 * One load merges the seven-day summary and the cached digest. "Actualizar
 * ahora" asks the PC for a hidden-display read, polls the job, triggers the
 * phone-side Health Connect/wellbeing report, then reloads both. Every seam is
 * injected so the rules run on the plain JVM without Android or Ktor.
 */
class HealthSaludViewModel(
    private val loadSummary: suspend (Int) -> HealthSummary = {
        IduPiClientProvider.client.getHealthSummary(it)
    },
    private val loadDigest: suspend (String?) -> HealthDigest = {
        IduPiClientProvider.client.getHealthDigest(it)
    },
    private val requestDigest: suspend (Boolean) -> HealthDigest = {
        IduPiClientProvider.client.requestHealthDigest(it)
    },
    private val startRefresh: suspend () -> HealthRefreshJob = {
        IduPiClientProvider.client.refreshHealth()
    },
    private val pollRefresh: suspend (String) -> HealthRefreshJob = {
        IduPiClientProvider.client.getHealthRefresh(it)
    },
    private val reportPhoneSide: suspend () -> Boolean = { HealthReporting.reportNow() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pollDelayMillis: Long = 1_000,
    private val digestPollDelayMillis: Long = 2_000,
    private val maxPollAttempts: Int = 60,
    private val maxDigestPollAttempts: Int = 120,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthSaludState())
    val state: StateFlow<HealthSaludState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun todayKey(): String =
        Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate().toString()

    /** Reloads the summary and the cached digest; never runs the agent. */
    fun refresh() {
        viewModelScope.launch { loadAll() }
    }

    private fun label(): String =
        HealthSalud.dateLabel(Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate())

    private fun mapSummary(summary: HealthSummary): HealthSaludView =
        HealthSalud.map(summary, todayKey(), label())

    private suspend fun loadAll() {
        val firstLoad = _state.value.view.cards.isEmpty() && _state.value.view.trends.isEmpty()
        if (firstLoad) _state.update { it.copy(isLoading = true) }

        var summary: HealthSummary? = null
        try {
            summary = loadSummary(HealthSalud.TREND_DAYS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health summary failed: ${e.javaClass.simpleName}")
        }

        var digest: HealthDigest? = null
        try {
            // No date: the PC anchors the digest to its owner-local today (the
            // phone offset it stored with the samples), so a device zone that
            // differs at the midnight edge cannot read the wrong day.
            digest = loadDigest(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health digest failed: ${e.javaClass.simpleName}")
        }

        _state.update { current ->
            current.copy(
                isLoading = false,
                view = summary?.let(::mapSummary) ?: current.view,
                digest = digest ?: current.digest,
                errorMessage = if (summary == null && digest == null) MESSAGE_LOAD_FAILED else null,
            )
        }
    }

    /**
     * "Actualizar ahora": PC hidden read (Huawei Health), then the phone-side
     * Health Connect/wellbeing report, then a silent reload. The summary is
     * reloaded even when the PC read failed, because the phone report may still
     * have added data.
     */
    fun refreshNow() {
        if (_state.value.isRefreshing) return
        _state.update {
            it.copy(isRefreshing = true, refreshMessage = MESSAGE_READING, errorMessage = null)
        }
        viewModelScope.launch {
            val outcome = runHealthRefresh(
                start = startRefresh,
                poll = pollRefresh,
                pollDelayMillis = pollDelayMillis,
                maxPollAttempts = maxPollAttempts,
            )
            try {
                reportPhoneSide()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "phone-side health report failed: ${e.javaClass.simpleName}")
            }

            var summary: HealthSummary? = null
            try {
                summary = loadSummary(HealthSalud.TREND_DAYS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "health summary reload failed: ${e.javaClass.simpleName}")
            }
            var digest: HealthDigest? = null
            try {
                digest = loadDigest(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "health digest reload failed: ${e.javaClass.simpleName}")
            }

            val error = when {
                outcome.failed -> MESSAGE_REFRESH_FAILED
                outcome.timedOut -> MESSAGE_REFRESH_TIMEOUT
                outcome.job?.status == "failed" -> MESSAGE_REFRESH_FAILED
                else -> null
            }
            _state.update { current ->
                current.copy(
                    isRefreshing = false,
                    refreshMessage = null,
                    view = summary?.let(::mapSummary) ?: current.view,
                    digest = digest ?: current.digest,
                    errorMessage = error,
                )
            }
        }
    }

    /**
     * "Generar resumen" / "Actualizar resumen": asks the agent and polls until
     * it is ready or failed. A forced refresh is intentional (the owner tapped).
     */
    fun generateDigest() {
        if (_state.value.isDigestBusy) return
        _state.update { it.copy(isDigestBusy = true, digestMessage = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                var digest = requestDigest(true)
                var attempts = 0
                while (digest.isAnalyzing && attempts < maxDigestPollAttempts) {
                    delay(digestPollDelayMillis)
                    digest = loadDigest(null)
                    attempts += 1
                }
                _state.update { it.copy(digest = digest, isDigestBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "health digest request failed: ${e.javaClass.simpleName}")
                _state.update {
                    it.copy(isDigestBusy = false, digestMessage = MESSAGE_DIGEST_FAILED)
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun clearDigestMessage() {
        _state.update { it.copy(digestMessage = null) }
    }

    companion object {
        private const val TAG = "HealthSaludVM"

        const val MESSAGE_READING = "Leyendo Huawei Health desde el PC…"
        const val MESSAGE_LOAD_FAILED =
            "No se pudieron cargar los datos de salud. Revisa la conexión con la PC."
        const val MESSAGE_REFRESH_FAILED =
            "No se pudo leer Huawei Health. Revisa ADB y la conexión con la PC."
        const val MESSAGE_REFRESH_TIMEOUT =
            "La lectura tardó demasiado. Intenta de nuevo en un momento."
        const val MESSAGE_DIGEST_FAILED =
            "No se pudo generar el resumen. Intenta de nuevo."
    }
}
