package com.idupi.app.data.health

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Health reporter behavior (phone-health T2): the 3 h loop, the 30 min
 * start/resume throttle, the manual send, and every condition that must
 * suppress a post. Virtual time; the Android readers are not involved.
 */
class HealthReporterTest {

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun upload() = HealthSamplesUpload(
        tzOffsetMinutes = -180,
        samples = listOf(
            HealthSample(
                date = "2026-11-24",
                source = HealthSampleBuilder.SOURCE_HEALTH_CONNECT,
                sourceApp = "",
                capturedAt = "2026-11-24T15:00:00Z",
                metrics = HealthMetrics(steps = 8123),
            ),
        ),
    )

    @Test
    fun `reportNow posts one upload and records the sample count`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertTrue(reporter.reportNow())

        assertEquals(1, fake.postedHealthUploads.size)
        assertEquals(1, fake.postedHealthUploads.single().samples.size)
        assertEquals(1, reporter.reportState.value.sampleCount)
        assertEquals(true, reporter.reportState.value.success)
        assertEquals(0L, reporter.reportState.value.lastAttemptAtMillis)
    }

    @Test
    fun `nothing is collected without a saved PC profile`() = runTest {
        var collected = 0
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { collected++; upload() },
            canReport = { false },
            nowMillis = { testScheduler.currentTime },
        )

        assertFalse(reporter.reportNow())
        assertEquals(0, collected)
        assertTrue(fake.postedHealthUploads.isEmpty())
    }

    @Test
    fun `a null upload is not an attempt`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { null },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertFalse(reporter.reportNow())
        assertNull(reporter.reportState.value.lastAttemptAtMillis)
        assertTrue(fake.postedHealthUploads.isEmpty())
    }

    @Test
    fun `a failing collection does not throw or post`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { throw IllegalStateException("reader down") },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertFalse(reporter.reportNow())
        assertTrue(fake.postedHealthUploads.isEmpty())
    }

    @Test
    fun `a failing post reports failure without throwing`() = runTest {
        fake.failHealthPostWith = RuntimeException("server down")
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertFalse(reporter.reportNow())
        assertEquals(false, reporter.reportState.value.success)
        assertEquals(1, reporter.reportState.value.sampleCount)
    }

    @Test
    fun `the loop posts immediately and then every three hours`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
            tickMillis = 60_000L,
            intervalMillis = 3L * 60L * 60_000L,
        )

        reporter.start(backgroundScope)
        runCurrent()
        assertEquals(1, fake.postedHealthUploads.size)

        advanceTimeBy(3L * 60L * 60_000L - 60_000L)
        runCurrent()
        assertEquals(1, fake.postedHealthUploads.size)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, fake.postedHealthUploads.size)

        reporter.stop()
    }

    @Test
    fun `resume posts are throttled to one every thirty minutes`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertTrue(reporter.reportOnResume())
        assertFalse(reporter.reportOnResume())

        advanceTimeBy(29 * 60_000L)
        assertFalse(reporter.reportOnResume())

        advanceTimeBy(60_000L)
        assertTrue(reporter.reportOnResume())
        assertEquals(2, fake.postedHealthUploads.size)
    }

    @Test
    fun `the manual send ignores the resume throttle`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertTrue(reporter.reportOnResume())
        assertTrue(reporter.reportNow())
        assertTrue(reporter.reportNow())

        assertEquals(3, fake.postedHealthUploads.size)
    }

    @Test
    fun `start is idempotent and keeps one loop`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
            tickMillis = 60_000L,
            intervalMillis = 3L * 60L * 60_000L,
        )

        reporter.start(backgroundScope)
        reporter.start(backgroundScope)
        runCurrent()

        assertEquals(1, fake.postedHealthUploads.size)

        advanceTimeBy(3L * 60L * 60_000L)
        runCurrent()
        assertEquals(2, fake.postedHealthUploads.size)

        reporter.stop()
    }

    @Test
    fun `stop cancels the loop`() = runTest {
        val reporter = HealthReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { upload() },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
            tickMillis = 60_000L,
            intervalMillis = 3L * 60L * 60_000L,
        )

        reporter.start(backgroundScope)
        runCurrent()
        reporter.stop()

        advanceTimeBy(6L * 60L * 60_000L)
        runCurrent()

        assertEquals(1, fake.postedHealthUploads.size)
    }
}
