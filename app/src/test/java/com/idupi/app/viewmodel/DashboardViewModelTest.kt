package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.CpuMetrics
import com.idupi.app.domain.model.SystemMetrics
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Dashboard polling contract: cadence, stop-on-leave, and the "keep the last
 * good sample" rule that keeps a dropped Wi-Fi poll from blanking the screen.
 */
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    private val sampleA = SystemMetrics(
        sampledAt = "2026-11-23T12:00:00.000Z",
        cpu = CpuMetrics(name = "AMD Ryzen 5 5600X", usagePercent = 12.5, cores = 12),
    )
    private val sampleB = sampleA.copy(
        sampledAt = "2026-11-23T12:00:02.000Z",
        cpu = CpuMetrics(name = "AMD Ryzen 5 5600X", usagePercent = 30.0, cores = 12),
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun createViewModel() = DashboardViewModel(
        clientSource = FakeClientSource(fake),
        pollIntervalMs = 2_000L,
    )

    @Test
    fun `startPolling samples immediately then every interval until stopped`() = runTest {
        fake.metricsToReturn = sampleA
        val viewModel = createViewModel()

        viewModel.startPolling()
        runCurrent()

        assertEquals(1, fake.getSystemMetricsCalls)
        assertEquals(sampleA, viewModel.state.value.metrics)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, fake.getSystemMetricsCalls)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, fake.getSystemMetricsCalls)

        viewModel.stopPolling()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, fake.getSystemMetricsCalls)
    }

    @Test
    fun `startPolling is idempotent while the loop is running`() = runTest {
        fake.metricsToReturn = sampleA
        val viewModel = createViewModel()

        viewModel.startPolling()
        viewModel.startPolling()
        runCurrent()
        assertEquals(1, fake.getSystemMetricsCalls)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, fake.getSystemMetricsCalls)

        viewModel.stopPolling()
    }

    @Test
    fun `a transient failure keeps the last good sample`() = runTest {
        fake.metricsToReturn = sampleA
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()
        assertEquals(sampleA, viewModel.state.value.metrics)

        fake.failMetricsWith = IOException("offline")
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(sampleA, viewModel.state.value.metrics)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.refreshFailed)
        assertFalse(viewModel.state.value.isLoading)

        fake.failMetricsWith = null
        fake.metricsToReturn = sampleB
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(sampleB, viewModel.state.value.metrics)
        assertFalse(viewModel.state.value.refreshFailed)
        viewModel.stopPolling()
    }

    @Test
    fun `a failure before any sample surfaces the error state and clears on success`() = runTest {
        fake.failMetricsWith = IOException("offline")
        val viewModel = createViewModel()

        viewModel.startPolling()
        runCurrent()

        assertNull(viewModel.state.value.metrics)
        assertFalse(viewModel.state.value.isLoading)
        val message = viewModel.state.value.errorMessage
        assertNotNull(message)
        assertTrue(message!!.contains("offline"))

        fake.failMetricsWith = null
        fake.metricsToReturn = sampleB
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(sampleB, viewModel.state.value.metrics)
        assertNull(viewModel.state.value.errorMessage)
        viewModel.stopPolling()
    }

    @Test
    fun `refreshNow fetches once without starting the poll loop`() = runTest {
        fake.metricsToReturn = sampleA
        val viewModel = createViewModel()

        viewModel.refreshNow()
        runCurrent()

        assertEquals(1, fake.getSystemMetricsCalls)
        assertEquals(sampleA, viewModel.state.value.metrics)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fake.getSystemMetricsCalls)
    }

    @Test
    fun `stopPolling before startPolling is a no-op`() = runTest {
        val viewModel = createViewModel()

        viewModel.stopPolling()
        runCurrent()

        assertEquals(0, fake.getSystemMetricsCalls)
        assertNull(viewModel.state.value.metrics)
    }
}
