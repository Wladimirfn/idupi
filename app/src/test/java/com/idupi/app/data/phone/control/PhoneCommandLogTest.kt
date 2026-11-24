package com.idupi.app.data.phone.control

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recent-command ring buffer and payload-free summaries (phone-agent T3). */
class PhoneCommandLogTest {

    @After
    fun tearDown() {
        PhoneCommandLog.clear()
    }

    private fun entry(index: Int) = PhoneCommandLogEntry(
        atMillis = index.toLong(),
        type = "screen",
        target = "pantalla",
        ok = true,
    )

    @Test
    fun `keeps the newest thirty entries, newest first`() {
        repeat(35) { index -> PhoneCommandLog.record(entry(index)) }

        val entries = PhoneCommandLog.entries.value

        assertEquals(PhoneCommandLog.MAX_ENTRIES, entries.size)
        assertEquals(34L, entries.first().atMillis)
        assertEquals(5L, entries.last().atMillis)
    }

    @Test
    fun `clear empties the buffer`() {
        PhoneCommandLog.record(entry(1))

        PhoneCommandLog.clear()

        assertTrue(PhoneCommandLog.entries.value.isEmpty())
    }

    @Test
    fun `summaries never carry typed text or dump contents`() {
        val typed = PhoneCommand.TypeText("phc-1", null, "hola secreto", submit = false)
        assertEquals("type", PhoneCommandSummaries.typeLabel(typed))
        assertEquals("12 caracteres", PhoneCommandSummaries.target(typed))

        val screen = PhoneCommand.Screen("phc-1", null)
        assertEquals("pantalla", PhoneCommandSummaries.target(screen))

        val entry = PhoneCommandSummaries.entry(typed, ok = true, error = null, atMillis = 7L)
        assertFalse(entry.target.contains("secreto"))
        assertEquals(7L, entry.atMillis)
        assertTrue(entry.ok)
    }

    @Test
    fun `summaries describe every command target`() {
        assertEquals(
            "Enviar",
            PhoneCommandSummaries.target(PhoneCommand.Tap("p", null, NodeTarget.Text("Enviar"))),
        )
        assertEquals(
            "com.whatsapp:id/send",
            PhoneCommandSummaries.target(
                PhoneCommand.Tap("p", null, NodeTarget.Id("com.whatsapp:id/send"))
            ),
        )
        assertEquals(
            "540,2100",
            PhoneCommandSummaries.target(PhoneCommand.LongPress("p", null, NodeTarget.Point(540, 2100))),
        )
        assertEquals(
            "down",
            PhoneCommandSummaries.target(PhoneCommand.Scroll("p", null, ScrollDirection.DOWN)),
        )
        assertEquals(
            "quick_settings",
            PhoneCommandSummaries.target(
                PhoneCommand.Global("p", null, GlobalAction.QUICK_SETTINGS)
            ),
        )
        assertEquals(
            "whatsapp",
            PhoneCommandSummaries.target(PhoneCommand.OpenApp("p", null, "whatsapp")),
        )
        assertEquals("1500 ms", PhoneCommandSummaries.target(PhoneCommand.Wait("p", null, 1500)))
        assertEquals("estado", PhoneCommandSummaries.target(PhoneCommand.ReportStatus("p", null)))
    }

    @Test
    fun `a long tap label is truncated in the log`() {
        val long = "x".repeat(100)
        val target = PhoneCommandSummaries.target(PhoneCommand.Tap("p", null, NodeTarget.Text(long)))

        assertEquals(40, target.length)
    }

    @Test
    fun `clock formats the local time without nanos`() {
        val instant = java.time.Instant.parse("2026-11-24T12:34:56.789Z").toEpochMilli()
        assertEquals("12:34:56", PhoneCommandLogFormat.clock(instant, ZoneId.of("UTC")))
    }
}
