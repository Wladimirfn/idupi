package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen stream is binary chunked HTTP with the helper's framing:
 * u32be totalLen || kind(1) || body ('J' control / 'F' frame). SSE would have
 * forced base64 (+33%) onto the hottest path; this codec keeps it raw.
 */
class ScreenFrameCodecTest {

    private fun u32be(n: Int): ByteArray =
        byteArrayOf(
            (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
        )

    private fun controlWire(json: String): ByteArray {
        val body = json.toByteArray()
        return u32be(body.size + 1) + byteArrayOf('J'.code.toByte()) + body
    }

    private fun frameWire(metaJson: String, jpeg: ByteArray): ByteArray {
        val meta = metaJson.toByteArray()
        val body = u32be(meta.size) + meta + jpeg
        return u32be(body.size + 1) + byteArrayOf('F'.code.toByte()) + body
    }

    @Test
    fun `decodes a control message fed in one chunk`() {
        val codec = ScreenFrameCodec()
        val messages = codec.feed(controlWire("""{"id":1,"ok":true}"""))

        assertEquals(1, messages.size)
        assertTrue(messages[0] is ScreenWireMessage.Control)
        assertEquals("""{"id":1,"ok":true}""", String((messages[0] as ScreenWireMessage.Control).json))
    }

    @Test
    fun `reassembles a frame split across chunks`() {
        val codec = ScreenFrameCodec()
        val wire = frameWire("""{"id":2,"w":800,"h":450}""", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))

        assertTrue(codec.feed(wire.copyOfRange(0, 3)).isEmpty())
        assertTrue(codec.feed(wire.copyOfRange(3, 9)).isEmpty())
        val messages = codec.feed(wire.copyOfRange(9, wire.size))

        val frame = messages.single() as ScreenWireMessage.Frame
        assertEquals(2, frame.meta.id)
        assertEquals(800, frame.meta.w)
        assertEquals(450, frame.meta.h)
        assertTrue(frame.jpeg.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)))
    }

    @Test
    fun `parses meta json into typed fields`() {
        val codec = ScreenFrameCodec()
        val messages = codec.feed(
            frameWire(
                """{"id":7,"w":1920,"h":1080,"bytes":36435,"quality":55,"monitor":"\\\\.\\DISPLAY2"}""",
                byteArrayOf(1, 2, 3)
            )
        )
        val frame = messages.single() as ScreenWireMessage.Frame
        assertEquals(1920, frame.meta.w)
        assertEquals(55, frame.meta.quality)
        assertEquals("\\\\.\\DISPLAY2", frame.meta.monitor)
    }

    @Test
    fun `decodes two consecutive messages from one chunk`() {
        val codec = ScreenFrameCodec()
        val wire = controlWire("""{"id":1}""") + controlWire("""{"id":2}""")
        assertEquals(2, codec.feed(wire).size)
    }

    @Test
    fun `rejects an absurd length prefix instead of allocating it`() {
        val codec = ScreenFrameCodec()
        assertThrows(IllegalStateException::class.java) {
            codec.feed(u32be(0x7FFFFFFF))
        }
    }

    @Test
    fun `a partial message is not emitted until its bytes arrive`() {
        val codec = ScreenFrameCodec()
        val full = controlWire("""{"id":9}""")
        val truncated = full.copyOf(full.size - 1)
        assertTrue(codec.feed(truncated).isEmpty())
        assertEquals(1, codec.feed(byteArrayOf('}'.code.toByte())).size)
    }
}
