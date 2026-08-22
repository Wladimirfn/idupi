package com.example.idupi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.SessionCounts
import com.example.idupi.domain.model.SessionItem
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _sessions = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _counts = MutableStateFlow<SessionCounts?>(null)
    val counts = _counts.asStateFlow()

    private val _countsPartial = MutableStateFlow(false)
    val countsPartial: StateFlow<Boolean> = _countsPartial.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedEngine = MutableStateFlow(ENGINE_ALL)
    val selectedEngine: StateFlow<String> = _selectedEngine.asStateFlow()

    /** True while [nextCursor] is non-null: another page can be requested. */
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private var nextCursor: String? = null
    private var firstPageRequestId = 0L
    private var pageGeneration = 0L
    private var loadingMore = false

    /**
     * Off by default: the list opens with the sessions the user actually
     * started. On OpenCode the unfiltered list is 108 rows of which 99 are
     * subagent runs, so showing everything is the exception, not the default.
     */
    private val _includeAll = MutableStateFlow(false)
    val includeAll = _includeAll.asStateFlow()

    fun setIncludeAll(value: Boolean) {
        if (_includeAll.value == value) return
        _includeAll.value = value
        refreshSessions()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        // Synchronously enter first-page loading and invalidate the cursor BEFORE
        // launching, so an immediate loadMore refuses instead of firing a
        // stale-cursor request against the previous page.
        _isLoading.value = true
        nextCursor = null
        _canLoadMore.value = false
        pageGeneration++
        loadingMore = false
        val requestId = ++firstPageRequestId
        // Capture the engine synchronously so a later selectEngine cannot change
        // which engine THIS refresh fetches, and re-check currency below so an
        // obsolete refresh issues no sessions request after being superseded.
        val engine = _selectedEngine.value
        viewModelScope.launch {
            loadCounts(requestId)
            if (requestId == firstPageRequestId) {
                fetchFirstPage(engine, requestId)
                finishLoadingIfCurrent(requestId)
            }
        }
    }

    fun selectEngine(engine: String) {
        _selectedEngine.value = engine
        nextCursor = null
        _canLoadMore.value = false
        _sessions.value = emptyList()
        pageGeneration++
        loadingMore = false
        val requestId = ++firstPageRequestId
        viewModelScope.launch {
            _isLoading.value = true
            fetchFirstPage(engine, requestId)
            finishLoadingIfCurrent(requestId)
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        val engine = _selectedEngine.value
        val generation = pageGeneration
        val loadRequestId = firstPageRequestId
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val page = client.getSessions(engine = engine, cursor = cursor, limit = PAGE_SIZE, includeAll = _includeAll.value)
                if (generation == pageGeneration && engine == _selectedEngine.value && cursor == nextCursor) {
                    nextCursor = page.nextCursor
                    _canLoadMore.value = page.nextCursor != null
                    _sessions.value = _sessions.value + page.sessions
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == pageGeneration && engine == _selectedEngine.value && cursor == nextCursor) {
                    Log.w(TAG, "Failed to load more sessions", e)
                    _errorMessage.value = "No se pudieron cargar más sesiones: ${e.localizedMessage}"
                }
            } finally {
                if (generation == pageGeneration) {
                    loadingMore = false
                    finishLoadingIfCurrent(loadRequestId)
                }
            }
        }
    }

    private fun finishLoadingIfCurrent(requestId: Long) {
        if (requestId == firstPageRequestId) {
            _isLoading.value = false
        }
    }

    private suspend fun loadCounts(requestId: Long) {
        try {
            val response = client.getSessionCounts(includeAll = _includeAll.value)
            // Guard the success commit with the request id: an older counts request
            // that completes after a newer refresh (which may have cleared counts on
            // failure) must NOT resurrect stale badges over the newer state.
            if (requestId == firstPageRequestId) {
                _counts.value = response.counts
                _countsPartial.value = response.partial
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestId == firstPageRequestId) {
                Log.w(TAG, "Failed to load session counts", e)
                // Honesty for the CURRENT request: drop any stale badges from a
                // previous engine/generation instead of showing them as fresh.
                _counts.value = null
                _countsPartial.value = true
                _errorMessage.value = "No se pudieron cargar los contadores: ${e.localizedMessage}"
            }
        }
    }

    private suspend fun fetchFirstPage(engine: String, requestId: Long) {
        try {
            val page = client.getSessions(engine = engine, cursor = null, limit = PAGE_SIZE, includeAll = _includeAll.value)
            if (requestId == firstPageRequestId) {
                nextCursor = page.nextCursor
                _canLoadMore.value = page.nextCursor != null
                _sessions.value = page.sessions
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestId == firstPageRequestId) {
                Log.w(TAG, "Failed to load sessions for engine $engine", e)
                _errorMessage.value = "No se pudieron cargar las sesiones: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Starts a fresh CLI session on the active engine, keeping the chosen model.
     * The server refuses with 409 while a turn is running -- killing a working
     * Pi child loses the answer in flight -- and that refusal is surfaced rather
     * than swallowed, so the user knows nothing changed.
     */
    fun startNewSession(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                client.startNewSession()
                refreshSessions()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start a new session", e)
                _errorMessage.value = "No se pudo abrir una sesión nueva: ${e.localizedMessage}"
            }
        }
    }

    fun toggleFavorite(sessionId: String) {
        val current = _sessions.value
        _sessions.value = current.map {
            if (it.id == sessionId) it.copy(isFavorite = !it.isFavorite) else it
        }
    }

    companion object {
        private const val TAG = "SessionsViewModel"
        private const val PAGE_SIZE = 30
        private const val ENGINE_ALL = "all"
    }
}