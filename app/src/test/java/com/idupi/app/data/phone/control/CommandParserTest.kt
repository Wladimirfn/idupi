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
    fun `parses every settings screen from the fixed allowlist`() {
        assertEquals(
            PhoneCommand.Settings("phc-1", null, SettingsScreen.DEVICE_CARE),
            valid("""{"id":"phc-1","type":"settings","args":{"screen":"device-care"}}"""),
        )
        assertEquals(
            PhoneCommand.Settings("phc-1", null, SettingsScreen.STORAGE),
            valid("""{"id":"phc-1","type":"settings","args":{"screen":"storage"}}"""),
        )
        assertEquals(
            PhoneCommand.Settings("phc-1", null, SettingsScreen.BATTERY),
            valid("""{"id":"phc-1","type":"settings","args":{"screen":"battery"}}"""),
        )
        assertEquals(
            PhoneCommand.Settings("phc-1", null, SettingsScreen.APPS),
            valid("""{"id":"phc-1","type":"settings","args":{"screen":"apps"}}"""),
        )
        assertEquals(
            PhoneCommand.Settings("phc-1", null, SettingsScreen.APP, "com.whatsapp"),
            valid(
                """{"id":"phc-1","type":"settings","args":{"screen":"app","package":"com.whatsapp"}}"""
            ),
        )
    }

    @Test
    fun `rejects settings outside the fixed allowlist`() {
        assertTrue(
            invalid("""{"id":"phc-1","type":"settings","args":{}}""").error.contains("screen"),
        )
        assertTrue(
            invalid("""{"id":"phc-1","type":"settings","args":{"screen":"wifi"}}""")
                .error.contains("screen"),
        )
        // A free-form intent is exactly what the allowlist forbids.
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"settings",
                    "args":{"screen":"device-care","intent":"android.settings.SETTINGS"}}"""
            ).error.contains("argumento desconocido"),
        )
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"settings",
                    "args":{"screen":"storage","package":"com.whatsapp"}}"""
            ).error.contains("package"),
        )
        // The package must be a package id, and only `screen: app` takes one.
        assertTrue(
            invalid("""{"id":"phc-1","type":"settings","args":{"screen":"app"}}""")
                .error.contains("package"),
        )
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"settings","args":{"screen":"app","package":"whatsapp"}}"""
            ).error.contains("package"),
        )
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"settings","args":{"screen":"app","package":"com..x"}}"""
            ).error.contains("package"),
        )
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"settings","args":{"screen":"app","package":"-com.x"}}"""
            ).error.contains("package"),
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

    @Test
    fun `an approved tap exposes the approval args`() {
        val parsed = valid(
            """
            {"id":"phc-2","type":"tap",
             "args":{"text":"Enviar","approvalId":"apr-123456789abc",
                     "approvedTarget":"Enviar","approvalReason":"risky_text"},
             "approvalId":"apr-123456789abc","approvalReason":"risky_text",
             "approvalTarget":"Enviar","approvalExpiresAt":"2026-11-24T12:10:00.000Z"}
            """.trimIndent()
        ) as PhoneCommand.Tap

        assertEquals(
            CommandApproval(
                approvalId = "apr-123456789abc",
                approvedTarget = "Enviar",
                reason = "risky_text",
                expiresAt = "2026-11-24T12:10:00.000Z",
            ),
            parsed.approval,
        )
    }

    @Test
    fun `an owner-request approval exposes the turn, the category and the expiry`() {
        val parsed = valid(
            """
            {"id":"phc-6","type":"type",
             "args":{"text":"hola","submit":true,"approvalId":"apr-123456789abc",
                     "approvedTarget":null,"approvalReason":"type_submit",
                     "ownerTurnId":"pat-0123456789ab","approvalCategory":"message"},
             "approvalId":"apr-123456789abc",
             "approvalExpiresAt":"2026-11-26T12:10:00.000Z",
             "approvalReason":"type_submit","approvalTarget":null,
             "ownerTurnId":"pat-0123456789ab","approvalCategory":"message"}
            """.trimIndent()
        ) as PhoneCommand.TypeText

        assertEquals(
            CommandApproval(
                approvalId = "apr-123456789abc",
                approvedTarget = null,
                reason = "type_submit",
                ownerTurnId = "pat-0123456789ab",
                category = "message",
                expiresAt = "2026-11-26T12:10:00.000Z",
            ),
            parsed.approval,
        )
    }

    @Test
    fun `approval fields missing from the wire default to null`() {
        val parsed = valid(
            """
            {"id":"phc-7","type":"tap",
             "args":{"text":"Enviar","approvalId":"apr-123456789abc",
                     "approvedTarget":"Enviar","approvalReason":"risky_text"}}
            """.trimIndent()
        ) as PhoneCommand.Tap
        val approval = parsed.approval ?: error("expected an approval")

        assertEquals("apr-123456789abc", approval.approvalId)
        assertEquals(null, approval.ownerTurnId)
        assertEquals(null, approval.category)
        assertEquals(null, approval.expiresAt)
    }

    @Test
    fun `an approved type with submit exposes a null target`() {
        val parsed = valid(
            """
            {"id":"phc-3","type":"type",
             "args":{"text":"hola","submit":true,"approvalId":"apr-123456789abc",
                     "approvedTarget":null,"approvalReason":"type_submit"},
             "approvalId":"apr-123456789abc","approvalReason":"type_submit",
             "approvalTarget":null}
            """.trimIndent()
        ) as PhoneCommand.TypeText

        assertEquals(true, parsed.submit)
        assertEquals(CommandApproval("apr-123456789abc", null, "type_submit"), parsed.approval)
    }

    @Test
    fun `the top-level approval fields are read when args omits them`() {
        val parsed = valid(
            """
            {"id":"phc-4","type":"tap","args":{"text":"Enviar"},
             "approvalId":"apr-123456789abc","approvalReason":"risky_text",
             "approvalTarget":"Enviar"}
            """.trimIndent()
        ) as PhoneCommand.Tap

        assertEquals(
            CommandApproval("apr-123456789abc", "Enviar", "risky_text"),
            parsed.approval,
        )
    }

    @Test
    fun `a free command carries no approval`() {
        val parsed = valid("""{"id":"phc-5","type":"tap","args":{"text":"Chats"}}""")
            as PhoneCommand.Tap

        assertEquals(null, parsed.approval)
    }

    @Test
    fun `an unknown argument is still rejected next to approval args`() {
        assertTrue(
            invalid(
                """{"id":"phc-1","type":"tap","args":{"text":"Enviar","approvalId":"apr-1","evil":1}}"""
            ).error.contains("argumento desconocido"),
        )
    }
}
