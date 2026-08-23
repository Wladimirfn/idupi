package com.example.idupi.domain.model

import kotlinx.serialization.json.Json

/**
 * Incremental decoder for the helper's binary wire format (mirrored by the
 * server): every stdout-style message is u32be totalLen || kind(1) || body,
 * where a 'J' body is raw JSON and an 'F' body is u32be metaLen || metaJSON ||
 * jpegBytes. NOT SSE, NOT base64 -- base64 would add 33% to the hottest path
 * in the system.
 */
class ScreenFrameCodec(
    private val maxMessageBytes: Int = 64 * 1024 * 1024
) {
    private var pending: ByteArray? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Feeds one network chunk; returns every message completed by it, in order. */
    fun feed(chunk: ByteArray): List<ScreenWireMessage> {
        val buf = pending?.let { prev ->
            ByteArray(prev.size + chunk.size).also { it.copyInto(0, prev); it.copyInto(prev.size, chunk) }
        } ?: chunk

        val messages = mutableListOf<ScreenWireMessage>()
        var offset = 0
        while (buf.size - offset >= 4) {
            val total = readU32be(buf, offset)
            if (total < 1 || total > maxMessageBytes) {
                throw IllegalStateException("screen frame length out of range: $total")
            }
            if (buf.size - offset - 4 < total) break
            val kind = buf[offset + 4].toInt().toChar()
            val body = buf.copyOfRange(offset + 5, offset + 4 + total)
            when (kind) {
                'J' -> messages.add(ScreenWireMessage.Control(body))
                'F' -> messages.add(decodeFrame(body))
                else -> throw IllegalStateException("unknown screen message kind: $kind")
            }
            offset += 4 + total
        }
        pending = if (buf.size - offset > 0) buf.copyOfRange(offset, buf.size) else null
        return messages
    }

    private fun decodeFrame(body: ByteArray): ScreenWireMessage.Frame {
        if (body.size < 5) throw IllegalStateException("frame body shorter than meta length prefix")
        val metaLen = readU32be(body, 0)
        if (metaLen + 4 > body.size) throw IllegalStateException("frame meta length exceeds frame body")
        val meta = json.decodeFromString<ScreenFrameMeta>(body.decodeToString(4, 4 + metaLen))
        return ScreenWireMessage.Frame(meta, body.copyOfRange(4 + metaLen, body.size))
    }

    private fun readU32be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun ByteArray.copyInto(offset: Int, src: ByteArray) {
        src.copyInto(this, offset)
    }
}

/** One decoded wire message from the screen stream. */
sealed interface ScreenWireMessage {
    /** Kind 'J': control JSON from the server (errors, quality_changed, ...). */
    data class Control(val json: ByteArray) : ScreenWireMessage

    /** Kind 'F': one JPEG frame plus its parsed meta. */
    data class Frame(val meta: ScreenFrameMeta, val jpeg: ByteArray) : ScreenWireMessage
}
