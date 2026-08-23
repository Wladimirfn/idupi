package com.example.idupi.domain.model

import kotlinx.serialization.Serializable

/** One monitor as reported by GET /api/v1/screen/monitors. */
@Serializable
data class ScreenMonitor(
    val id: Int,
    val name: String,
    val primary: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val scaleFactor: Double = 1.0
)

/** Meta JSON that travels inside every framed screen message. */
@Serializable
data class ScreenFrameMeta(
    val id: Int,
    val w: Int,
    val h: Int,
    val bytes: Int = 0,
    val quality: Int = 55,
    val monitor: String = ""
)

/**
 * Body of POST /api/v1/screen/ack.
 *
 * Declared rather than assembled as a map: its values are a String, two Ints
 * and a Long, so a map of them is a `Map<String, Any>` and kotlinx has no
 * serializer for `Any`. Posting one threw before it left the phone, and since
 * the stream is paced by these, the picture froze on its first frame.
 */
@Serializable
data class ScreenAckPayload(
    val sid: String,
    val frameId: Int,
    val bytes: Int,
    val renderMs: Long
)

/** Query parameters for opening /api/v1/screen/stream. */
data class ScreenStreamRequest(
    val sid: String,
    val monitor: Int,
    val viewportW: Int,
    val viewportH: Int,
    val quality: String = "55"
)

/** What the server allows over this bridge (remote input opt-in). */
@Serializable
data class ScreenRemoteConfig(
    val remoteInputEnabled: Boolean = false,
)

/** One mouse event; coordinates are normalised 0..1 against ONE monitor. */
@Serializable
data class ScreenInputEvent(
    val type: String, // "move" | "down" | "up" | "scroll" | "relmove"
    val monitor: Int = 0,
    val x: Double? = null,
    val y: Double? = null,
    val button: String = "left",
    /** relmove: cursor pixels to travel -- pad mode's relative movement. */
    val dx: Double? = null,
    val dy: Double? = null,
    /** keychar: UTF-16 unit | keyvk: Windows virtual-key code. */
    val code: Int? = null,
    val delta: Int? = null,
)