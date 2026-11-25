package com.idupi.app.data.phone.control

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Control loop decision logic (phone-agent T3): enabled/profile gating,
 * expired-command skip, invalid-command reporting and backoff. Executed with
 * the real poller over [FakeIduPiClient] and virtual time; the Android executor
 * is replaced by a lambda.
 */
class PhoneControlPollerTest {

    private lateinit var fake: FakeIduPiClient
    private val logEntries = mutableListOf<PhoneCommandLogEntry>()
    private val executed = mutableListOf<PhoneCommand>()
    private val now = 1_766_000_000_000L // 2025-12-16T…; any fixed instant works

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        logEntries.clear()
        executed.clear()
    }

    private fun newPoller(
        enabled: suspend () -> Boolean = { true },
        canPoll: () -> Boolean = { true },
        execute: suspend (PhoneCommand) -> CommandOutcome = { command ->
            executed += command
            CommandOutcome.success()
        },
        nowMillis: () -> Long = { now },
    ) = PhoneControlPoller(
        clientSource = FakeClientSource(fake),
        isEnabled = enabled,
        canPoll = canPoll,
        execute = execute,
        nowMillis = nowMillis,
        log = logEntries::add,
    )

    private fun command(
        type: String,
        args: String = "{}",
        id: String = "phc-000000000001",
        expiresAt: String? = null,
    ): String {
        val expiry = if (expiresAt != null) ",\"expiresAt\":\"$expiresAt\"" else ""
        return """{"id":"$id","type":"$type","args":$args$expiry}"""
    }

    @Test
    fun `stops when the master switch is off`() = runTest {
        val step = newPoller(enabled = { false }).pollOnce()

        assertEquals(PollStep.Stop, step)
        assertEquals(0, fake.getNextPhoneCommandCalls)
    }

    @Test
    fun `stops when no connection profile exists`() = runTest {
        val step = newPoller(canPoll = { false }).pollOnce()

        assertEquals(PollStep.Stop, step)
        assertEquals(0, fake.getNextPhoneCommandCalls)
    }

    @Test
    fun `a 204 continues immediately`() = runTest {
        assertEquals(PollStep.Continue, newPoller().pollOnce())
        assertEquals(1, fake.getNextPhoneCommandCalls)
        assertEquals(PhoneControlPoller.DEFAULT_WAIT_SECONDS, fake.lastNextPhoneCommandWait)
    }

    @Test
    fun `executes a command and posts its result`() = runTest {
        fake.nextPhoneCommands += command("screen")

        assertEquals(PollStep.Continue, newPoller().pollOnce())

        assertEquals(1, executed.size)
        assertEquals(PhoneCommand.Screen("phc-000000000001", null), executed.single())
        val posted = fake.postedPhoneCommandResults.single()
        assertEquals("phc-000000000001", posted.commandId)
        assertTrue(posted.ok)
        assertEquals("screen", logEntries.single().type)
        assertEquals("pantalla", logEntries.single().target)
        assertTrue(logEntries.single().ok)
    }

    @Test
    fun `posts the executor result or failure`() = runTest {
        fake.nextPhoneCommands += command("wait", """{"ms":0}""")
        val result = buildJsonObject { put("message", JsonPrimitive("Listo")) }
        val poller = newPoller(execute = { CommandOutcome.success(result) })

        poller.pollOnce()

        assertEquals(result, fake.postedPhoneCommandResults.single().result)
        assertTrue(fake.postedPhoneCommandResults.single().ok)

        fake.nextPhoneCommands += command("tap", """{"text":"Enviar"}""")
        val failing = newPoller(execute = { CommandOutcome.failure("no se encontró el texto") })
        failing.pollOnce()

        val failure = fake.postedPhoneCommandResults.last()
        assertFalse(failure.ok)
        assertEquals("no se encontró el texto", failure.error)
    }

    @Test
    fun `skips an expired command and reports the failure`() = runTest {
        fake.nextPhoneCommands += command(
            "tap",
            """{"text":"Enviar"}""",
            expiresAt = "2020-01-01T00:00:00.000Z",
        )

        assertEquals(PollStep.Continue, newPoller().pollOnce())

        assertTrue(executed.isEmpty())
        val posted = fake.postedPhoneCommandResults.single()
        assertFalse(posted.ok)
        assertEquals(PhoneControlPoller.COMMAND_EXPIRED, posted.error)
        assertEquals("tap", logEntries.single().type)
        assertFalse(logEntries.single().ok)
    }

    @Test
    fun `an invalid command posts a failure when the id is readable`() = runTest {
        fake.nextPhoneCommands += command("shake")

        assertEquals(PollStep.Continue, newPoller().pollOnce())

        assertTrue(executed.isEmpty())
        val posted = fake.postedPhoneCommandResults.single()
        assertFalse(posted.ok)
        assertEquals("tipo desconocido", posted.error)
        assertEquals("shake", logEntries.single().type)
    }

    @Test
    fun `an unreadable command is logged and not posted`() = runTest {
        fake.nextPhoneCommands += "not json"

        assertEquals(PollStep.Continue, newPoller().pollOnce())

        assertTrue(fake.postedPhoneCommandResults.isEmpty())
        assertEquals("?", logEntries.single().type)
        assertFalse(logEntries.single().ok)
    }

    @Test
    fun `an executor crash becomes a reported failure`() = runTest {
        fake.nextPhoneCommands += command("screen")
        val poller = newPoller(execute = { throw IllegalStateException("boom") })

        assertEquals(PollStep.Continue, poller.pollOnce())

        val posted = fake.postedPhoneCommandResults.single()
        assertFalse(posted.ok)
        assertEquals(PhoneControlPoller.COMMAND_INTERNAL_ERROR, posted.error)
    }

    @Test
    fun `poll failures back off 5, 10, 20 and cap at 30 seconds`() = runTest {
        val poller = newPoller()
        val delays = mutableListOf<Long>()
        repeat(5) {
            fake.failNextPhoneCommandWith = java.io.IOException("offline")
            val step = poller.pollOnce()
            assertTrue(step is PollStep.Retry)
            delays += (step as PollStep.Retry).delayMillis
        }

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `a successful poll resets the backoff`() = runTest {
        val poller = newPoller()

        fake.failNextPhoneCommandWith = java.io.IOException("offline")
        assertEquals(PollStep.Retry(5_000), poller.pollOnce())
        fake.failNextPhoneCommandWith = java.io.IOException("offline")
        assertEquals(PollStep.Retry(10_000), poller.pollOnce())

        assertEquals(PollStep.Continue, poller.pollOnce()) // 204 resets

        fake.failNextPhoneCommandWith = java.io.IOException("offline")
        assertEquals(PollStep.Retry(5_000), poller.pollOnce())
    }

    @Test
    fun `a result post failure becomes a retry`() = runTest {
        fake.nextPhoneCommands += command("screen")
        val poller = newPoller(execute = {
            // Fail only the result post, after a successful poll.
            fake.failWith = java.io.IOException("pc down")
            CommandOutcome.success()
        })

        assertEquals(PollStep.Retry(5_000), poller.pollOnce())
    }

    @Test
    fun `typed text never reaches the log`() = runTest {
        fake.nextPhoneCommands += command("type", """{"text":"secreto"}""")

        newPoller().pollOnce()

        val entry = logEntries.single()
        assertEquals("type", entry.type)
        assertEquals("7 caracteres", entry.target)
        assertFalse(entry.target.contains("secreto"))
    }
}
