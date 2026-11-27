package com.idupi.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Helper-side allowlist (phone-shell T4). The app and the server validate the
 * same contract; this is the execution boundary that a forged provider call
 * cannot bypass.
 */
class ShellRequestValidatorTest {

    private fun valid(
        action: String,
        packageName: String? = null,
        mode: String? = null,
        topic: String? = null,
    ): ShellRequest {
        val result = ShellRequestValidator.validate(action, packageName, mode, topic)
        assertTrue("expected valid for $action, got $result", result is ShellValidation.Valid)
        return (result as ShellValidation.Valid).request
    }

    private fun invalid(
        action: String?,
        packageName: String? = null,
        mode: String? = null,
        topic: String? = null,
    ): String {
        val result = ShellRequestValidator.validate(action, packageName, mode, topic)
        assertTrue("expected invalid for $action, got $result", result is ShellValidation.Invalid)
        return (result as ShellValidation.Invalid).message
    }

    @Test
    fun `accepts every action with exactly its arguments`() {
        assertEquals(ShellRequest(ShellAction.TRIM_CACHES), valid("trim-caches"))
        assertEquals(
            ShellRequest(ShellAction.FORCE_STOP, packageName = "com.example.app"),
            valid("force-stop", "com.example.app"),
        )
        assertEquals(
            ShellRequest(
                ShellAction.BACKGROUND,
                packageName = "com.example.app",
                mode = ShellMode.IGNORE,
            ),
            valid("background", "com.example.app", mode = "ignore"),
        )
        assertEquals(
            ShellRequest(ShellAction.DISABLE, packageName = "com.example.app"),
            valid("disable", "com.example.app"),
        )
        assertEquals(
            ShellRequest(ShellAction.ENABLE, packageName = "com.example.app"),
            valid("enable", "com.example.app"),
        )
        assertEquals(
            ShellRequest(ShellAction.INFO, topic = ShellTopic.MEMORY),
            valid("info", topic = "memory"),
        )
    }

    @Test
    fun `rejects unknown actions`() {
        assertEquals("acción inválida", invalid("reboot"))
        assertEquals("acción inválida", invalid(null))
        assertEquals("acción inválida", invalid(""))
    }

    @Test
    fun `info requires a known topic and nothing else`() {
        assertEquals("tema inválido", invalid("info", topic = "wifi"))
        assertEquals("tema inválido", invalid("info"))
        assertEquals(
            "argumento no permitido",
            invalid("info", packageName = "com.example.app", topic = "storage"),
        )
        assertEquals("argumento no permitido", invalid("info", mode = "allow", topic = "storage"))
    }

    @Test
    fun `trim-caches takes no other argument`() {
        assertEquals("argumento no permitido", invalid("trim-caches", packageName = "com.example.app"))
        assertEquals("argumento no permitido", invalid("trim-caches", mode = "allow"))
        assertEquals("argumento no permitido", invalid("trim-caches", topic = "storage"))
    }

    @Test
    fun `package actions require a valid package`() {
        assertEquals("falta el paquete", invalid("force-stop"))
        assertEquals("falta el paquete", invalid("disable"))
        assertEquals("falta el paquete", invalid("enable"))
        assertEquals("paquete inválido", invalid("force-stop", "whatsapp"))
        assertEquals("paquete inválido", invalid("force-stop", "com..app"))
        assertEquals("paquete inválido", invalid("force-stop", "com.2app"))
        assertEquals("paquete inválido", invalid("disable", "-com.app"))
        assertEquals("paquete inválido", invalid("disable", "com.app/x"))
        assertEquals("paquete inválido", invalid("disable", "com.app\n"))
    }

    @Test
    fun `background requires allow or ignore`() {
        assertEquals("modo inválido", invalid("background", "com.example.app"))
        assertEquals("modo inválido", invalid("background", "com.example.app", mode = "deny"))
        assertEquals(
            ShellRequest(
                ShellAction.BACKGROUND,
                packageName = "com.example.app",
                mode = ShellMode.ALLOW,
            ),
            valid("background", "com.example.app", mode = "allow"),
        )
    }

    @Test
    fun `protected packages are refused for stop, background and disable`() {
        ShellRequestValidator.PROTECTED_PACKAGES.forEach { pkg ->
            assertEquals("paquete protegido", invalid("force-stop", pkg))
            assertEquals("paquete protegido", invalid("background", pkg, mode = "ignore"))
            assertEquals("paquete protegido", invalid("disable", pkg))
            // enable is the undo and stays allowed for real package ids.
            if (pkg == "android") {
                // One segment: not a valid package id even for enable.
                assertEquals("paquete inválido", invalid("enable", pkg))
            } else {
                assertEquals(pkg, valid("enable", pkg).packageName)
            }
        }
    }

    @Test
    fun `the 200 character package limit is enforced`() {
        val long = "com." + "a".repeat(200)
        assertTrue(long.length > ShellRequestValidator.MAX_PACKAGE_CHARS)
        assertEquals("paquete inválido", invalid("force-stop", long))
        val exactly = "com." + "a".repeat(196)
        assertEquals(200, exactly.length)
        assertEquals(exactly, valid("force-stop", exactly).packageName)
    }
}
