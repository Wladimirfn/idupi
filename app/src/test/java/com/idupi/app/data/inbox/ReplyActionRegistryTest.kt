package com.idupi.app.data.inbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reply-action bookkeeping (phone-inbox T3).
 *
 * The actual RemoteInput + PendingIntent delivery is Android-bound and covered
 * by the on-device delivery checks of T4/T6; these tests pin the decision the
 * policy depends on: an action exists only while it is live, and a failed
 * delivery removes it so a later reminder never reports a reply that cannot
 * be sent.
 */
class ReplyActionRegistryTest {

    private val liveAction = ReplyAction { _, _ -> true }

    @Before
    fun setUp() = ReplyActionRegistry.clear()

    @After
    fun tearDown() = ReplyActionRegistry.clear()

    @Test
    fun `send without a live action returns false`() {
        assertFalse(ReplyActionRegistry.send("whatsapp|Carlos", "hola") { _, _ -> true })
        assertFalse(ReplyActionRegistry.has("whatsapp|Carlos"))
    }

    @Test
    fun `a registered action is delivered with the exact text`() {
        ReplyActionRegistry.register("whatsapp|Carlos", liveAction)
        var delivered: String? = null

        val sent = ReplyActionRegistry.send("whatsapp|Carlos", "Nos vemos a las 5") { _, text ->
            delivered = text
            true
        }

        assertTrue(sent)
        assertEquals("Nos vemos a las 5", delivered)
        assertTrue(ReplyActionRegistry.has("whatsapp|Carlos"))
    }

    @Test
    fun `blank text is never sent and keeps the action alive`() {
        ReplyActionRegistry.register("whatsapp|Carlos", liveAction)
        var called = false

        val sent = ReplyActionRegistry.send("whatsapp|Carlos", "   ") { _, _ ->
            called = true
            true
        }

        assertFalse(sent)
        assertFalse(called)
        assertTrue(ReplyActionRegistry.has("whatsapp|Carlos"))
    }

    @Test
    fun `a failed delivery drops the action`() {
        ReplyActionRegistry.register("whatsapp|Carlos", liveAction)

        val sent = ReplyActionRegistry.send("whatsapp|Carlos", "hola") { _, _ -> false }

        assertFalse(sent)
        assertFalse(ReplyActionRegistry.has("whatsapp|Carlos"))
    }

    @Test
    fun `a throwing delivery drops the action instead of propagating`() {
        ReplyActionRegistry.register("whatsapp|Carlos", liveAction)

        val sent = ReplyActionRegistry.send("whatsapp|Carlos", "hola") { _, _ ->
            throw IllegalStateException("dead action")
        }

        assertFalse(sent)
        assertFalse(ReplyActionRegistry.has("whatsapp|Carlos"))
    }

    @Test
    fun `dropping one conversation keeps the other apps' actions`() {
        ReplyActionRegistry.register("whatsapp|Carlos", liveAction)
        ReplyActionRegistry.register("whatsapp_business|Carlos", liveAction)

        ReplyActionRegistry.drop("whatsapp|Carlos")

        assertFalse(ReplyActionRegistry.has("whatsapp|Carlos"))
        assertTrue(ReplyActionRegistry.has("whatsapp_business|Carlos"))
    }
}
