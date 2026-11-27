package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exit-status marker parsing (phone-shell T2). The adb `shell:` service gives
 * no exit code, so the client appends `echo __IDUPI_EXIT__$?` and this parser
 * separates the code from the command output. CRLF arrives because the legacy
 * shell service allocates a pseudo-terminal.
 */
class ShellOutputParserTest {

    @Test
    fun `parses the exit code and removes the marker line`() {
        assertEquals(
            ShellResult(0, "hello"),
            ShellOutputParser.parse("hello\n__IDUPI_EXIT__0\n"),
        )
    }

    @Test
    fun `parses CRLF terminal output`() {
        assertEquals(
            ShellResult(0, "hello"),
            ShellOutputParser.parse("hello\r\n__IDUPI_EXIT__0\r\n"),
        )
    }

    @Test
    fun `keeps every output line and removes only the final break`() {
        assertEquals(
            ShellResult(1, "line1\nline2"),
            ShellOutputParser.parse("line1\nline2\n__IDUPI_EXIT__1\n"),
        )
    }

    @Test
    fun `empty output still yields the exit code`() {
        assertEquals(ShellResult(0, ""), ShellOutputParser.parse("__IDUPI_EXIT__0\n"))
        assertEquals(ShellResult(3, ""), ShellOutputParser.parse("__IDUPI_EXIT__3"))
    }

    @Test
    fun `output without a trailing newline stays intact`() {
        assertEquals(
            ShellResult(0, "no newline here"),
            ShellOutputParser.parse("no newline here__IDUPI_EXIT__0\n"),
        )
    }

    @Test
    fun `preserves additional trailing blank lines`() {
        // Two blank lines are output; only the one the shell wrote after the
        // last line belongs to the stream framing.
        assertEquals(
            ShellResult(0, "x\n\n"),
            ShellOutputParser.parse("x\n\n\n__IDUPI_EXIT__0\n"),
        )
    }

    @Test
    fun `accepts a CR-only line ending`() {
        assertEquals(
            ShellResult(7, "value"),
            ShellOutputParser.parse("value\r__IDUPI_EXIT__7\r"),
        )
    }

    @Test
    fun `parses the highest shell exit status`() {
        assertEquals(
            ShellResult(255, ""),
            ShellOutputParser.parse("__IDUPI_EXIT__255\r\n"),
        )
    }

    @Test
    fun `a missing marker reports an unknown exit and preserves the raw output`() {
        val raw = "hello\nworld"
        assertEquals(ShellResult(-1, raw), ShellOutputParser.parse(raw))
    }

    @Test
    fun `a marker without digits reports an unknown exit and preserves the raw output`() {
        val raw = "hello\n__IDUPI_EXIT__\n"
        assertEquals(ShellResult(-1, raw), ShellOutputParser.parse(raw))
    }

    @Test
    fun `text after the marker is not accepted as an exit code`() {
        val raw = "hello\n__IDUPI_EXIT__0oops\n"
        assertEquals(ShellResult(-1, raw), ShellOutputParser.parse(raw))
    }

    @Test
    fun `the last marker wins when the command prints the marker itself`() {
        assertEquals(
            ShellResult(0, "echo __IDUPI_EXIT__"),
            ShellOutputParser.parse("echo __IDUPI_EXIT__\n__IDUPI_EXIT__0\n"),
        )
    }

    @Test
    fun `an empty stream has no marker`() {
        assertEquals(ShellResult(-1, ""), ShellOutputParser.parse(""))
    }

    @Test
    fun `isComplete waits for the whole marker line`() {
        assertEquals(false, ShellOutputParser.isComplete(""))
        assertEquals(false, ShellOutputParser.isComplete("output\n__IDUPI_EX"))
        assertEquals(false, ShellOutputParser.isComplete("output\n__IDUPI_EXIT__1"))
        assertEquals(true, ShellOutputParser.isComplete("\n__IDUPI_EXIT__1\n"))
        assertEquals(true, ShellOutputParser.isComplete("Success\r\n__IDUPI_EXIT__0\r\n"))
        assertEquals(true, ShellOutputParser.isComplete("__IDUPI_EXIT__255\n"))
    }
}
