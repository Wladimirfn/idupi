package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command validation (phone-agent T3). Mirror of the server rules in
 * `idupi-server/lib/phone-store.mjs`: the phone must accept exactly what the
 * server normalizes and reject the rest with a short reason.
 */
class CommandParserTest {

    private fun parse(raw: String) = CommandParser.parse(raw)

    private fun valid(raw: String): PhoneCommand {
        val result = parse(raw)
        assertTrue("expected valid, got $result", result is CommandParseResult.Valid)
        return (result as CommandParseResult.Valid).command
    }

    private fun invalid(raw: String): CommandParseResult.Invalid {
        val result = parse(raw)
        assertTrue("expected invalid, got $result", result is CommandParseResult.Invalid)
        return result as CommandParseResult.Invalid
    }

    @Test
    fun `parses screen and status with empty args`() {
        assertEquals(
            PhoneCommand.Screen("phc-1", null),
            valid("""{"id":"phc-1","type":"screen","args":{}}"""),
        )
        assertEquals(
            PhoneCommand.ReportStatus("phc-2", "2026-11-24T12:01:00.000Z"),
            valid("""{"id":"phc-2","type":"status","expiresAt":"2026-11-24T12:01:00.000Z"}"""),
        )
    }

    @Test
    fun `parses tap targets by text, id and coordinates`() {
        assertEquals(
            PhoneCommand.Tap("phc-1", null, NodeTarget.Text("Enviar")),
            valid("""{"id":"phc-1","type":"tap","args":{"text":"Enviar"}}"""),
        )
        assertEquals(
            PhoneCommand.Tap("phc-1", null, NodeTarget.Id("com.whatsapp:id/send")),
            valid("""{"id":"phc-1","type":"tap","args":{"id":"com.whatsapp:id/send"}}"""),
        )
        assertEquals(
            PhoneCommand.Tap("phc-1", null, NodeTarget.Point(540, 2100)),
            valid("""{"id":"phc-1","type":"tap","args":{"x":540,"y":2100}}"""),
        )
        assertEquals(
            PhoneCommand.LongPress("phc-1", null, NodeTarget.Text("Mensaje")),
            valid("""{"id":"phc-1","type":"long_press","args":{"text":"Mensaje"}}"""),
        )
    }

    @Test
    fun `rejects bad tap targets`() {
        assertTrue(invalid("""{"id":"phc-1","type":"tap","args":{}}""").error.contains("text, id"))
        assertTrue(
            invalid("""{"id":"phc-1","type":"tap","args":{"text":"a","id":"b"}}""")
                .error.contains("text, id"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"tap","args":{"x":1}}""")
                .error.contains("x e y"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"tap","args":{"text":"a","extra":1}}""")
                .error.contains("argumento desconocido"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"tap","args":{"x":-1,"y":2}}""")
                .error.contains("coordenadas"),
        )
    }

    @Test
    fun `parses type with and without submit`() {
        assertEquals(
            PhoneCommand.TypeText("phc-1", null, "hola", submit = false),
            valid("""{"id":"phc-1","type":"type","args":{"text":"hola"}}"""),
        )
        assertEquals(
            PhoneCommand.TypeText("phc-1", null, "hola", submit = true),
            valid("""{"id":"phc-1","type":"type","args":{"text":"hola","submit":true}}"""),
        )
    }

    @Test
    fun `rejects bad type args`() {
        assertTrue(invalid("""{"id":"phc-1","type":"type","args":{}}""").error.contains("text"))
        assertTrue(
            invalid("""{"id":"phc-1","type":"type","args":{"text":" "}}""").error.contains("text"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"type","args":{"text":"hola","submit":"yes"}}""")
                .error.contains("submit"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"type","args":{"text":"hola","other":true}}""")
                .error.contains("argumento desconocido"),
        )
    }

    @Test
    fun `parses every scroll direction`() {
        assertEquals(
            PhoneCommand.Scroll("phc-1", null, ScrollDirection.DOWN),
            valid("""{"id":"phc-1","type":"scroll","args":{"direction":"down"}}"""),
        )
        assertEquals(
            ScrollDirection.UP,
            (valid("""{"id":"phc-1","type":"scroll","args":{"direction":"up"}}""") as PhoneCommand.Scroll).direction,
        )
        assertEquals(
            ScrollDirection.LEFT,
            (valid("""{"id":"phc-1","type":"scroll","args":{"direction":"left"}}""") as PhoneCommand.Scroll).direction,
        )
        assertEquals(
            ScrollDirection.RIGHT,
            (valid("""{"id":"phc-1","type":"scroll","args":{"direction":"right"}}""") as PhoneCommand.Scroll).direction,
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"scroll","args":{"direction":"diagonal"}}""")
                .error.contains("dirección"),
        )
    }

    @Test
    fun `parses every global action`() {
        GlobalAction.values().forEach { action ->
            val parsed = valid(
                """{"id":"phc-1","type":"global","args":{"action":"${action.name.lowercase()}"}}"""
            )
            assertEquals(PhoneCommand.Global("phc-1", null, action), parsed)
        }
        assertTrue(
            invalid("""{"id":"phc-1","type":"global","args":{"action":"lock"}}""")
                .error.contains("acción"),
        )
    }

    @Test
    fun `parses open and rejects a blank app`() {
        assertEquals(
            PhoneCommand.OpenApp("phc-1", null, "whatsapp"),
            valid("""{"id":"phc-1","type":"open","args":{"app":"whatsapp"}}"""),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"open","args":{"app":""}}""").error.contains("app"),
        )
    }

    @Test
    fun `waits are bounded integers`() {
        assertEquals(
            PhoneCommand.Wait("phc-1", null, 0),
            valid("""{"id":"phc-1","type":"wait","args":{"ms":0}}"""),
        )
        assertEquals(
            PhoneCommand.Wait("phc-1", null, 10_000),
            valid("""{"id":"phc-1","type":"wait","args":{"ms":10000}}"""),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"wait","args":{"ms":10001}}""").error.contains("ms"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"wait","args":{"ms":-1}}""").error.contains("ms"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"wait","args":{"ms":"500"}}""").error.contains("ms"),
        )
    }

    @Test
    fun `rejects unknown types and malformed wire payloads`() {
        assertEquals("tipo desconocido", invalid("""{"id":"phc-1","type":"shake"}""").error)
        assertTrue(invalid("""{"id":"phc-1"}""").error.contains("tipo"))
        assertTrue(invalid("""{"type":"screen"}""").error.contains("id"))
        assertTrue(invalid("not json").commandId == null)
        assertTrue(invalid("""{"id":"phc-1","type":"screen","args":[]}""").error.contains("args"))
    }

    @Test
    fun `ignores server-side fields around the command`() {
        val parsed = valid(
            """
            {"id":"phc-1","type":"screen","args":{},"state":"delivered",
             "createdAt":"2026-11-24T12:00:00.000Z","deliveredAt":null}
            """.trimIndent()
        )
        assertEquals(PhoneCommand.Screen("phc-1", null), parsed)
    }

    @Test
    fun `reports the id of an invalid command when readable`() {
        val result = invalid("""{"id":"phc-123456789abc","type":"nope","args":{}}""")
        assertEquals("phc-123456789abc", result.commandId)
        assertEquals("nope", result.type)
    }
}
