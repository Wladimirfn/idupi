package com.example.idupi.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The acknowledgement was posted as `mapOf("sid" to sid, "frameId" to id, ...)`.
 * Its values are a String, two Ints and a Long, so the map is a
 * `Map<String, Any>` -- and kotlinx.serialization has no serializer for `Any`.
 * Every ack threw before leaving the phone:
 *
 *     Serializer for subclass 'LinkedHashMap' is not found in the
 *     polymorphic scope of 'Map'.
 *
 * The stream is paced by those acks, so none arriving froze it on its first
 * frame, and the request then died of its own socket timeout thirty seconds
 * later. One failure wearing three faces.
 *
 * A declared payload is the repo's idiom for a mixed body (MessagePayload,
 * RejectPayload, GuardrailPayload) and it is checkable here, off the wire.
 */
class ScreenAckPayloadTest {

    @Test
    fun `the ack serializes with every field the server pairs a frame by`() {
        val json = Json.encodeToString(
            ScreenAckPayload(sid = "abc", frameId = 7, bytes = 160_000, renderMs = 12)
        )

        assertEquals(
            """{"sid":"abc","frameId":7,"bytes":160000,"renderMs":12}""",
            json
        )
    }

    @Test
    fun `mixed value types survive, which is what the map could not do`() {
        // frameId is Int and renderMs is Long on purpose: the map collapsed
        // them to Any and that is exactly where it broke.
        val json = Json.encodeToString(
            ScreenAckPayload(sid = "s", frameId = 0, bytes = 0, renderMs = 9_999_999_999L)
        )

        assertEquals("""{"sid":"s","frameId":0,"bytes":0,"renderMs":9999999999}""", json)
    }
}
