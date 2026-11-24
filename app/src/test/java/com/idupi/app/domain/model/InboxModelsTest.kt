package com.idupi.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire contract for the phone-inbox models (phone-inbox T2).
 *
 * The JSON below is copied from what `idupi-server/lib/inbox-store.mjs`
 * actually stores, including a document from a hypothetical newer server
 * (unknown key) and the pre-analysis state (null importance/error). Decoding
 * must not throw in either case: phone and PC update independently.
 */
class InboxModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `decodes a ready item with every analysis field`() {
        val payload = """
            {
              "id": "itm-2f1a9c",
              "app": "whatsapp",
              "appLabel": "WhatsApp",
              "packageName": "com.whatsapp",
              "sender": "Carlos Mendez",
              "conversation": "Carlos Mendez",
              "text": "Se rompio el compresor del taller, necesitamos el service urgente",
              "postedAt": "2026-11-24T12:30:00.000Z",
              "receivedAt": "2026-11-24T12:30:01.000Z",
              "notificationKey": "0|com.whatsapp|1|null|10123",
              "status": "ready",
              "importance": "high",
              "category": "trabajo",
              "summary": "El compresor del taller se rompio y piden service urgente.",
              "suggestedReply": "Hola Carlos, recibi el mensaje. Coordino el service hoy mismo.",
              "error": null,
              "state": "open",
              "updatedAt": "2026-11-24T12:30:20.000Z"
            }
        """.trimIndent()

        val item = json.decodeFromString<InboxItem>(payload)

        assertEquals("itm-2f1a9c", item.id)
        assertEquals("whatsapp", item.app)
        assertEquals("WhatsApp", item.appLabel)
        assertEquals("com.whatsapp", item.packageName)
        assertEquals("Carlos Mendez", item.sender)
        assertEquals("Se rompio el compresor del taller, necesitamos el service urgente", item.text)
        assertEquals("2026-11-24T12:30:00.000Z", item.postedAt)
        assertEquals("0|com.whatsapp|1|null|10123", item.notificationKey)
        assertEquals(InboxItemStatus.READY, item.status)
        assertEquals(InboxImportance.HIGH, item.importance)
        assertEquals("trabajo", item.category)
        assertEquals("El compresor del taller se rompio y piden service urgente.", item.summary)
        assertEquals("Hola Carlos, recibi el mensaje. Coordino el service hoy mismo.", item.suggestedReply)
        assertNull(item.error)
        assertEquals(InboxItemState.OPEN, item.state)
    }

    @Test
    fun `decodes an analyzing item with nulls, missing fields and unknown keys`() {
        val payload = """
            {
              "id": "itm-b001",
              "app": "gmail",
              "text": "Mantenimiento preventivo del equipo",
              "notificationKey": "gmail:42",
              "status": "analyzing",
              "importance": null,
              "error": null,
              "state": "open",
              "futureField": {"ignored": true}
            }
        """.trimIndent()

        val item = json.decodeFromString<InboxItem>(payload)

        assertEquals("itm-b001", item.id)
        assertEquals("gmail", item.app)
        assertEquals("", item.appLabel)
        assertNull(item.postedAt)
        assertEquals("", item.sender)
        assertEquals("", item.conversation)
        assertEquals(InboxItemStatus.ANALYZING, item.status)
        assertNull(item.importance)
        assertEquals("", item.summary)
        assertNull(item.error)
    }

    @Test
    fun `decodes the items envelope as returned by the server`() {
        val payload = """
            {"items": [
              {"id": "a", "app": "outlook", "text": "uno", "notificationKey": "k1"},
              {"id": "b", "app": "whatsapp_business", "text": "dos", "notificationKey": "k2"}
            ]}
        """.trimIndent()

        val items = json.decodeFromString<InboxItemsEnvelope>(payload).items

        assertEquals(2, items.size)
        assertEquals("outlook", items[0].app)
        assertEquals("whatsapp_business", items[1].app)
    }

    @Test
    fun `encodes the message request with the server's exact field names`() {
        val request = InboxMessageRequest(
            app = "whatsapp",
            packageName = "com.whatsapp",
            sender = "Carlos",
            conversation = "Taller",
            text = "Hola",
            postedAt = "2026-11-24T12:30:00Z",
            notificationKey = "key-1"
        )

        assertEquals(
            """{"app":"whatsapp","packageName":"com.whatsapp","sender":"Carlos",""" +
                """"conversation":"Taller","text":"Hola","postedAt":"2026-11-24T12:30:00Z",""" +
                """"notificationKey":"key-1"}""",
            json.encodeToString(InboxMessageRequest.serializer(), request)
        )
    }

    @Test
    fun `encodes the optional appLabel only when the capture carried one`() {
        val labelled = InboxMessageRequest(
            app = "org.telegram.messenger",
            appLabel = "Telegram",
            packageName = "org.telegram.messenger",
            sender = "Ana",
            conversation = "Taller",
            text = "Hola",
            postedAt = "2026-11-24T12:30:00Z",
            notificationKey = "key-2"
        )

        assertEquals(
            """{"app":"org.telegram.messenger","appLabel":"Telegram",""" +
                """"packageName":"org.telegram.messenger","sender":"Ana",""" +
                """"conversation":"Taller","text":"Hola",""" +
                """"postedAt":"2026-11-24T12:30:00Z","notificationKey":"key-2"}""",
            json.encodeToString(InboxMessageRequest.serializer(), labelled)
        )
    }

    @Test
    fun `decodes the config with the server-provided agents and efforts`() {
        val payload = """
            {
              "agent": "opencode",
              "model": "opencode-go/deepseek-v4.1-flash",
              "effort": "max",
              "agents": ["opencode", "claude", "codex", "pi"],
              "efforts": ["low", "medium", "high", "max"]
            }
        """.trimIndent()

        val config = json.decodeFromString<InboxConfig>(payload)

        assertEquals("opencode", config.agent)
        assertEquals("opencode-go/deepseek-v4.1-flash", config.model)
        assertEquals("max", config.effort)
        assertEquals(listOf("opencode", "claude", "codex", "pi"), config.agents)
        assertEquals(listOf("low", "medium", "high", "max"), config.efforts)
    }

    @Test
    fun `an unknown state or importance stays as text instead of failing`() {
        val item = json.decodeFromString<InboxItem>(
            """{"id":"x","state":"archived","importance":"critical","notificationKey":"k"}"""
        )

        assertEquals("archived", item.state)
        assertEquals("critical", item.importance)
    }
}

/** Mirrors the private envelope in RealIduPiClient so the shape is pinned. */
@Serializable
private data class InboxItemsEnvelope(val items: List<InboxItem> = emptyList())
