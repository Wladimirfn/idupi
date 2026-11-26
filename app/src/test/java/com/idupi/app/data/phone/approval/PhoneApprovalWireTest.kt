package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import com.idupi.app.domain.model.PhoneApprovalsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for `GET /api/v1/phone/approvals` and the decision answer
 * (phone-approvals T2). Every field has a default because the client decodes
 * with `encodeDefaults = false` and the server may grow fields later.
 */
class PhoneApprovalWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a full approval`() {
        val raw = """
            {
              "id": "apr-1a2b3c4d5e6f",
              "state": "pending",
              "command": { "type": "tap", "args": { "text": "Enviar" } },
              "reason": "risky_text",
              "target": "Enviar",
              "foregroundApp": { "packageName": "com.whatsapp", "label": "WhatsApp" },
              "requestedBy": "cli",
              "createdAt": "2026-11-24T12:00:00.000Z",
              "expiresAt": "2026-11-24T12:10:00.000Z",
              "decidedAt": null,
              "commandId": "phc-1a2b3c4d5e6f",
              "resultCommandId": null
            }
        """.trimIndent()

        val approval = json.decodeFromString(PhoneApproval.serializer(), raw)

        assertEquals("apr-1a2b3c4d5e6f", approval.id)
        assertEquals(PhoneApprovalState.PENDING, approval.state)
        assertEquals("tap", approval.command.type)
        assertEquals("Enviar", approval.command.args["text"]?.jsonPrimitive?.content)
        assertEquals(PhoneApprovalReason.RISKY_TEXT, approval.reason)
        assertEquals("Enviar", approval.target)
        assertEquals("WhatsApp", approval.foregroundApp?.label)
        assertEquals("com.whatsapp", approval.foregroundApp?.packageName)
        assertEquals("phc-1a2b3c4d5e6f", approval.commandId)
        assertNull(approval.decidedAt)
        assertNull(approval.resultCommandId)
    }

    @Test
    fun `a minimal approval decodes with defaults`() {
        val approval = json.decodeFromString(
            PhoneApproval.serializer(),
            """{"id":"apr-123456789abc"}""",
        )

        assertEquals("apr-123456789abc", approval.id)
        assertEquals(PhoneApprovalState.PENDING, approval.state)
        assertEquals("", approval.command.type)
        assertTrue(approval.command.args.isEmpty())
        assertNull(approval.target)
        assertNull(approval.foregroundApp)
        assertEquals("", approval.expiresAt)
    }

    @Test
    fun `a null foreground app and target decode`() {
        val approval = json.decodeFromString(
            PhoneApproval.serializer(),
            """{"id":"apr-123456789abc","state":"pending","foregroundApp":null,"target":null}""",
        )

        assertNull(approval.foregroundApp)
        assertNull(approval.target)
    }

    @Test
    fun `the approvals envelope decodes and tolerates a missing list`() {
        val full = json.decodeFromString(
            PhoneApprovalsResponse.serializer(),
            """{"approvals":[{"id":"apr-a"},{"id":"apr-b"}]}""",
        )
        assertEquals(listOf("apr-a", "apr-b"), full.approvals.map { it.id })

        val empty = json.decodeFromString(PhoneApprovalsResponse.serializer(), "{}")
        assertTrue(empty.approvals.isEmpty())
    }

    @Test
    fun `an unknown server field is ignored`() {
        val approval = json.decodeFromString(
            PhoneApproval.serializer(),
            """{"id":"apr-a","futureField":"ignored"}""",
        )

        assertEquals("apr-a", approval.id)
    }

    @Test
    fun `the typed decision result exposes the conflict codes`() {
        assertEquals("EXPIRED", PhoneApprovalDecisionResult.CODE_EXPIRED)
        assertEquals("ALREADY_DECIDED", PhoneApprovalDecisionResult.CODE_ALREADY_DECIDED)
    }
}
