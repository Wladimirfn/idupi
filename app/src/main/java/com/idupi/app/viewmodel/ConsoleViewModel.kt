package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.KillProcessOutcome
import com.idupi.app.domain.model.OS_CATEGORY_AGENTS
import com.idupi.app.domain.model.OS_CATEGORY_APPS
import com.idupi.app.domain.model.OS_CATEGORY_BACKGROUND
import com.idupi.app.domain.model.OS_CATEGORY_IDUPI
import com.idupi.app.domain.model.OS_CATEGORY_WINDOWS
import com.idupi.app.domain.model.OsProcess
import com.idupi.app.domain.model.OsProcessSample
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Category chips of the Procesos screen, in display order. The ids match the
 * server's classification, so a filter never guesses a category on the client.
 */
enum class ProcessCategoryFilter(val categoryId: String?, val label: String) {
    ALL(null, "Todos"),
    APPS(OS_CATEGORY_APPS, "Apps"),
    BACKGROUND(OS_CATEGORY_BACKGROUND, "Segundo plano"),
    WINDOWS(OS_CATEGORY_WINDOWS, "Windows"),
    AGENTS(OS_CATEGORY_AGENTS, "Agentes"),
    IDUPI(OS_CATEGORY_IDUPI, "IDUPI"),
}

enum class ProcessSort(val label: String) {
    MEMORY("Memoria"),
    CPU("CPU"),
    NAME("Nombre"),
}

/**
 * Pure filter/sort used by [ConsoleViewModel.UiState]. Search matches the
 * process name and the window title, case-insensitively; the CPU sort keeps
 * processes without a delta (null) at the end instead of treating them as 0 %.
 */
internal fun filterProcesses(
    processes: List<OsProcess>,
    category: ProcessCategoryFilter,
    query: String,
    sort: ProcessSort
): List<OsProcess> {
    val needle = query.trim()
    val filtered = processes.filter { process ->
        val categoryMatches = category.categoryId == null || process.category == category.categoryId
        val searchMatches = needle.isEmpty() ||
            process.name.contains(needle, ignoreCase = true) ||
            process.title?.contains(needle, ignoreCase = true) == true
        categoryMatches && searchMatches
    }
    return when (sort) {
        ProcessSort.MEMORY -> filtered.sortedWith(
            compareByDescending<OsProcess> { it.memoryBytes }
                .thenBy { it.name.lowercase(Locale.US) }
                .thenBy { it.pid }
        )
        ProcessSort.CPU -> filtered.sortedWith(
            compareByDescending<OsProcess> { it.cpuPercent ?: -1.0 }
                .thenByDescending { it.memoryBytes }
                .thenBy { it.pid }
        )
        ProcessSort.NAME -> filtered.sortedWith(
            compareBy<OsProcess> { it.name.lowercase(Locale.US) }.thenBy { it.pid }
        )
    }
}

/**
 * "Procesos" (T10): every process on the PC, polled every 3 s while the screen
 * is visible.
 *
 * Polling is owned by the screen lifecycle, exactly like the Dashboard: a
 * backgrounded app does not keep asking the server to run PowerShell. A
 * transient failure keeps the last good list on screen; [UiState.errorMessage]
 * is only the "nothing was ever loaded" state (plus kill failures, which the
 * list does not hide).
 */
class ConsoleViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ViewModel() {

    data class UiState(
        val sample: OsProcessSample = OsProcessSample(),
        val isLoading: Boolean = true,
        /** Set when nothing loaded yet, or when a kill action failed. */
        val errorMessage: String? = null,
        /** True when the newest poll failed but a previous list is on screen. */
        val refreshFailed: Boolean = false,
        val category: ProcessCategoryFilter = ProcessCategoryFilter.ALL,
        val query: String = "",
        val sort: ProcessSort = ProcessSort.MEMORY,
        /** PID whose kill request is in flight; buttons disable while non-null. */
        val killingPid: Int? = null,
    ) {
        val processes: List<OsProcess> get() = sample.processes

        val filteredProcesses: List<OsProcess>
            get() = filterProcesses(processes, category, query, sort)

        fun countFor(filter: ProcessCategoryFilter): Int =
            if (filter.categoryId == null) processes.size
            else processes.count { it.category == filter.categoryId }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Error stream for the navigation shell's shared snackbar. Kept as its own
     * flow because the shell observes every screen the same way.
     */
    val errorMessage: StateFlow<String?> = _state
        .map { it.errorMessage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var pollJob: Job? = null

    /** Starts the 3 s poll loop; a second call while it runs is a no-op. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                fetchProcesses()
                delay(pollIntervalMs)
            }
        }
    }

    /** Stops the loop when the screen leaves composition or the app backgrounds. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Top-bar refresh: one immediate sample without touching the loop. */
    fun refreshNow() {
        viewModelScope.launch { fetchProcesses() }
    }

    fun setCategory(category: ProcessCategoryFilter) {
        _state.update { it.copy(category = category) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSort(sort: ProcessSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Ends [pid] on the PC. Success and "already gone" both remove the row;
     * a protected process keeps its row and surfaces the server's refusal.
     * A successful kill is not re-fetched immediately: the server caches a
     * process sample for 2 s, so an instant poll could resurrect the row.
     */
    fun killProcess(pid: Int) {
        if (_state.value.killingPid != null) return
        viewModelScope.launch {
            _state.update { it.copy(killingPid = pid) }
            try {
                when (clientSource.client.killOsProcess(pid)) {
                    KillProcessOutcome.KILLED, KillProcessOutcome.GONE ->
                        _state.update { current ->
                            current.copy(
                                killingPid = null,
                                sample = current.sample.copy(
                                    processes = current.sample.processes.filterNot { it.pid == pid }
                                )
                            )
                        }
                    KillProcessOutcome.PROTECTED ->
                        _state.update { it.copy(killingPid = null, errorMessage = "Proceso protegido") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to end process $pid", e)
                _state.update {
                    it.copy(
                        killingPid = null,
                        errorMessage = "No se pudo finalizar el proceso: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private suspend fun fetchProcesses() {
        try {
            val sample = clientSource.client.getOsProcesses()
            _state.update {
                it.copy(
                    sample = sample,
                    isLoading = false,
                    errorMessage = null,
                    refreshFailed = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh OS processes", e)
            val current = _state.value
            _state.value = if (current.processes.isNotEmpty()) {
                current.copy(isLoading = false, refreshFailed = true)
            } else {
                current.copy(
                    isLoading = false,
                    errorMessage = "No se pudieron cargar los procesos: ${e.localizedMessage}",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
        private const val TAG = "ConsoleViewModel"
    }
}
