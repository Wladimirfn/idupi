package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.PtyInfo
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live interactive terminals (ConPTY) running on the PC.
 *
 * The list is polled while the screen is visible: a shell can exit, or another
 * client can create one, without this app doing anything, so a one-shot read
 * would leave a stale list on screen.
 *
 * Creating a terminal happens here rather than on the terminal screen so the
 * list and the new shell agree on the same client call; the new id is published
 * through [createdTerminalIds] for the screen to open.
 */
class TerminalsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _ptys = MutableStateFlow<List<PtyInfo>>(emptyList())
    val ptys: StateFlow<List<PtyInfo>> = _ptys.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    /** True while the last list read failed; drives the screen's error state. */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val createdIds = Channel<String>(Channel.BUFFERED)
    val createdTerminalIds: Channel<String> = createdIds

    private var pollingJob: Job? = null

    init {
        refresh()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                loadPtys()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh() {
        viewModelScope.launch { loadPtys() }
    }

    /**
     * The poll runs every few seconds against a possibly-offline PC: report a
     * failure only on the transition into failure, so a dead link raises one
     * message instead of one snackbar per poll.
     */
    private suspend fun loadPtys() {
        try {
            _ptys.value = client.listPtys()
            if (_loadFailed.value) {
                _loadFailed.value = false
                _errorMessage.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list interactive PTYs", e)
            if (!_loadFailed.value) {
                _loadFailed.value = true
                _errorMessage.value = "No se pudieron cargar las terminales: ${e.localizedMessage}"
            }
        }
    }

    fun createTerminal() {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            try {
                val created = client.createPty(cols = DEFAULT_COLS, rows = DEFAULT_ROWS)
                if (created.id.isBlank()) {
                    _errorMessage.value =
                        created.error ?: "El servidor no devolvió una terminal válida."
                    return@launch
                }
                createdIds.trySend(created.id)
                loadPtys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create interactive PTY", e)
                _errorMessage.value = "No se pudo crear la terminal: ${e.localizedMessage}"
            } finally {
                _isCreating.value = false
            }
        }
    }

    private companion object {
        const val TAG = "TerminalsViewModel"

        /** Visible-screen refresh cadence; matches the console's log poll order of magnitude. */
        const val POLL_INTERVAL_MS = 3_000L

        /** Initial size; the terminal screen resizes to its fitted viewport on attach. */
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
