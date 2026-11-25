package com.idupi.app.data.phone

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reporter behavior (phone-agent T2): the 60 s loop, the on-demand path and —
 * importantly — every condition that must suppress a post. Uses virtual time
 * and [FakeIduPiClient]; the Android collectors are not involved.
 */
class PhoneStatusReporterTest {

    private lateinit var fake: FakeIduPiClient
    private val snapshot = PhoneStatus(
        collectedAt = "2026-11-24T12:00:00Z",
        appVersion = "test",
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun reporter(
        enabled: suspend () -> Boolean = { true },
        canReport: () -> Boolean = { true },
        intervalMillis: Long = 1_000,
    ) = PhoneStatusReporter(
        clientSource = FakeClientSource(fake),
        snapshotProvider = { snapshot },
        isEnabled = enabled,
        canReport = canReport,
        intervalMillis = intervalMillis,
    )

    @Test
    fun `reportNow posts one snapshot`() = runTest {
        val reporter = reporter()

        assertTrue(reporter.reportNow())

        assertEquals(1, fake.postedPhoneStatuses.size)
        assertSame(snapshot, fake.postedPhoneStatuses.single())
    }

    @Test
    fun `reportNow skips work when no connection profile is saved`() = runTest {
        val reporter = reporter(canReport = { false })

        assertFalse(reporter.reportNow())
        assertTrue(fake.postedPhoneStatuses.isEmpty())
    }

    @Test
    fun `reportNow skips work when the snapshot cannot be collected`() = runTest {
        val reporter = PhoneStatusReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { null },
            isEnabled = { true },
            canReport = { true },
        )

        assertFalse(reporter.reportNow())
        assertTrue(fake.postedPhoneStatuses.isEmpty())
    }

    @Test
    fun `reportNow reports failure without throwing`() = runTest {
        fake.failWith = RuntimeException("server down")
        val reporter = reporter()

        assertFalse(reporter.reportNow())
    }

    @Test
    fun `the loop posts immediately and then every interval`() = runTest {
        val reporter = reporter(intervalMillis = 1_000)

        reporter.start(backgroundScope)
        runCurrent()
        assertEquals(1, fake.postedPhoneStatuses.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fake.postedPhoneStatuses.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, fake.postedPhoneStatuses.size)

        reporter.stop()
    }

    @Test
    fun `the loop skips ticks while the switch is off`() = runTest {
        val enabled = MutableStateFlow(false)
        val reporter = PhoneStatusReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { snapshot },
            isEnabled = { enabled.value },
            canReport = { true },
            intervalMillis = 60_000,
        )

        reporter.start(backgroundScope)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, fake.postedPhoneStatuses.size)

        enabled.value = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, fake.postedPhoneStatuses.size)

        reporter.stop()
    }

    @Test
    fun `the loop does nothing without a connection profile`() = runTest {
        val reporter = reporter(canReport = { false }, intervalMillis = 1_000)

        reporter.start(backgroundScope)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fake.postedPhoneStatuses.isEmpty())
        reporter.stop()
    }

    @Test
    fun `a failed tick does not kill the loop`() = runTest {
        fake.failWith = RuntimeException("offline")
        val reporter = reporter(intervalMillis = 1_000)

        reporter.start(backgroundScope)
        runCurrent()
        assertTrue(fake.postedPhoneStatuses.isEmpty())

        fake.failWith = null
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fake.postedPhoneStatuses.size)
        reporter.stop()
    }

    @Test
    fun `start is idempotent and keeps one loop`() = runTest {
        val reporter = reporter(intervalMillis = 1_000)

        reporter.start(backgroundScope)
        reporter.start(backgroundScope)
        runCurrent()

        assertEquals(1, fake.postedPhoneStatuses.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fake.postedPhoneStatuses.size)

        reporter.stop()
    }

    @Test
    fun `stop cancels the loop`() = runTest {
        val reporter = reporter(intervalMillis = 1_000)

        reporter.start(backgroundScope)
        runCurrent()
        reporter.stop()

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, fake.postedPhoneStatuses.size)
    }
}
