package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.KillProcessOutcome
import com.idupi.app.domain.model.OS_CATEGORY_AGENTS
import com.idupi.app.domain.model.OS_CATEGORY_APPS
import com.idupi.app.domain.model.OS_CATEGORY_BACKGROUND
import com.idupi.app.domain.model.OS_CATEGORY_IDUPI
import com.idupi.app.domain.model.OS_CATEGORY_WINDOWS
import com.idupi.app.domain.model.OsProcess
import com.idupi.app.domain.model.OsProcessSample
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
 * Procesos contract (T10): category/search/sort filtering, kill outcomes
 * (200/404/409), visible-only 3 s polling, and keeping the last good list when
 * a poll fails.
 */
class ConsoleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    private val chrome = OsProcess(
        pid = 100, name = "chrome", title = "Google Chrome y 4 pestañas más",
        memoryBytes = 500L * MIB, cpuPercent = 5.0, category = OS_CATEGORY_APPS,
    )
    private val svchost = OsProcess(
        pid = 200, name = "svchost", memoryBytes = 50L * MIB,
        cpuPercent = 0.5, category = OS_CATEGORY_WINDOWS,
    )
    private val claude = OsProcess(
        pid = 300, name = "claude", memoryBytes = 300L * MIB,
        cpuPercent = 2.0, category = OS_CATEGORY_AGENTS,
    )
    private val node = OsProcess(
        pid = 400, name = "node", memoryBytes = 100L * MIB,
        cpuPercent = 0.0, category = OS_CATEGORY_BACKGROUND,
    )
    private val server = OsProcess(
        pid = 500, name = "idupi-server", memoryBytes = 20L * MIB,
        cpuPercent = 1.0, category = OS_CATEGORY_IDUPI,
    )
    private val explorer = OsProcess(
        pid = 600, name = "explorer", title = "Program Manager",
        memoryBytes = 200L * MIB, cpuPercent = null, category = OS_CATEGORY_APPS,
    )
    private val paint = OsProcess(
        pid = 700, name = "mspaint", title = "sin_titulo - Paint",
        memoryBytes = 800L * MIB, cpuPercent = 0.1, category = OS_CATEGORY_APPS,
    )

    private val sample = OsProcessSample(
        sampledAt = "2026-11-23T18:00:00.000Z",
        totalMemoryBytes = 16L * 1024L * MIB,
        cpuCount = 12,
        processes = listOf(chrome, svchost, claude, node, server, explorer, paint),
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        fake.osProcessesToReturn = sample
    }

    private fun createViewModel() = ConsoleViewModel(
        clientSource = FakeClientSource(fake),
        pollIntervalMs = 3_000L,
    )

    @Test
    fun `startPolling samples immediately then every 3 seconds until stopped`() = runTest {
        val viewModel = createViewModel()

        viewModel.startPolling()
        runCurrent()

        assertEquals(1, fake.getOsProcessesCalls)
        assertEquals(sample, viewModel.state.value.sample)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, fake.getOsProcessesCalls)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, fake.getOsProcessesCalls)

        viewModel.stopPolling()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, fake.getOsProcessesCalls)
    }

    @Test
    fun `startPolling is idempotent while the loop is running`() = runTest {
        val viewModel = createViewModel()

        viewModel.startPolling()
        viewModel.startPolling()
        runCurrent()
        assertEquals(1, fake.getOsProcessesCalls)

        viewModel.stopPolling()
    }

    @Test
    fun `category filter uses the server ids and exposes per-category counts`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.setCategory(ProcessCategoryFilter.WINDOWS)
        assertEquals(listOf(svchost), viewModel.state.value.filteredProcesses)

        val state = viewModel.state.value
        assertEquals(7, state.countFor(ProcessCategoryFilter.ALL))
        assertEquals(3, state.countFor(ProcessCategoryFilter.APPS))
        assertEquals(1, state.countFor(ProcessCategoryFilter.WINDOWS))
        assertEquals(1, state.countFor(ProcessCategoryFilter.AGENTS))
        assertEquals(1, state.countFor(ProcessCategoryFilter.IDUPI))
        assertEquals(1, state.countFor(ProcessCategoryFilter.BACKGROUND))

        viewModel.setCategory(ProcessCategoryFilter.ALL)
        assertEquals(7, viewModel.state.value.filteredProcesses.size)

        viewModel.stopPolling()
    }

    @Test
    fun `search matches the process name and the window title case-insensitively`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.setQuery("CHROME")
        assertEquals(listOf(chrome), viewModel.state.value.filteredProcesses)

        // Matches the title, not the name.
        viewModel.setQuery("paint")
        assertEquals(listOf(paint), viewModel.state.value.filteredProcesses)

        // A query with no match yields nothing; clearing restores the list.
        viewModel.setQuery("does-not-exist")
        assertTrue(viewModel.state.value.filteredProcesses.isEmpty())
        viewModel.setQuery("")
        assertEquals(7, viewModel.state.value.filteredProcesses.size)

        viewModel.stopPolling()
    }

    @Test
    fun `sort by memory is descending and sort by name is ascending`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.setSort(ProcessSort.MEMORY)
        assertEquals(
            listOf("mspaint", "chrome", "claude", "explorer", "node", "svchost", "idupi-server"),
            viewModel.state.value.filteredProcesses.map { it.name }
        )

        viewModel.setSort(ProcessSort.NAME)
        assertEquals(
            listOf("chrome", "claude", "explorer", "idupi-server", "mspaint", "node", "svchost"),
            viewModel.state.value.filteredProcesses.map { it.name }
        )

        viewModel.stopPolling()
    }

    @Test
    fun `sort by cpu puts processes without a delta last`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.setSort(ProcessSort.CPU)
        val sorted = viewModel.state.value.filteredProcesses

        assertEquals("chrome", sorted.first().name)
        assertEquals("explorer", sorted.last().name)
        assertNull(sorted.last().cpuPercent)

        viewModel.stopPolling()
    }

    @Test
    fun `a successful kill removes the row without waiting for the next poll`() = runTest {
        fake.killOsProcessDefault = KillProcessOutcome.KILLED
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.killProcess(paint.pid)
        runCurrent()

        assertEquals(listOf(paint.pid), fake.killedOsPids)
        assertNull(viewModel.state.value.killingPid)
        assertFalse(viewModel.state.value.processes.any { it.pid == paint.pid })

        viewModel.stopPolling()
    }

    @Test
    fun `a 404 from the kill route also removes the row`() = runTest {
        fake.killOutcomeByPid[paint.pid] = KillProcessOutcome.GONE
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.killProcess(paint.pid)
        runCurrent()

        assertFalse(viewModel.state.value.processes.any { it.pid == paint.pid })
        assertNull(viewModel.state.value.errorMessage)

        viewModel.stopPolling()
    }

    @Test
    fun `a 409 keeps the protected process and surfaces the refusal`() = runTest {
        fake.killOutcomeByPid[svchost.pid] = KillProcessOutcome.PROTECTED
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        viewModel.killProcess(svchost.pid)
        runCurrent()

        assertTrue(viewModel.state.value.processes.any { it.pid == svchost.pid })
        assertEquals("Proceso protegido", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.killingPid)

        viewModel.stopPolling()
    }

    @Test
    fun `a failed kill request keeps the row and reports the error`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()

        fake.failWith = IOException("offline")
        viewModel.killProcess(chrome.pid)
        runCurrent()

        assertTrue(viewModel.state.value.processes.any { it.pid == chrome.pid })
        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.errorMessage!!.contains("offline"))

        viewModel.stopPolling()
    }

    @Test
    fun `a transient poll failure keeps the last good list`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPolling()
        runCurrent()
        assertEquals(sample, viewModel.state.value.sample)

        fake.failOsProcessesWith = IOException("offline")
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(sample, viewModel.state.value.sample)
        assertTrue(viewModel.state.value.refreshFailed)
        assertNull(viewModel.state.value.errorMessage)

        fake.failOsProcessesWith = null
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(viewModel.state.value.refreshFailed)

        viewModel.stopPolling()
    }

    @Test
    fun `a failure before any sample surfaces the error state and clears on success`() = runTest {
        fake.failOsProcessesWith = IOException("offline")
        val viewModel = createViewModel()

        viewModel.startPolling()
        runCurrent()

        assertTrue(viewModel.state.value.processes.isEmpty())
        assertFalse(viewModel.state.value.isLoading)
        assertNotNull(viewModel.state.value.errorMessage)

        fake.failOsProcessesWith = null
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(sample, viewModel.state.value.sample)
        assertNull(viewModel.state.value.errorMessage)

        viewModel.stopPolling()
    }

    @Test
    fun `stopPolling before startPolling is a no-op`() = runTest {
        val viewModel = createViewModel()

        viewModel.stopPolling()
        runCurrent()

        assertEquals(0, fake.getOsProcessesCalls)
        assertTrue(viewModel.state.value.processes.isEmpty())
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
