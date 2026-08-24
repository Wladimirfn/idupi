package com.example.idupi.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.ScreenMonitor
import com.example.idupi.domain.model.frameRate
import com.example.idupi.domain.model.recentArrivals
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.ScreenInputEvent
import com.example.idupi.domain.model.ScreenStreamRequest
import com.example.idupi.domain.model.ScreenWireMessage
import com.example.idupi.domain.model.isTileFrame
import com.example.idupi.domain.model.tileRect
import com.example.idupi.domain.model.tileSlices
import com.example.idupi.domain.repository.IduPiClientSource
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
    val frameMeta: com.example.idupi.domain.model.ScreenFrameMeta? = null,
    val lastRenderMs: Long = 0,
    val lastFrameBytes: Int = 0,
    /** Frames fully rendered and acked since the stream started. */
    val frameCount: Long = 0,
    /** Rate over the last couple of seconds -- what says the stream is healthy. */
    val fps: Int = 0,
    val error: String? = null,
    /** Server-side opt-in for remote input; ships OFF. */
    val remoteInputEnabled: Boolean = false,
)

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

    fun startStreaming(viewportW: Int, viewportH: Int) {
        stopStreaming()
        val monitorId = _uiState.value.selectedMonitorId ?: return
        val request = ScreenStreamRequest(
            sid = UUID.randomUUID().toString(),
            monitor = monitorId,
            viewportW = viewportW,
            viewportH = viewportH
        )
        sid = request.sid
        viewportForCurrentBox = viewportW to viewportH
        _uiState.value = _uiState.value.copy(streaming = true)

        streamJob = viewModelScope.launch {
            try {
                client.screenFrames(request).collect { message ->
                    when (message) {
                        is ScreenWireMessage.Frame -> renderAndAcknowledge(request.sid, message)
                        is ScreenWireMessage.Control -> Unit // quality_changed etc.: hito 9
                    }
                }
                // Stream ended cleanly (server closed): surface it as stopped.
                _uiState.value = _uiState.value.copy(streaming = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(streaming = false, error = e.message)
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
        if (!_uiState.value.remoteInputEnabled) return
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
     */
    fun sendKey(press: KeyPress) {
        if (!_uiState.value.remoteInputEnabled) return
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
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
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
        /** Bounded ack retries: enough to cross a helper respawn (~instant)
         * or a transient GDI hiccup, small enough to surface real breakage. */
        const val ACK_MAX_ATTEMPTS = 8
        const val ACK_RETRY_BASE_MS = 100L
        const val ACK_RETRY_CAP_MS = 2_000L
        val DEFAULT_VIEWPORT_PAIR: Pair<Int, Int> =
            com.example.idupi.domain.model.DEFAULT_VIEWPORT
    }
}

private fun viewportFor(monitor: ScreenMonitor?, boxW: Int, boxH: Int): Pair<Int, Int> =
    com.example.idupi.domain.model.viewportFor(monitor, boxW, boxH)

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
