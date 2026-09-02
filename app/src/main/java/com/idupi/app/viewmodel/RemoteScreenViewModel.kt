package com.idupi.app.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.ScreenMonitor
import com.idupi.app.domain.model.frameRate
import com.idupi.app.domain.model.recentArrivals
import com.idupi.app.domain.model.KeyPress
import com.idupi.app.domain.model.ScreenInputEvent
import com.idupi.app.domain.model.SpecialKey
import com.idupi.app.domain.model.ScreenQualityChanged
import com.idupi.app.domain.model.ScreenStreamRequest
import com.idupi.app.domain.model.ScreenWireMessage
import com.idupi.app.domain.model.isTileFrame
import com.idupi.app.domain.model.tileRect
import com.idupi.app.domain.model.tileSlices
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Everything the remote screen UI shows, in one immutable snapshot. */
data class RemoteScreenUiState(
    val monitors: List<ScreenMonitor> = emptyList(),
    val selectedMonitorId: Int? = null,
    val streaming: Boolean = false,
    val currentFrame: ImageBitmap? = null,
    val frameMeta: com.idupi.app.domain.model.ScreenFrameMeta? = null,
    val lastRenderMs: Long = 0,
    val lastFrameBytes: Int = 0,
    /** Frames fully rendered and acked since the stream started. */
    val frameCount: Long = 0,
    /** Rate over the last couple of seconds -- what says the stream is healthy. */
    val fps: Int = 0,
    val error: String? = null,
    /** Server-side opt-in for remote input; defaults ON so a fast fullscreen
     * entry never meets a dead keyboard while the config loads asynchronously.
     * The config response can still refine (server ships OFF only via
     * IDUPI_REMOTE_INPUT=0). */
    val remoteInputEnabled: Boolean = true,
    /** Auto-ladder's current preset name, as announced by the server (hito 9). */
    val activeQuality: String? = null,
    /**
     * The user's quality CHOICE: "auto" or a pinned preset name. Lives in
     * state (not a remember) so rotation -- which restarts the stream --
     * keeps it. The server's activeQuality may differ under auto.
     */
    val selectedQuality: String = "auto",
    /** Last place the user tapped/dragged, normalised 0..1, for smart keyboard panning. */
    val lastInteractionFraction: Pair<Double, Double>? = null,
    /** Wall-clock ms when [lastInteractionFraction] was last set. The smart
     *  pan reads both: a stale fraction (older than ~2s) must NOT move the
     *  picture, because by then the user has likely moved their attention
     *  to a different field (or the keyboard is being opened for the
     *  currently-focused one, whose position we cannot infer from pixels). */
    val lastInteractionTime: Long = 0L,
    /**
     * Local echo of what the split keyboard is typing so the user can see it
     * without auto-pan -- the fullscreen strip is too short to also lift the
     * image, and the preview sits between the keys and the picture. Updated
     * on every [sendKey] (chars append, backspace drops, enter clears) and
     * wiped by [clearKeyboardPreview] when the keyboard closes.
     */
    val keyboardPreview: String = "",
) {
    companion object {
        /** Hard cap on the echo so a runaway auto-repeat cannot blow up the
         *  preview bar. The UI shows only the trailing window anyway. */
        const val KEYBOARD_PREVIEW_MAX = 80
        const val KEYBOARD_PREVIEW_VISIBLE = 40
    }
}

/**
 * Pacing owner of the remote screen (brief §4.1): the receiver paces, the
 * sender never pushes. Every frame is decoded and exposed to the UI FIRST and
 * only then acknowledged -- acknowledging before rendering would let the
 * server capture ahead and rebuild a queue of stale frames.
 */
class RemoteScreenViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val decodeJpeg: (ByteArray) -> ImageBitmap? = ::decodeJpegToBitmap,
    /** Draws decoded tiles onto the cached frame; injectable for tests. */
    private val compositeTiles:
        (base: ImageBitmap, tiles: List<Triple<ImageBitmap, Int, Int>>) -> ImageBitmap =
        ::drawTilesOn,
    private val clockMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val client get() = clientSource.client

    private val _uiState = MutableStateFlow(RemoteScreenUiState())
    val uiState: StateFlow<RemoteScreenUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var sid: String? = null

    /**
     * Stream generation counter: every startStreaming/stopStreaming bumps it
     * so a CANCELLED job's catch can never overwrite streaming=false after a
     * NEWER job already set streaming=true (monitor switch + rotation race:
     * the old job's finally used to clobber the new stream's live state).
     */
    private var streamJobId = 0

    /** Last fully-known picture: keyframes replace it, tiles patch it. */
    private var frameCache: ImageBitmap? = null

    /** Arrival times of the frames actually shown, pruned to the rate window. */
    private var arrivals: List<Long> = emptyList()

    fun refreshMonitors() {
        viewModelScope.launch {
            try {
                val monitors = client.getScreenMonitors()
                // Default to the primary so "open screen -> watch" is one tap;
                // the user can still switch from the picker.
                val primary = monitors.firstOrNull { it.primary } ?: monitors.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    monitors = monitors,
                    selectedMonitorId = primary?.id,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun selectMonitor(id: Int) {
        val previous = _uiState.value.selectedMonitorId
        _uiState.value = _uiState.value.copy(selectedMonitorId = id)
        // A monitor switch mid-stream restarts the stream: the server sends a
        // full keyframe for the new monitor instead of tiles of the old one.
        if (_uiState.value.streaming && id != previous) {
            val state = _uiState.value
            startStreaming(viewportForCurrentBox.first, viewportForCurrentBox.second)
        }
    }

    /** Viewport the UI wants; updated by the screen on layout changes. */
    var viewportForCurrentBox: Pair<Int, Int> = DEFAULT_VIEWPORT_PAIR
        private set

    fun updateViewport(boxW: Int, boxH: Int) {
        val monitor = _uiState.value.monitors.firstOrNull { it.id == _uiState.value.selectedMonitorId }
        viewportForCurrentBox = viewportFor(monitor, boxW, boxH)
    }

    fun startStreaming(viewportW: Int, viewportH: Int, quality: String? = null) {
        stopStreaming()
        val monitorId = _uiState.value.selectedMonitorId ?: return
        val chosen = quality ?: _uiState.value.selectedQuality
        val request = ScreenStreamRequest(
            sid = UUID.randomUUID().toString(),
            monitor = monitorId,
            viewportW = viewportW,
            viewportH = viewportH,
            quality = chosen
        )
        sid = request.sid
        viewportForCurrentBox = viewportW to viewportH
        // Only THIS generation may write streaming/error: a superseded job's
        // catch must never turn the state off behind the stream that replaced it.
        val jobId = ++streamJobId
        _uiState.value = _uiState.value.copy(streaming = true)

        streamJob = viewModelScope.launch {
            try {
                client.screenFrames(request).collect { message ->
                    when (message) {
                        is ScreenWireMessage.Frame -> renderAndAcknowledge(request.sid, message)
                        is ScreenWireMessage.Control -> applyControl(message.json)
                    }
                }
                // Stream ended cleanly (server closed): surface it as stopped.
                if (jobId == streamJobId) {
                    _uiState.value = _uiState.value.copy(streaming = false)
                }
            } catch (e: Exception) {
                if (jobId == streamJobId) {
                    _uiState.value = _uiState.value.copy(
                        streaming = false,
                        error = if (e is CancellationException) null else e.message
                    )
                }
            }
        }
    }

    /**
     * Server-announced controls (hito 9): today only quality_changed -- the
     * auto ladder's public step. Unknown controls are ignored, never errors.
     */
    private fun applyControl(json: ByteArray) {
        val event = try {
            controlJson.decodeFromString<ScreenQualityChanged>(json.decodeToString())
        } catch (_: Exception) {
            return
        }
        if (event.type == "quality_changed" && event.name != null) {
            _uiState.value = _uiState.value.copy(activeQuality = event.name)
        }
    }

    private val controlJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Live quality change: records the choice and, mid-stream, tells the
     * server WITHOUT restarting. The server announces what actually applies
     * as a quality_changed control, so [activeQuality] stays server truth --
     * under "auto" it will keep moving on its own.
     */
    fun setScreenQuality(quality: String) {
        _uiState.value = _uiState.value.copy(selectedQuality = quality)
        val currentSid = sid ?: return
        viewModelScope.launch {
            try {
                client.changeScreenQuality(currentSid, quality)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            }
        }
    }

    /**
     * Decode, expose to the UI, THEN ack with real telemetry. The ordering is
     * the whole point: an unrendered-but-acked frame lets the server capture
     * ahead of what the user actually sees.
     *
     * Tile frames (hito 8) patch the CACHED picture instead of replacing it:
     * each declared tile is decoded separately and drawn at its rectangle.
     * A frame we cannot render is still acknowledged -- withholding it stalls
     * the receiver-paced session, while showing one stale frame costs a beat
     * nobody perceives.
     */
    private suspend fun renderAndAcknowledge(sid: String, frame: ScreenWireMessage.Frame) {
        val startedAt = System.nanoTime()
        val bitmap: ImageBitmap?
        if (isTileFrame(frame.meta)) {
            val base = frameCache
            bitmap = when {
                // Unchanged screen: an empty tile set keeps the pacing loop
                // alive at near-zero wire cost and changes nothing visually.
                frame.meta.tiles.isEmpty() -> base
                // No cached keyframe to patch (shouldn't happen -- servers
                // lead with a keyframe) -- show nothing new rather than a
                // half-patched picture.
                base == null -> null
                else -> try {
                    val tiles = tileSlices(frame.jpeg, frame.meta.tiles)
                        .mapNotNull { (ref, chunk) ->
                            decodeJpeg(chunk)?.let { decoded ->
                                val rect = tileRect(ref.i, frame.meta.w, frame.meta.h, frame.meta.tw)
                                Triple(decoded, rect.left, rect.top)
                            }
                        }
                    compositeTiles(base, tiles).also { frameCache = it }
                } catch (_: IllegalStateException) {
                    // Malformed tile map: keep the previous picture; the next
                    // keyframe (monitor switch, quality change) heals us.
                    null
                }
            }
        } else {
            bitmap = decodeJpeg(frame.jpeg)
            if (bitmap != null) frameCache = bitmap
        }
        val renderMs = (System.nanoTime() - startedAt) / 1_000_000

        if (bitmap != null) {
            val now = clockMs()
            arrivals = recentArrivals(arrivals + now, now)
            val state = _uiState.value
            _uiState.value = state.copy(
                currentFrame = bitmap,
                frameMeta = frame.meta,
                lastRenderMs = renderMs,
                lastFrameBytes = frame.jpeg.size,
                frameCount = state.frameCount + 1,
                fps = frameRate(arrivals, now)
            )
        }
        // A frame we could not decode is still acknowledged. Withholding the
        // ack costs the whole session -- nothing else can wake a stream the
        // receiver paces -- while acknowledging it costs one frame of picture
        // that the previous frame covers for.
        //
        // And an ack that FAILS must be retried: after a failed capture the
        // server hands the ack back and waits for exactly this redelivery.
        // Giving up after one attempt froze live sessions at their first
        // hiccup until the socket timeout killed them.
        var backoffMs = ACK_RETRY_BASE_MS
        for (attempt in 1..ACK_MAX_ATTEMPTS) {
            try {
                client.acknowledgeScreenFrame(
                    sid = sid,
                    frameId = frame.meta.id,
                    bytes = frame.jpeg.size,
                    renderMs = renderMs
                )
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == ACK_MAX_ATTEMPTS) {
                    _uiState.value = _uiState.value.copy(error = e.message)
                } else {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(ACK_RETRY_CAP_MS)
                }
            }
        }
    }

    fun refreshConfig() {
        viewModelScope.launch {
            try {
                val config = client.getScreenConfig()
                _uiState.value = _uiState.value.copy(remoteInputEnabled = config.remoteInputEnabled)
            } catch (_: Exception) {
                // Config is advisory: the server 403s input regardless.
            }
        }
    }

    /**
     * Second gate of remote input, behind the server's own 403: without the
     * opt-in nothing leaves the phone. Coordinates are normalised 0..1 --
     * pixel maths lives in the Go helper (the left monitor starts at x=-1920).
     */
    fun sendInput(event: ScreenInputEvent) {
        // Remember where AND when the user last interacted, so the 40%
        // keyboard can push the image to keep that spot visible ONLY if the
        // tap is recent (owner's smart-pan request). A stale fraction must
        // not move the picture -- by then the user is typing in a different
        // field, or the keyboard is being opened for a field we cannot
        // detect from pixels alone.
        if (event.x != null && event.y != null) {
            _uiState.value = _uiState.value.copy(
                lastInteractionFraction = event.x to event.y,
                lastInteractionTime = clockMs(),
            )
        }
        if (!_uiState.value.remoteInputEnabled) {
            // Loud, not silent: a dropped keystroke with no trace looked like
            // a dead keyboard in fullscreen. The server still 403s a real opt-out.
            Log.w(TAG, "remote input disabled; dropping ${event.type}")
            return
        }
        viewModelScope.launch {
            try {
                client.sendScreenInput(event)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            }
        }
    }

    /**
     * Realtime typing (hito 7): every keystroke leaves the phone THE MOMENT
     * it is pressed, never on IME commit. Same double gate as the mouse --
     * a keyboard is an even more loaded gun than a pointer.
     *
     * Also drives [RemoteScreenUiState.keyboardPreview] -- a LOCAL echo of what
     * the user is typing, displayed in a small bar above the keys. Owner
     * request: typing in the bottom 40% of a landscape fullscreen was
     * invisible to the user, and a forced zoom (the previous smart-pan) only
     * distorted the picture without revealing the caret. The preview is the
     * honest fix: the keys go out exactly as before, and the UI gets a
     * non-blocking echo so the user can see what just happened.
     *
     * For a single key tap (the split keyboard's normal path) the call is
     * fire-and-forget on its own coroutine -- latency is what the user
     * feels. For a multi-press burst (paste, autocomplete, replacement) the
     * single-press path races through the Go helper's stdin and the chars
     * arrive interleaved. The caller must use [sendKeys] for any list with
     * more than one press; this function stays the single-keystroke path.
     */
    fun sendKey(press: KeyPress) {
        // Update the echo FIRST, regardless of the input gate: a disabled
        // input still shows what the user pressed locally, so they know the
        // key registered even if the server drops it.
        _uiState.value = _uiState.value.copy(keyboardPreview = previewAfter(_uiState.value.keyboardPreview, press))
        if (!_uiState.value.remoteInputEnabled) {
            Log.w(TAG, "remote input disabled; dropping key code=${press.code}")
            return
        }
        viewModelScope.launch {
            try {
                client.sendScreenInput(
                    ScreenInputEvent(type = press.wireAction, code = press.code)
                )
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            }
        }
    }

    /**
     * Sequential key sender (bug-1 fix, Aug 28): a paste of N chars, an
     * IME replacement, or any list of presses fired from a single UI event
     * must travel in ORDER over the wire. Firing N concurrent coroutines
     * (one per `viewModelScope.launch` inside [sendKey]) races the helper's
     * stdin pipe and the OS receives the chars interleaved -- the
     * distortion the owner saw: "pluging para opencode" arriving as
     * "lpuig gnpraap onoedce".
     *
     * The fix is structural, not algorithmic: ONE coroutine drains the
     * list, awaiting the network round-trip for each press before sending
     * the next. The order in the list is the order on the wire, every
     * time. A small inter-press delay gives Windows time to settle each
     * keystroke in its input queue before the next arrives, so the OS
     * never buffers two of them into one.
     *
     * Also used for chord assembly (bug-2 fix): the three events of a
     * Ctrl+V (keydown VK_CONTROL, keychar 'v', keyup VK_CONTROL) travel
     * in the same coroutine so the helper sees the modifier held across
     * the character press.
     *
     * The local preview update stays in sync via [previewAfter] inside
     * the loop, so the echo bar still grows char-by-char as the burst
     * drains -- the user sees the typing happen, just correctly.
     */
    fun sendKeys(presses: List<KeyPress>) {
        if (presses.isEmpty()) return
        // One tap short-circuits to the realtime path: no ordering hazard,
        // no extra coroutine.
        if (presses.size == 1) {
            sendKey(presses.single())
            return
        }
        if (!_uiState.value.remoteInputEnabled) {
            // Drop the whole burst with one log line, mirroring the single
            // press behaviour but louder so a paste never appears to work
            // locally while the server silently dropped it.
            Log.w(TAG, "remote input disabled; dropping ${presses.size} keys")
            // Still update the local echo: the user pressed them and the
            // preview is the honest local story.
            var echo = _uiState.value.keyboardPreview
            presses.forEach { echo = previewAfter(echo, it) }
            _uiState.value = _uiState.value.copy(keyboardPreview = echo)
            return
        }
        viewModelScope.launch {
            var echo = _uiState.value.keyboardPreview
            for (press in presses) {
                echo = previewAfter(echo, press)
                _uiState.value = _uiState.value.copy(keyboardPreview = echo)
                try {
                    client.sendScreenInput(
                        ScreenInputEvent(type = press.wireAction, code = press.code)
                    )
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                    // Stop the burst on the first wire error: half-sent
                    // bursts leave the remote field in a worse state than
                    // a clean drop.
                    return@launch
                }
                // Inter-press pacing. 0 for a single CHAR after a chord's
                // modifier release (no reason to wait longer), ~3ms for
                // everything else -- short enough to be invisible to a
                // human, long enough that Windows' SendInput queue
                // presents each keystroke as its own event.
                delay(INTER_KEY_DELAY_MS)
            }
        }
    }

    /** Wipe the local echo when the keyboard closes -- the next session
     *  should not show a stale line of text from the previous one. */
    fun clearKeyboardPreview() {
        if (_uiState.value.keyboardPreview.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(keyboardPreview = "")
        }
    }

    /**
     * Pure projection of a [KeyPress] onto the preview string. Lives next to
     * [sendKey] so the wire-side echo and the test can share the rule:
     *   - printable char   -> append
     *   - BACKSPACE        -> drop the last char (if any)
     *   - ENTER            -> clear (a sentence ended)
     *   - everything else  -> ignored, the preview is for narrative text,
     *                         not for hidden control surfaces
     * Output is capped at [RemoteScreenUiState.KEYBOARD_PREVIEW_MAX] chars
     * so a held key can never inflate it; the UI shows the trailing
     * [RemoteScreenUiState.KEYBOARD_PREVIEW_VISIBLE] window with ellipsis.
     *
     * Bug-2 fix: rewrite as a `when` over [press.kind] with an inner `when`
     * over the resolved [SpecialKey]. The previous boolean chain was
     * logically correct in isolation, but on the realtime text field the
     * [keyboardDiffs] helper emits BACKSPACE through `KeyPress.special(...)`
     * (kind=SPECIAL) while the split keyboard's ⌫ keycap also goes through
     * `KeyPress.special(...)` -- both paths must hit the drop branch.
     * Keeping BACKSPACE in its own inner `when` (instead of a flat boolean
     * chain that could shadow it) guarantees the drop fires regardless of
     * which UI surface emitted the press.
     */
    private fun previewAfter(current: String, press: KeyPress): String {
        val next = when (press.kind) {
            KeyPress.Kind.CHAR -> if (press.phase == KeyPress.Phase.PRESS) {
                current + Char(press.code)
            } else current
            KeyPress.Kind.SPECIAL -> when (press.phase) {
                // Half-events (DOWN/UP) are modifier-hold scaffolding for
                // chords like Ctrl+V -- the user never sees a literal
                // "VK_CONTROL" appended to the echo bar. The character
                // press between them is what the preview should grow.
                KeyPress.Phase.DOWN, KeyPress.Phase.UP -> current
                KeyPress.Phase.PRESS -> when (press.asSpecial()) {
                    SpecialKey.BACKSPACE -> if (current.isNotEmpty()) current.dropLast(1) else current
                    SpecialKey.ENTER -> ""
                    else -> current
                }
            }
        }
        return if (next.length > RemoteScreenUiState.KEYBOARD_PREVIEW_MAX) {
            next.takeLast(RemoteScreenUiState.KEYBOARD_PREVIEW_MAX)
        } else next
    }
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        // Invalidate the cancelled job's generation: its catch must not write
        // state (or an error card) after a manual stop.
        streamJobId++
        sid = null
        // Tiles of a dead stream must never patch the next stream's picture.
        frameCache = null
        // A stopped stream has no rate. Keeping the last one would leave a
        // frozen picture claiming to be live.
        arrivals = emptyList()
        _uiState.value = _uiState.value.copy(streaming = false, fps = 0)
    }

    override fun onCleared() {
        stopStreaming()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RemoteScreenVM"
        /** Bounded ack retries: enough to cross a helper respawn (~instant)
         * or a transient GDI hiccup, small enough to surface real breakage. */
        const val ACK_MAX_ATTEMPTS = 8
        const val ACK_RETRY_BASE_MS = 100L
        const val ACK_RETRY_CAP_MS = 2_000L
        /** Pacing between sequential presses inside [sendKeys] (bug-1 fix).
         *  3ms is well below human perception of "instant" (~80ms) and
         *  comfortably above the SendInput queue's inter-event resolution
         *  on Windows, so a 20-char paste arrives as 20 distinct keystrokes
         *  in the right order, not as a buffer-collapse into a single
         *  chord. */
        const val INTER_KEY_DELAY_MS = 3L
        val DEFAULT_VIEWPORT_PAIR: Pair<Int, Int> =
            com.idupi.app.domain.model.DEFAULT_VIEWPORT
    }
}

private fun viewportFor(monitor: ScreenMonitor?, boxW: Int, boxH: Int): Pair<Int, Int> =
    com.idupi.app.domain.model.viewportFor(monitor, boxW, boxH)

/**
 * Real tile compositing: copies the cached frame once per tiles-frame and
 * draws each decoded tile at its destination rectangle. The copy keeps the
 * UI's currently-drawn bitmap untouched while we patch.
 */
private fun drawTilesOn(
    base: ImageBitmap,
    tiles: List<Triple<ImageBitmap, Int, Int>>,
): ImageBitmap {
    val out = base.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    for ((tile, x, y) in tiles) {
        canvas.drawBitmap(tile.asAndroidBitmap(), x.toFloat(), y.toFloat(), null)
    }
    return out.asImageBitmap()
}
