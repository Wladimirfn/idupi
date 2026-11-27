package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Command building and shell quoting (phone-shell T2). The helper bootstrap
 * depends on these exact strings: a wrong flag makes `pm install` fail and a
 * wrong quote rule would let a path break the command.
 */
class ShellCommandsTest {

    @Test
    fun `instrumentBridge starts the helper instrumentation`() {
        assertEquals(
            "am instrument -w -r com.idupi.shell/.ShellBridgeInstrumentation",
            ShellCommands.instrumentBridge(),
        )
    }

    @Test
    fun `grantWriteSecureSettings targets the app permission`() {
        assertEquals(
            "pm grant com.idupi.app android.permission.WRITE_SECURE_SETTINGS",
            ShellCommands.grantWriteSecureSettings(),
        )
    }

    @Test
    fun `installHelper replaces the app and quotes the path`() {
        assertEquals(
            "pm install -r -t '/data/local/tmp/idupi-helper.apk'",
            ShellCommands.installHelper("/data/local/tmp/idupi-helper.apk"),
        )
        assertEquals(
            "pm install -r -t '/storage/emulated/0/a b.apk'",
            ShellCommands.installHelper("/storage/emulated/0/a b.apk"),
        )
    }

    @Test
    fun `installHelper rejects a blank path`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommands.installHelper("  ")
        }
    }

    @Test
    fun `quote escapes an embedded single quote`() {
        assertEquals("'it'\\''s.apk'", ShellCommands.quote("it's.apk"))
        assertEquals("''", ShellCommands.quote(""))
    }

    @Test
    fun `quote rejects line breaks and NUL`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommands.quote("/tmp/a\nb.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommands.quote("/tmp/a\rb.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommands.quote("/tmp/a\u0000b.apk")
        }
    }

    @Test
    fun `withExitStatus appends the status probe`() {
        assertEquals(
            "id; echo __IDUPI_EXIT__\$?",
            ShellCommands.withExitStatus("id"),
        )
    }

    @Test
    fun `withExitStatus rejects a blank command`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommands.withExitStatus(" ")
        }
    }
}
