package com.idupi.app.data.phone.control

import com.idupi.app.data.phone.shell.ShellMode
import com.idupi.app.data.phone.shell.ShellOperation
import com.idupi.app.data.phone.shell.ShellTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shell` command parsing (phone-shell T4). The T4 contract is exact: unknown
 * actions or keys, missing/extra arguments, invalid packages and protected
 * packages are refused instead of normalized.
 */
class ShellCommandParserTest {

    private fun shell(args: String): PhoneCommand.Shell =
        valid("""{"id":"phc-1","type":"shell","args":{$args}}""") as PhoneCommand.Shell

    private fun valid(raw: String): PhoneCommand {
        val result = CommandParser.parse(raw)
        assertTrue("expected valid, got $result", result is CommandParseResult.Valid)
        return (result as CommandParseResult.Valid).command
    }

    private fun invalid(args: String): CommandParseResult.Invalid {
        val result = CommandParser.parse("""{"id":"phc-1","type":"shell","args":{$args}}""")
        assertTrue("expected invalid, got $result", result is CommandParseResult.Invalid)
        return result as CommandParseResult.Invalid
    }

    @Test
    fun `parses info topics`() {
        assertEquals(
            ShellOperation.Info(ShellTopic.STORAGE),
            shell(""""action":"info","topic":"storage"""").operation,
        )
        assertEquals(
            ShellOperation.Info(ShellTopic.MEMORY),
            shell(""""action":"info","topic":"memory"""").operation,
        )
        assertEquals(
            ShellOperation.Info(ShellTopic.BATTERY),
            shell(""""action":"info","topic":"battery"""").operation,
        )
        assertEquals(
            ShellOperation.Info(ShellTopic.PACKAGES),
            shell(""""action":"info","topic":"packages"""").operation,
        )
    }

    @Test
    fun `parses trim-caches with no arguments`() {
        assertEquals(
            ShellOperation.TrimCaches,
            shell(""""action":"trim-caches"""").operation,
        )
    }

    @Test
    fun `parses force-stop and the package actions`() {
        assertEquals(
            ShellOperation.ForceStop("com.example.app"),
            shell(""""action":"force-stop","package":"com.example.app"""").operation,
        )
        assertEquals(
            ShellOperation.Disable("com.example.app"),
            shell(""""action":"disable","package":"com.example.app"""").operation,
        )
        assertEquals(
            ShellOperation.Enable("com.example.app"),
            shell(""""action":"enable","package":"com.example.app"""").operation,
        )
    }

    @Test
    fun `parses both background modes`() {
        assertEquals(
            ShellOperation.Background("com.example.app", ShellMode.ALLOW),
            shell(""""action":"background","package":"com.example.app","mode":"allow"""").operation,
        )
        assertEquals(
            ShellOperation.Background("com.example.app", ShellMode.IGNORE),
            shell(""""action":"background","package":"com.example.app","mode":"ignore"""").operation,
        )
    }

    @Test
    fun `parses the approval hints`() {
        val command = shell(
            """"action":"disable","package":"com.example.app",""" +
                """"approvalId":"apr-123456789abc","approvalReason":"destructive",""" +
                """"ownerTurnId":"pat-0123456789ab","approvalCategory":"destructive",""" +
                """"approvalExpiresAt":"2026-11-27T12:10:00.000Z""""
        )
        assertEquals(
            CommandApproval(
                approvalId = "apr-123456789abc",
                approvedTarget = null,
                reason = "destructive",
                ownerTurnId = "pat-0123456789ab",
                category = "destructive",
                expiresAt = "2026-11-27T12:10:00.000Z",
            ),
            command.approval,
        )
    }

    @Test
    fun `a free shell command carries no approval`() {
        assertNull(shell(""""action":"trim-caches"""").approval)
    }

    @Test
    fun `rejects unknown actions and keys`() {
        assertEquals("acción inválida", invalid(""""action":"reboot"""").error)
        assertEquals("falta la acción", invalid(""""topic":"storage"""").error)
        assertEquals("acción inválida", invalid(""""action":"INFO"""").error)
        assertTrue(
            "got ${invalid(""""action":"trim-caches","extra":true""").error}",
            invalid(""""action":"trim-caches","extra":true""").error
                .contains("argumento desconocido"),
        )
        assertTrue(
            "got ${invalid(""""action":"force-stop","package":"com.a.b","shell":"id"""").error}",
            invalid(""""action":"force-stop","package":"com.a.b","shell":"id"""").error
                .contains("argumento desconocido"),
        )
    }

    @Test
    fun `rejects arguments that do not belong to the action`() {
        assertEquals(
            "argumento no permitido",
            invalid(""""action":"info","topic":"storage","package":"com.a.b"""").error,
        )
        assertEquals(
            "argumento no permitido",
            invalid(""""action":"trim-caches","package":"com.a.b"""").error,
        )
        assertEquals(
            "argumento no permitido",
            invalid(""""action":"force-stop","package":"com.a.b","mode":"allow"""").error,
        )
        assertEquals(
            "argumento no permitido",
            invalid(""""action":"background","package":"com.a.b","mode":"allow","topic":"storage"""").error,
        )
    }

    @Test
    fun `rejects missing action arguments`() {
        assertEquals("tema inválido", invalid(""""action":"info"""").error)
        assertEquals("tema inválido", invalid(""""action":"info","topic":"wifi"""").error)
        assertEquals("falta el paquete", invalid(""""action":"force-stop"""").error)
        assertEquals("modo inválido", invalid(""""action":"background","package":"com.a.b"""").error)
        assertEquals(
            "modo inválido",
            invalid(""""action":"background","package":"com.a.b","mode":"deny"""").error,
        )
    }

    @Test
    fun `rejects invalid package ids`() {
        listOf(
            "whatsapp",
            "com..app",
            ".com.app",
            "com.app.",
            "com.2app",
            "com._app",
            "-com.app",
            "com.app/x",
            "com app",
            "com.app\n",
        ).forEach { pkg ->
            assertEquals(
                "package $pkg",
                "paquete inválido",
                invalid(""""action":"disable","package":"${jsonPackage(pkg)}"""").error,
            )
        }
    }

    /** Escapes a value so the test sends a string with those characters. */
    private fun jsonPackage(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    @Test
    fun `rejects a package longer than 200 characters`() {
        val pkg = "com." + "a".repeat(200)
        assertEquals(
            "paquete inválido",
            invalid(""""action":"disable","package":"$pkg"""").error,
        )
    }

    @Test
    fun `rejects protected packages for stop, background and disable`() {
        val protected = listOf(
            "com.idupi.app",
            "com.idupi.shell",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.providers.settings",
            "com.google.android.gms",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
        )
        protected.forEach { pkg ->
            assertEquals(
                "$pkg force-stop",
                "paquete protegido",
                invalid(""""action":"force-stop","package":"$pkg"""").error,
            )
            assertEquals(
                "$pkg background",
                "paquete protegido",
                invalid(""""action":"background","package":"$pkg","mode":"ignore"""").error,
            )
            assertEquals(
                "$pkg disable",
                "paquete protegido",
                invalid(""""action":"disable","package":"$pkg"""").error,
            )
        }
    }

    @Test
    fun `enable stays available for protected package ids`() {
        assertEquals(
            ShellOperation.Enable("com.idupi.app"),
            shell(""""action":"enable","package":"com.idupi.app"""").operation,
        )
    }

    @Test
    fun `parses scan with no arguments`() {
        assertEquals(ShellOperation.Scan, shell(""""action":"scan"""").operation)
        assertEquals(
            "argumento desconocido: package",
            invalid(""""action":"scan","package":"com.example.app"""").error,
        )
    }

    @Test
    fun `parses uninstall and refuses protected packages`() {
        assertEquals(
            ShellOperation.Uninstall("com.example.app"),
            shell(""""action":"uninstall","package":"com.example.app"""").operation,
        )
        assertEquals(
            "paquete protegido",
            invalid(""""action":"uninstall","package":"com.idupi.app"""").error,
        )
        assertEquals(
            "paquete inválido",
            invalid(""""action":"uninstall","package":"whatsapp"""").error,
        )
        assertEquals("falta el paquete", invalid(""""action":"uninstall"""").error)
    }

    @Test
    fun `parses a batch of typed steps in order`() {
        val parsed = shell(
            """"action":"batch","steps":[""" +
                """{"action":"trim-caches"},""" +
                """{"action":"force-stop","package":"com.example.a"},""" +
                """{"action":"background","package":"com.example.b","mode":"ignore"},""" +
                """{"action":"disable","package":"com.example.c"},""" +
                """{"action":"enable","package":"com.example.d"},""" +
                """{"action":"uninstall","package":"com.example.e"}""" +
                """]"""
        ).operation

        assertEquals(
            ShellOperation.Batch(
                listOf(
                    ShellOperation.TrimCaches,
                    ShellOperation.ForceStop("com.example.a"),
                    ShellOperation.Background("com.example.b", ShellMode.IGNORE),
                    ShellOperation.Disable("com.example.c"),
                    ShellOperation.Enable("com.example.d"),
                    ShellOperation.Uninstall("com.example.e"),
                )
            ),
            parsed,
        )
    }

    @Test
    fun `a batch accepts one and thirty steps`() {
        assertEquals(
            ShellOperation.Batch(listOf(ShellOperation.TrimCaches)),
            shell(""""action":"batch","steps":[{"action":"trim-caches"}]""").operation,
        )

        val steps = (1..30).joinToString(",") { index ->
            """{"action":"force-stop","package":"com.example.app$index"}"""
        }
        val parsed = shell(""""action":"batch","steps":[$steps]""").operation
        assertEquals(30, (parsed as ShellOperation.Batch).steps.size)
    }

    @Test
    fun `a batch rejects empty, oversized and malformed step lists`() {
        assertEquals(
            "el plan debe tener entre 1 y 30 pasos",
            invalid(""""action":"batch","steps":[]""").error,
        )
        val tooMany = (1..31).joinToString(",") { index ->
            """{"action":"force-stop","package":"com.example.app$index"}"""
        }
        assertEquals(
            "el plan debe tener entre 1 y 30 pasos",
            invalid(""""action":"batch","steps":[$tooMany]""").error,
        )
        assertEquals("steps inválidos", invalid(""""action":"batch"""").error)
        assertEquals(
            "paso 1 inválido",
            invalid(""""action":"batch","steps":["id"]""").error,
        )
        assertEquals(
            "argumento desconocido: package",
            invalid(""""action":"batch","steps":[],"package":"com.example.app"""").error,
        )
    }

    @Test
    fun `a batch refuses free and nested actions`() {
        listOf("info", "scan", "batch").forEach { action ->
            assertEquals(
                "paso 1: acción no permitida en un plan",
                invalid(""""action":"batch","steps":[{"action":"$action"}]""").error,
            )
        }
    }

    @Test
    fun `a batch refuses duplicate steps`() {
        assertEquals(
            "paso 2: paso repetido",
            invalid(
                """"action":"batch","steps":[""" +
                    """{"action":"force-stop","package":"com.example.app"},""" +
                    """{"action":"force-stop","package":"com.example.app"}""" +
                    """]"""
            ).error,
        )
        assertEquals(
            "paso 2: paso repetido",
            invalid(
                """"action":"batch","steps":[""" +
                    """{"action":"trim-caches"},""" +
                    """{"action":"trim-caches"}""" +
                    """]"""
            ).error,
        )
        // A different mode is a different step.
        assertEquals(
            ShellOperation.Batch(
                listOf(
                    ShellOperation.Background("com.example.app", ShellMode.ALLOW),
                    ShellOperation.Background("com.example.app", ShellMode.IGNORE),
                )
            ),
            shell(
                """"action":"batch","steps":[""" +
                    """{"action":"background","package":"com.example.app","mode":"allow"},""" +
                    """{"action":"background","package":"com.example.app","mode":"ignore"}""" +
                    """]"""
            ).operation,
        )
    }

    @Test
    fun `a batch re-validates every step`() {
        assertEquals(
            "paso 1: argumento desconocido: topic",
            invalid(
                """"action":"batch","steps":[{"action":"trim-caches","topic":"storage"}]"""
            ).error,
        )
        assertEquals(
            "paso 1: argumento desconocido: approvalId",
            invalid(
                """"action":"batch","steps":[""" +
                    """{"action":"disable","package":"com.example.app","approvalId":"apr-1"}""" +
                    """]"""
            ).error,
        )
        assertEquals(
            "paso 2: paquete protegido",
            invalid(
                """"action":"batch","steps":[""" +
                    """{"action":"force-stop","package":"com.example.a"},""" +
                    """{"action":"uninstall","package":"com.idupi.app"}""" +
                    """]"""
            ).error,
        )
        assertEquals(
            "paso 1: paquete inválido",
            invalid(
                """"action":"batch","steps":[{"action":"disable","package":"whatsapp"}]"""
            ).error,
        )
        assertEquals(
            "paso 1: falta el paquete",
            invalid(""""action":"batch","steps":[{"action":"uninstall"}]""").error,
        )
    }
}
