package com.example.idupi.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.ScreenMonitor
import com.example.idupi.domain.model.frameRate
import com.example.idupi.domain.model.recentArrivals
import com.example.idupi.domain.model.ScreenStreamRequest
import com.example.idupi.domain.model.ScreenWireMessage
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.Job
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
    val error: String? = null
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
    private val clockMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val client get() = clientSource.client

    private val _uiState = MutableStateFlow(RemoteScreenUiState())
    val uiState: StateFlow<RemoteScreenUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var sid: String? = null

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
     */
    private suspend fun renderAndAcknowledge(sid: String, frame: ScreenWireMessage.Frame) {
        val startedAt = System.nanoTime()
        val bitmap = decodeJpeg(frame.jpeg)
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
        runCatching {
            client.acknowledgeScreenFrame(
                sid = sid,
                frameId = frame.meta.id,
                bytes = frame.jpeg.size,
                renderMs = renderMs
            )
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        sid = null
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
        val DEFAULT_VIEWPORT_PAIR: Pair<Int, Int> =
            com.example.idupi.domain.model.DEFAULT_VIEWPORT
    }
}

private fun viewportFor(monitor: ScreenMonitor?, boxW: Int, boxH: Int): Pair<Int, Int> =
    com.example.idupi.domain.model.viewportFor(monitor, boxW, boxH)
