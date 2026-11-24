package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.remote.IduPiHttpException
import com.idupi.app.domain.model.PtyInfo
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Base64

/**
 * Owns one interactive PTY session end to end: creates or attaches, keeps the
 * authenticated byte stream open, coalesces keystrokes into ordered POSTs,
 * debounces resizes, and reconnects through the server's replay ring when the
 * stream ends under it.
 *
 * Transport rules that matter:
 *  - The stream is one-way raw bytes, so the ViewModel never parses them; it
 *    base64-encodes chunks and the screen hands them to xterm.js.
 *  - The bearer token lives only in [RealIduPiClient]; JS never sees it.
 *  - Input is a single sequential sender, so characters can never overtake
 *    each other on the wire.
 *
 * Cross-thread surface: [onInput], [onResize] and [onViewportReady] are called
 * from the WebView's JavaScript bridge thread. They only touch channels and
 * fields; state mutation happens on the Main dispatcher.
 */
class PtyTerminalViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _uiState = MutableStateFlow(PtyTerminalUiState())
    val uiState: StateFlow<PtyTerminalUiState> = _uiState.asStateFlow()

    /**
     * Live context of the terminal this screen is attached to (project, git
     * branch, running agent), read from the same list the Terminales screen
     * uses. The screen refreshes it once on open and every few seconds while
     * visible; it is informational, so a failed read keeps the last value.
     */
    private val _headerInfo = MutableStateFlow<PtyInfo?>(null)
    val headerInfo: StateFlow<PtyInfo?> = _headerInfo.asStateFlow()

    /**
     * Base64-encoded raw VT chunks, in wire order. A channel (not a SharedFlow)
     * because the WebView may not exist yet when the first replay lands: the
     * bytes wait for the screen's drain instead of being dropped.
     */
    private val outputChannel = Channel<String>(Channel.UNLIMITED)
    val output: Channel<String> = outputChannel

    private val eventChannel = Channel<PtyTerminalEvent>(Channel.BUFFERED)
    val events: Channel<PtyTerminalEvent> = eventChannel

    /** Keystrokes from the WebView and from the Compose extra-keys bar. */
    private val inputChannel = Channel<String>(Channel.UNLIMITED)

    /** The terminal the navigation asked for; null means "create a new one". */
    private var requestedId: String? = null

    /** The server-side terminal this session talks to, once known. */
    private var activeId: String? = null

    private var started = false
    private var viewportCols = 80
    private var viewportRows = 24
    private var lastSentCols = -1
    private var lastSentRows = -1
    private var pendingCols = 80
    private var pendingRows = 24

    /**
     * Session generation tokens. [prepare] and [detach] bump [sessionGeneration];
     * a kill records the generation it belongs to in [killedGeneration]. A
     * create that was in flight can therefore tell "my terminal must be killed"
     * from "the user moved on", and a late kill can never close a newer screen.
     */
    private var sessionGeneration = 0
    private var killedGeneration = -1

    private var createJob: Job? = null
    private var streamJob: Job? = null
    private var senderJob: Job? = null
    private var resizeJob: Job? = null
    private var headerJob: Job? = null

    /**
     * Called when the screen is (re)entered for [terminalId]. Resets the
     * session only when the target actually changed, so a rotation keeps the
     * live stream until the recreated WebView reports its viewport.
     */
    fun prepare(terminalId: String?) {
        if (started && requestedId == terminalId) return
        cancelSession()
        sessionGeneration++
        requestedId = terminalId
        activeId = null
        started = false
        lastSentCols = -1
        lastSentRows = -1
        while (outputChannel.tryReceive().isSuccess) {
            // Drop the previous target's buffered bytes; a fresh WebView must
            // not render another terminal's scrollback.
        }
        while (eventChannel.tryReceive().isSuccess) {
            // A close event from a previous session must not navigate this one.
        }
        _headerInfo.value = null
        _uiState.value = PtyTerminalUiState(terminalId = terminalId)
    }

    /**
     * One list read to refresh the top-bar context (project, branch, agent).
     * The screen calls it on open and every few seconds while visible; it is
     * informational, so a failure logs and keeps the header the user already
     * sees instead of raising an error over a working terminal.
     */
    fun refreshHeader() {
        if (headerJob?.isActive == true) return
        val id = activeId ?: requestedId ?: return
        headerJob = viewModelScope.launch {
            try {
                val info = client.listPtys().firstOrNull { it.id == id }
                if (info != null) _headerInfo.value = info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Header refresh failed: ${e.localizedMessage}")
            }
        }
    }

    /**
     * xterm reports its fitted cols/rows from `AndroidBridge.onReady`. The first
     * call starts the session; later calls mean the WebView was recreated
     * (rotation), so the stream restarts to replay the server's ring buffer
     * into the blank terminal.
     */
    fun onViewportReady(cols: Int, rows: Int) {
        if (killedGeneration == sessionGeneration) return
        if (cols > 0) viewportCols = cols
        if (rows > 0) viewportRows = rows
        if (!started) {
            started = true
            _uiState.value = _uiState.value.copy(
                state = PtyConnectionState.CONNECTING,
                terminalId = requestedId
            )
            createJob = viewModelScope.launch { startSession() }
        } else if (activeId != null) {
            restartStream()
        }
    }

    /** One keystroke / paste from the WebView or a bar key. Order is preserved. */
    fun onInput(data: String) {
        if (data.isEmpty()) return
        inputChannel.trySend(data)
    }

    /** Debounced resize; the PTY is resized once the user stops dragging/typing. */
    fun onResize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        // Always remember the latest fitted size: a resize that lands while the
        // PTY is still being created is otherwise dropped (activeId is null),
        // and startSession() would re-apply the stale first-layout size.
        viewportCols = cols
        viewportRows = rows
        if (cols == pendingCols && rows == pendingRows && resizeJob != null) return
        pendingCols = cols
        pendingRows = rows
        resizeJob?.cancel()
        resizeJob = viewModelScope.launch {
            delay(RESIZE_DEBOUNCE_MS)
            val id = activeId ?: return@launch
            postResize(id, cols, rows)
        }
    }

    /**
     * Kills the whole PC-side process tree, then asks the screen to leave.
     * Kills are sent from their own job so leaving the screen (which calls
     * [detach]) can never abort the tree-kill request mid-flight.
     */
    fun kill() {
        val id = activeId
        val generation = sessionGeneration
        killedGeneration = generation
        cancelSession()
        started = false
        activeId = null
        viewModelScope.launch {
            if (id != null) {
                try {
                    withTimeout(KILL_TIMEOUT_MS) { client.killPty(id) }
                } catch (e: TimeoutCancellationException) {
                    // The screen must not stay hostage to a hung DELETE.
                    Log.w(TAG, "killPty timed out; closing anyway")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "killPty failed: ${e.localizedMessage}")
                }
            }
            if (sessionGeneration == generation) eventChannel.trySend(PtyTerminalEvent.Closed)
        }
    }

    /**
     * Leaves the screen WITHOUT killing anything: the terminal keeps running on
     * the PC, and re-entering reconnects into the replay. Called from the
     * composable's dispose.
     */
    fun detach() {
        cancelSession()
        sessionGeneration++
        started = false
        activeId = null
        lastSentCols = -1
        lastSentRows = -1
        while (outputChannel.tryReceive().isSuccess) {
            // No screen is draining; do not grow memory while away.
        }
        while (eventChannel.tryReceive().isSuccess) {
            // A kill that finished after the screen left must not close the
            // NEXT terminal the user opens.
        }
    }

    private suspend fun startSession() {
        val generation = sessionGeneration
        val requested = requestedId
        val id = activeId ?: if (requested != null) {
            requested
        } else {
            // NonCancellable: a kill (or leaving the screen) right after the
            // create started must not cancel the response before we learn the
            // new id -- that id is the only handle able to tree-kill it.
            val created = try {
                withContext(NonCancellable) {
                    client.createPty(cols = viewportCols, rows = viewportRows)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    state = PtyConnectionState.ERROR,
                    error = e.localizedMessage ?: "No se pudo crear la terminal en tu PC."
                )
                return
            }
            if (created.id.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    state = PtyConnectionState.ERROR,
                    error = created.error ?: "El servidor no devolvió una terminal válida."
                )
                return
            }
            if (killedGeneration == generation) {
                // The user killed before the create answered: the terminal now
                // exists on the PC, so kill this exact id. Only signal the
                // screen if it is still showing this same session.
                withContext(NonCancellable) {
                    try {
                        withTimeout(KILL_TIMEOUT_MS) { client.killPty(created.id) }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "cleanup killPty timed out")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "cleanup killPty failed: ${e.localizedMessage}")
                    }
                }
                if (sessionGeneration == generation) {
                    eventChannel.trySend(PtyTerminalEvent.Closed)
                }
                return
            }
            _uiState.value = _uiState.value.copy(pty = created, terminalId = created.id)
            created.id
        }
        activeId = id
        _uiState.value = _uiState.value.copy(terminalId = id)
        // Always align the PC-side PTY with this viewport: a created one may
        // have been sized before the final layout pass, an attached one keeps
        // whatever size a previous client used.
        postResize(id, viewportCols, viewportRows)
        startSender(id)
        streamJob = viewModelScope.launch { runStreamLoop(id) }
    }

    private fun restartStream() {
        val id = activeId ?: return
        streamJob?.cancel()
        _uiState.value = _uiState.value.copy(state = PtyConnectionState.CONNECTING, error = null)
        streamJob = viewModelScope.launch {
            postResize(id, viewportCols, viewportRows)
            runStreamLoop(id)
        }
    }

    private suspend fun runStreamLoop(id: String) {
        var attempt = 0
        var firstPass = true
        while (currentCoroutineContext().isActive) {
            if (!firstPass) {
                _uiState.value = _uiState.value.copy(state = PtyConnectionState.RECONNECTING)
            }
            firstPass = false
            var gotBytes = false
            var httpStatus: Int? = null
            var failure: String? = null
            try {
                client.ptyStream(id).collect { chunk ->
                    if (!gotBytes) {
                        gotBytes = true
                        // A healthy stream is the proof the backoff worked.
                        attempt = 0
                        _uiState.value = _uiState.value.copy(
                            state = PtyConnectionState.CONNECTED,
                            error = null
                        )
                    }
                    outputChannel.trySend(Base64.getEncoder().encodeToString(chunk))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IduPiHttpException) {
                httpStatus = e.statusCode
                failure = e.message
            } catch (e: Exception) {
                failure = e.localizedMessage ?: e.message
            }
            if (!currentCoroutineContext().isActive) return

            val decisivelyGone = httpStatus == 401 || httpStatus == 403 || httpStatus == 404
            val ptys = if (decisivelyGone) {
                null
            } else {
                try {
                    client.listPtys()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Liveness is unknown; the decision falls back to reconnect
                    // rather than inventing an exit code.
                    null
                }
            }
            when (val outcome = decideAfterStreamEnd(id, attempt, httpStatus, ptys, failure)) {
                is PtyStreamOutcome.Reconnect -> {
                    attempt++
                    _uiState.value = _uiState.value.copy(state = PtyConnectionState.RECONNECTING)
                    delay(outcome.delayMs)
                }
                is PtyStreamOutcome.Exited -> {
                    senderJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        state = PtyConnectionState.EXITED,
                        exitCode = outcome.exitCode,
                        error = null
                    )
                    return
                }
                is PtyStreamOutcome.Failed -> {
                    senderJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        state = PtyConnectionState.ERROR,
                        error = outcome.message
                    )
                    return
                }
            }
        }
    }

    /**
     * ONE coroutine drains every keystroke, so arrival order is wire order.
     * The 15 ms window trades a barely perceptible delay for one POST per key
     * burst instead of one per character.
     */
    private fun startSender(id: String) {
        senderJob?.cancel()
        senderJob = viewModelScope.launch {
            val queue = PtyInputQueue()
            while (isActive) {
                queue.offer(inputChannel.receive())
                delay(INPUT_COALESCE_MS)
                while (true) {
                    val next = inputChannel.tryReceive().getOrNull() ?: break
                    queue.offer(next)
                }
                val batch = queue.drain()
                if (batch.isEmpty()) continue
                // A single body is capped at 256 KiB server-side; a huge paste
                // is split on code-point boundaries instead of being rejected.
                for (part in splitPtyInput(batch)) {
                    try {
                        client.ptyInput(id, part)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Dropping the rest of the batch is honest: the terminal
                        // is gone or the link is down, and an infinite retry
                        // would wedge the sequential sender behind input nobody
                        // can receive.
                        Log.w(TAG, "ptyInput failed (${part.length} chars): ${e.localizedMessage}")
                        break
                    }
                }
            }
        }
    }

    private suspend fun postResize(id: String, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == lastSentCols && rows == lastSentRows) return
        try {
            client.ptyResize(id, cols, rows)
            lastSentCols = cols
            lastSentRows = rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient: the next resize (or reconnect) retries the same dims.
            Log.w(TAG, "ptyResize failed: ${e.localizedMessage}")
        }
    }

    private fun cancelSession() {
        createJob?.cancel()
        createJob = null
        streamJob?.cancel()
        streamJob = null
        senderJob?.cancel()
        senderJob = null
        resizeJob?.cancel()
        resizeJob = null
        headerJob?.cancel()
        headerJob = null
        while (inputChannel.tryReceive().isSuccess) {
            // Stale keystrokes must never reach the next terminal.
        }
    }

    override fun onCleared() {
        cancelSession()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PtyTerminalVM"

        /** Batching window: below human perception, above a key-repeat burst. */
        const val INPUT_COALESCE_MS = 15L

        /** Resize debounce while the IME animates or the user drags. */
        const val RESIZE_DEBOUNCE_MS = 150L

        /** A DELETE that hangs must not keep the terminal screen open. */
        const val KILL_TIMEOUT_MS = 10_000L
    }
}
