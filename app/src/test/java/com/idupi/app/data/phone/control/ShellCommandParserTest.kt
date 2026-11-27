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
}
