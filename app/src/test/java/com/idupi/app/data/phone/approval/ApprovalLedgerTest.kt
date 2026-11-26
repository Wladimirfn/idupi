package com.idupi.app.data.phone.approval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The local approval ledger is a security boundary: the phone executes a
 * command carrying an `approvalId` only when the owner approved that id ON THIS
 * PHONE, and only once. These tests pin record/consume, one use, expiry,
 * the bound and persistence.
 */
class ApprovalLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_000_000L

    private fun grant(
        id: String,
        expiresAt: Long = now + 600_000L,
        reason: String = "risky_text",
        target: String? = "Enviar",
        grantedAt: Long = now,
    ) = ApprovalGrant(
        approvalId = id,
        reason = reason,
        target = target,
        expiresAtMillis = expiresAt,
        grantedAtMillis = grantedAt,
    )

    private fun ledger(file: java.io.File? = null) = FileApprovalLedger(file = file, clock = { now })

    @Test
    fun `a recorded grant is consumed exactly once`() = runTest {
        val ledger = ledger()
        ledger.record(grant("apr-123456789abc"))

        val consumed = ledger.consume("apr-123456789abc", now)

        assertNotNull(consumed)
        assertEquals("apr-123456789abc", consumed!!.approvalId)
        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `an expired grant is never returned`() = runTest {
        val ledger = ledger()
        ledger.record(grant("apr-123456789abc", expiresAt = now - 1))

        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `consuming after the deadline refuses`() = runTest {
        val ledger = ledger()
        ledger.record(grant("apr-123456789abc", expiresAt = now + 60_000L))

        assertNull(ledger.consume("apr-123456789abc", now + 60_000L))
    }

    @Test
    fun `an unknown id is never granted`() = runTest {
        assertNull(ledger().consume("apr-unknown", now))
    }

    @Test
    fun `the ledger keeps at most one hundred entries`() = runTest {
        val ledger = ledger()
        repeat(105) { index ->
            ledger.record(grant("apr-$index", grantedAt = now + index))
        }

        assertNull(ledger.consume("apr-0", now))
        assertNull(ledger.consume("apr-4", now))
        assertNotNull(ledger.consume("apr-5", now))
        assertNotNull(ledger.consume("apr-104", now))
    }

    @Test
    fun `grants survive a new instance over the same file`() = runTest {
        val file = tempFolder.newFile("ledger.json").apply { delete() }
        ledger(file).record(grant("apr-123456789abc"))

        val reopened = ledger(file)

        assertNotNull(reopened.consume("apr-123456789abc", now))
    }

    @Test
    fun `a corrupt file fails closed and still accepts new grants`() = runTest {
        val file = tempFolder.newFile("ledger.json").apply { writeText("not json") }
        val ledger = ledger(file)

        assertNull(ledger.consume("apr-123456789abc", now))

        ledger.record(grant("apr-123456789abc"))
        assertNotNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `remove drops a grant that will not be used`() = runTest {
        val ledger = ledger()
        ledger.record(grant("apr-123456789abc"))

        ledger.remove("apr-123456789abc")

        assertNull(ledger.consume("apr-123456789abc", now))
    }

    @Test
    fun `blank approval ids are ignored`() = runTest {
        val ledger = ledger()
        ledger.record(grant(""))

        assertNull(ledger.consume("", now))
    }

    @Test
    fun `expire prunes stale grants`() = runTest {
        val ledger = ledger()
        ledger.record(grant("apr-123456789abc", expiresAt = now + 10_000L))

        ledger.expire(now + 10_001L)

        assertNull(ledger.consume("apr-123456789abc", now + 10_001L))
    }
}
