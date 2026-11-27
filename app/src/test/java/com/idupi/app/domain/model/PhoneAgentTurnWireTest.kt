package com.idupi.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract of the phone bubble turns (phone-bubble T6), mirroring
 * `idupi-server/docs/phone.md` "Bubble agent turns".
 *
 * The client decodes with `encodeDefaults = false` on the encode side and
 * `ignoreUnknownKeys` on the decode side, so every field must have a default:
 * a document from a newer server must decode instead of failing.
 */
class PhoneAgentTurnWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a full turn decodes every documented field`() {
        val turn = json.decodeFromString<PhoneAgentTurn>(
            """
            {
              "id": "pat-1a2b3c4d5e6f",
              "text": "revisa mi correo",
              "status": "running",
              "answer": "",
              "steps": [
                {"at": "2026-11-26T12:00:01.000Z", "kind": "started", "summary": "Pedido iniciado"},
                {"at": "2026-11-26T12:00:02.000Z", "kind": "cli", "summary": "screen"}
              ],
              "error": null,
              "createdAt": "2026-11-26T12:00:00.000Z",
              "updatedAt": "2026-11-26T12:00:02.000Z",
              "agent": "claude"
            }
            """.trimIndent()
        )

        assertEquals("pat-1a2b3c4d5e6f", turn.id)
        assertEquals("revisa mi correo", turn.text)
        assertEquals(PhoneAgentTurnStatus.RUNNING, turn.status)
        assertEquals("", turn.answer)
        assertEquals(2, turn.steps.size)
        assertEquals("started", turn.steps[0].kind)
        assertEquals("Pedido iniciado", turn.steps[0].summary)
        assertEquals("2026-11-26T12:00:01.000Z", turn.steps[0].at)
        assertNull(turn.error)
        assertEquals("2026-11-26T12:00:00.000Z", turn.createdAt)
        assertEquals("2026-11-26T12:00:02.000Z", turn.updatedAt)
        assertEquals("claude", turn.agent)
    }

    @Test
    fun `a minimal turn decodes with defaults`() {
        val turn = json.decodeFromString<PhoneAgentTurn>(
            """{"id":"pat-000000000000","text":"hola","status":"queued"}"""
        )

        assertEquals("pat-000000000000", turn.id)
        assertEquals(PhoneAgentTurnStatus.QUEUED, turn.status)
        assertEquals("", turn.answer)
        assertTrue(turn.steps.isEmpty())
        assertNull(turn.error)
        assertEquals("", turn.agent)
    }

    @Test
    fun `a turn with a failed status keeps its error`() {
        val turn = json.decodeFromString<PhoneAgentTurn>(
            """{"id":"pat-000000000001","text":"x","status":"failed","error":"agent failed"}"""
        )

        assertEquals(PhoneAgentTurnStatus.FAILED, turn.status)
        assertEquals("agent failed", turn.error)
    }

    @Test
    fun `the post envelope decodes the created turn`() {
        val envelope = json.decodeFromString<PhoneAgentTurnEnvelope>(
            """{"turn":{"id":"pat-000000000002","text":"x","status":"queued"}}"""
        )

        assertEquals("pat-000000000002", envelope.turn.id)
    }

    @Test
    fun `the list envelope decodes newest first`() {
        val envelope = json.decodeFromString<PhoneAgentTurnsEnvelope>(
            """
            {"turns":[
              {"id":"pat-000000000003","text":"b","status":"done","answer":"listo"},
              {"id":"pat-000000000002","text":"a","status":"cancelled"}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("pat-000000000003", "pat-000000000002"), envelope.turns.map { it.id })
        assertEquals("listo", envelope.turns.first().answer)
    }

    @Test
    fun `status helpers separate active from terminal`() {
        assertTrue(PhoneAgentTurnStatus.isActive(PhoneAgentTurnStatus.QUEUED))
        assertTrue(PhoneAgentTurnStatus.isActive(PhoneAgentTurnStatus.RUNNING))
        assertTrue(PhoneAgentTurnStatus.isTerminal(PhoneAgentTurnStatus.DONE))
        assertTrue(PhoneAgentTurnStatus.isTerminal(PhoneAgentTurnStatus.FAILED))
        assertTrue(PhoneAgentTurnStatus.isTerminal(PhoneAgentTurnStatus.CANCELLED))
        assertTrue(!PhoneAgentTurnStatus.isTerminal(PhoneAgentTurnStatus.RUNNING))
    }
}
