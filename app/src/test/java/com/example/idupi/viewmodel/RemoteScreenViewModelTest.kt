package com.example.idupi.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import com.example.idupi.MainDispatcherRule
import com.example.idupi.FakeClientSource
import com.example.idupi.FakeIduPiClient
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.ScreenFrameMeta
import com.example.idupi.domain.model.ScreenInputEvent
import com.example.idupi.domain.model.ScreenMonitor
import com.example.idupi.domain.model.ScreenRemoteConfig
import com.example.idupi.domain.model.ScreenTileRef
import com.example.idupi.domain.model.ScreenWireMessage
import com.example.idupi.domain.model.SpecialKey
import com.example.idupi.domain.model.keyboardDiffs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The two non-negotiable pacing rules of the remote screen live here, not in
 * the UI: ACK AFTER RENDERING (the server captures nothing until we confirm),
 * and requesting the stream at our viewport size.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteScreenViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val primaryMonitor = ScreenMonitor(
        id = 0, name = "\\\\.\\DISPLAY2", primary = true, x = 0, y = 0, width = 1920, height = 1080
    )

    private fun frame(id: Int) = ScreenWireMessage.Frame(
        meta = com.example.idupi.domain.model.ScreenFrameMeta(id = id, w = 800, h = 450),
        jpeg = byteArrayOf(1, 2, 3)
    )

    private val stubBitmap = object : ImageBitmap {
        override val width get() = 8
        override val height get() = 4
        override val config: androidx.compose.ui.graphics.ImageBitmapConfig =
            androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean = false
        override val colorSpace: androidx.compose.ui.graphics.colorspace.ColorSpace =
            androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
        override fun prepareToDraw() {}
        override fun readPixels(
            buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int,
            bufferOffset: Int, stride: Int
        ) {}
    }

    @Test
    fun `acks each frame only AFTER decoding it`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        fake.screenFramesToEmit = listOf(frame(1))
        val events = mutableListOf<String>()
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { events.add("decode"); stubBitmap }
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()

        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        // Decode happened exactly once, and the ack carries the rendered frame's id.
        assertEquals(listOf("decode"), events)
        assertEquals(1, fake.screenAcks.size)
        val ack = fake.screenAcks.single()
        assertEquals(1, ack.frameId)
        assertEquals(800, ack.frameViewportW)
        assertEquals(3, ack.bytes)
        assertTrue("renderMs must be measured, got ${ack.renderMs}", ack.renderMs >= 0)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `the stream is requested at the given viewport`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap }
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()

        viewModel.startStreaming(viewportW = 1080, viewportH = 607)
        advanceUntilIdle()

        val request = requireNotNull(fake.lastScreenStreamRequest)
        assertEquals(1080, request.viewportW)
        assertEquals(607, request.viewportH)
        assertEquals(0, request.monitor) // primary selected by default
    }

    @Test
    fun `refreshMonitors selects the primary monitor`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(
            ScreenMonitor(id = 1, name = "left", x = -1920, y = 0, width = 1920, height = 1080),
            primaryMonitor
        )
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap }
        )

        viewModel.refreshMonitors()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.selectedMonitorId)
    }

    /**
     * This used to assert the opposite -- that a frame which failed to decode
     * was never acknowledged -- and its own comment named the consequence:
     * "the server would eventually time this stream out". It did. After 1996
     * good frames one failed, the acknowledgement never went out, and because
     * the stream is paced by acknowledgements alone there was nothing left to
     * wake it: the picture froze until the socket gave up thirty seconds later.
     *
     * Acknowledging a frame we could not show costs one frame of picture. Not
     * acknowledging it costs the session. The screen keeps showing the last
     * good frame for one extra beat, which nobody perceives.
     */
    @Test
    fun `a frame that fails to decode is still acked so the stream survives`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        fake.screenFramesToEmit = listOf(frame(5))
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { null } // decoder fails
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()

        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        assertEquals(1, fake.screenAcks.size)
        assertEquals(5, fake.screenAcks.single().frameId)
        // Acked, but never shown: the undecodable frame must not become the
        // picture, and it must not be counted as one that was rendered.
        assertEquals(null, viewModel.uiState.value.currentFrame)
        assertEquals(0L, viewModel.uiState.value.frameCount)
    }

    @Test
    fun `a failed ack is retried until it lands, or the stream dies with it`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        // First frame: its ack fails twice before landing. Second frame: clean.
        // If the ViewModel gave up after one attempt, neither would be acked and
        // the ack-paced server would go silent until the socket timeout killed
        // the session -- the exact freeze seen live after ~a minute of streaming.
        fake.failNextAcks = 2
        fake.screenFramesToEmit = listOf(frame(1), frame(2))
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap }
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()

        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        assertEquals(2, fake.screenAcks.size)
        assertEquals(listOf(1, 2), fake.screenAcks.map { it.frameId })
    }
    

    @Test
    fun `input is not sent while the server has it disabled`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenConfigToReturn = ScreenRemoteConfig(remoteInputEnabled = false)
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshConfig()
        advanceUntilIdle()

        viewModel.sendInput(ScreenInputEvent(type = "move", monitor = 0, x = 0.5, y = 0.5))
        advanceUntilIdle()

        // Seeing the screen must not silently mean moving it: without opt-in
        // the ViewModel is the second gate, behind the server's own 403.
        assertEquals(0, fake.screenInputs.size)
    }

    @Test
    fun `input flows once the server opted in`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenConfigToReturn = ScreenRemoteConfig(remoteInputEnabled = true)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshConfig()
        advanceUntilIdle()

        viewModel.sendInput(ScreenInputEvent(type = "move", monitor = 0, x = 0.25, y = 0.75))
        advanceUntilIdle()

        assertEquals(1, fake.screenInputs.size)
        assertEquals(0.25, fake.screenInputs.first().x)
    }

    @Test
    fun `keystrokes are gated like the mouse while input is disabled`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenConfigToReturn = ScreenRemoteConfig(remoteInputEnabled = false)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshConfig()
        advanceUntilIdle()

        viewModel.sendKey(KeyPress.char('a'))
        advanceUntilIdle()

        // A keyboard is a MORE loaded gun than a pointer: the same second
        // gate must hold it back.
        assertEquals(0, fake.screenInputs.size)
    }

    @Test
    fun `a keystroke travels the moment it is pressed, one wire event per key`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenConfigToReturn = ScreenRemoteConfig(remoteInputEnabled = true)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshConfig()
        advanceUntilIdle()

        val presses = keyboardDiffs("", "hi\n")
        presses.forEach { viewModel.sendKey(it) }
        advanceUntilIdle()

        assertEquals(3, fake.screenInputs.size)
        assertEquals("keychar", fake.screenInputs[0].type)
        assertEquals('h'.code, fake.screenInputs[0].code)
        assertEquals("keychar", fake.screenInputs[1].type)
        assertEquals('i'.code, fake.screenInputs[1].code)
        // Control keys ride virtual keys, not unicode.
        assertEquals("keyvk", fake.screenInputs[2].type)
        assertEquals(SpecialKey.ENTER.vk, fake.screenInputs[2].code)
    }

    // --- dirty tiles (hito 8): compositing on the cached frame ---

    @Test
    fun `a quality_changed control surfaces the active preset`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenFramesToEmit = listOf(
            keyframe(1),
            ScreenWireMessage.Control(
                """{"type":"quality_changed","name":"baja","scale":0.4,"jpegQuality":40,"maxFps":10}"""
                    .encodeToByteArray(),
            ),
        )
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()
        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        assertEquals("baja", viewModel.uiState.value.activeQuality)
        // The frame itself was still rendered and acked normally.
        assertEquals(stubBitmap, viewModel.uiState.value.currentFrame)
        assertEquals(listOf(1), fake.screenAcks.map { it.frameId })
    }

    @Test
    fun `starting with auto quality requests the ladder over the wire`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenMonitorsToReturn = listOf(primaryMonitor)
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { stubBitmap },
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()
        viewModel.startStreaming(viewportW = 800, viewportH = 450, quality = "auto")
        advanceUntilIdle()

        assertEquals("auto", requireNotNull(fake.lastScreenStreamRequest).quality)
    }

    private fun keyframe(id: Int) = ScreenWireMessage.Frame(
        meta = ScreenFrameMeta(id = id, w = 800, h = 450),
        jpeg = byteArrayOf(0x11)
    )

    @Test
    fun `a tile frame is decoded and composited onto the cached keyframe`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenFramesToEmit = listOf(
            keyframe(1),
            ScreenWireMessage.Frame(
                meta = ScreenFrameMeta(
                    id = 2, w = 800, h = 450, type = "tiles", tw = 64, th = 64,
                    tiles = listOf(ScreenTileRef(i = 5, len = 3)),
                ),
                jpeg = byteArrayOf(0x22, 0x22, 0x22),
            ),
        )
        val decoded = mutableListOf<ByteArray>()
        val compositions = mutableListOf<Pair<ImageBitmap, List<Triple<ImageBitmap, Int, Int>>>>()
        val composed = stubBitmap
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { bytes -> decoded.add(bytes); stubBitmap },
            compositeTiles = { base, tiles -> compositions.add(base to tiles); composed },
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()
        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        // Keyframe decoded whole; the tile chunk decoded separately.
        assertEquals(2, decoded.size)
        assertTrue(decoded[1].contentEquals(byteArrayOf(0x22, 0x22, 0x22)))
        // Compositing received the cached keyframe as base and the tile at
        // tile 5's rectangle: cols=ceil(800/64)=13, so index 5 sits at
        // col 5, row 0 -> x=320, y=0.
        assertEquals(1, compositions.size)
        val (base, tiles) = compositions.single()
        assertEquals(stubBitmap, base)
        assertEquals(Triple(stubBitmap, 320, 0), tiles.single())
        assertEquals(composed, viewModel.uiState.value.currentFrame)
        // Both frames were acknowledged: the pacing loop keeps turning.
        assertEquals(listOf(1, 2), fake.screenAcks.map { it.frameId })
    }

    @Test
    fun `an empty tile set keeps the picture alive without touching it`() = runTest {
        val fake = FakeIduPiClient()
        fake.screenFramesToEmit = listOf(
            keyframe(1),
            ScreenWireMessage.Frame(
                meta = ScreenFrameMeta(id = 2, w = 800, h = 450, type = "tiles", tw = 64, th = 64),
                jpeg = ByteArray(0),
            ),
        )
        var decodes = 0
        val viewModel = RemoteScreenViewModel(
            clientSource = FakeClientSource(fake),
            decodeJpeg = { decodes++; stubBitmap },
        )
        viewModel.refreshMonitors()
        advanceUntilIdle()
        viewModel.startStreaming(viewportW = 800, viewportH = 450)
        advanceUntilIdle()

        // Only the keyframe was ever decoded; nothing changed on screen.
        assertEquals(1, decodes)
        assertEquals(stubBitmap, viewModel.uiState.value.currentFrame)
        // But the ack still flowed: this is what keeps a static desktop's
        // pacing loop from stalling.
        assertEquals(listOf(1, 2), fake.screenAcks.map { it.frameId })
    }
}
