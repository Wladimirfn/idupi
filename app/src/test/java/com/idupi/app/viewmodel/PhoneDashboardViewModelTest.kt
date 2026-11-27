package com.idupi.app.viewmodel

import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.phone.PhoneBattery
import com.idupi.app.data.phone.PhoneDashboardSnapshot
import com.idupi.app.data.phone.PhoneDashboardSource
import com.idupi.app.data.phone.PhoneMemory
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Phone page polling contract (phone-dashboard T2): local samples every five
 * seconds, an explicit refresh that also re-reads the expensive app storage,
 * and the same "keep the last good sample" rule as the PC page.
 */
class PhoneDashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sample = PhoneDashboardSnapshot(
        memory = PhoneMemory(usedBytes = 4_000L, totalBytes = 8_000L),
    )
    private val newer = sample.copy(battery = PhoneBattery(percent = 80))

    private class FakePhoneDashboardSource : PhoneDashboardSource {
        val forceFlags = mutableListOf<Boolean>()
        var snapshot: PhoneDashboardSnapshot = PhoneDashboardSnapshot()
        var failWith: Exception? = null

        override suspend fun collect(forceAppStorage: Boolean): PhoneDashboardSnapshot {
            forceFlags += forceAppStorage
            failWith?.let { throw it }
            return snapshot
        }
    }

    private fun createViewModel(source: FakePhoneDashboardSource) = PhoneDashboardViewModel(
        source = source,
        pollIntervalMs = 5_000L,
    )

    @Test
    fun `startPolling samples immediately then every five seconds until stopped`() = runTest {
        val fake = FakePhoneDashboardSource().apply { snapshot = sample }
        val viewModel = createViewModel(fake)

        viewModel.startPolling()
        runCurrent()

        assertEquals(listOf(false), fake.forceFlags)
        assertEquals(sample, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, fake.forceFlags.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, fake.forceFlags.size)

        viewModel.stopPolling()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(2, fake.forceFlags.size)
    }

    @Test
    fun `startPolling is idempotent while the loop is running`() = runTest {
        val fake = FakePhoneDashboardSource().apply { snapshot = sample }
        val viewModel = createViewModel(fake)

        viewModel.startPolling()
        viewModel.startPolling()
        runCurrent()
        assertEquals(1, fake.forceFlags.size)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, fake.forceFlags.size)

        viewModel.stopPolling()
    }

    @Test
    fun `refreshNow forces app storage without starting the poll loop`() = runTest {
        val fake = FakePhoneDashboardSource().apply { snapshot = sample }
        val viewModel = createViewModel(fake)

        viewModel.refreshNow()
        runCurrent()

        assertEquals(listOf(true), fake.forceFlags)
        assertEquals(sample, viewModel.state.value.snapshot)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, fake.forceFlags.size)
    }

    @Test
    fun `a transient failure keeps the last good snapshot`() = runTest {
        val fake = FakePhoneDashboardSource().apply { snapshot = sample }
        val viewModel = createViewModel(fake)
        viewModel.startPolling()
        runCurrent()
        assertEquals(sample, viewModel.state.value.snapshot)

        fake.failWith = IOException("offline")
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(sample, viewModel.state.value.snapshot)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.refreshFailed)
        assertFalse(viewModel.state.value.isLoading)

        fake.failWith = null
        fake.snapshot = newer
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(newer, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.refreshFailed)
        viewModel.stopPolling()
    }

    @Test
    fun `a failure before any snapshot surfaces the error and clears on success`() = runTest {
        val fake = FakePhoneDashboardSource().apply { failWith = IOException("offline") }
        val viewModel = createViewModel(fake)

        viewModel.startPolling()
        runCurrent()

        assertNull(viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isLoading)
        val message = viewModel.state.value.errorMessage
        assertNotNull(message)
        assertTrue(message!!.contains("offline"))

        fake.failWith = null
        fake.snapshot = newer
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(newer, viewModel.state.value.snapshot)
        assertNull(viewModel.state.value.errorMessage)
        viewModel.stopPolling()
    }

    @Test
    fun `stopPolling before startPolling is a no-op`() = runTest {
        val fake = FakePhoneDashboardSource()
        val viewModel = createViewModel(fake)

        viewModel.stopPolling()
        runCurrent()

        assertEquals(0, fake.forceFlags.size)
        assertNull(viewModel.state.value.snapshot)
    }
}
