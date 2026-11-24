package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.PtyAgent
import com.idupi.app.domain.model.PtyContext
import com.idupi.app.domain.model.PtyInfo
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

class TerminalsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init maps the listed PTYs into state`() = runTest {
        fake.ptyListToReturn = listOf(
            PtyInfo(id = "pty-1", pid = 100, cols = 80, rows = 24, shell = "pwsh.exe"),
            PtyInfo(id = "pty-2", pid = 101, cols = 120, rows = 40, alive = false, exitCode = 1),
        )

        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(listOf("pty-1", "pty-2"), viewModel.ptys.value.map { it.id })
        assertEquals(120, viewModel.ptys.value[1].cols)
        assertFalse(viewModel.loadFailed.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `list failure surfaces the error state and message`() = runTest {
        fake.failWith = RuntimeException("offline")

        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertTrue(viewModel.loadFailed.value)
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("offline"))
    }

    @Test
    fun `listed terminals keep their live context`() = runTest {
        fake.ptyListToReturn = listOf(
            PtyInfo(
                id = "pty-ctx",
                pid = 300,
                context = PtyContext(
                    cwd = "C:\\repo\\IDUPI",
                    project = "IDUPI",
                    branch = "main",
                    agent = PtyAgent(id = "opencode", name = "OpenCode", pid = 301)
                )
            )
        )

        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val context = viewModel.ptys.value.single().context
        assertEquals("IDUPI", context?.project)
        assertEquals("main", context?.branch)
        assertEquals("OpenCode", context?.agent?.name)
    }

    @Test
    fun `polling refreshes the list and stops when asked`() = runTest {
        fake.ptyListToReturn = listOf(PtyInfo(id = "pty-old"))
        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.ptyListToReturn = listOf(PtyInfo(id = "pty-late"))
        viewModel.startPolling()
        advanceTimeBy(3_100)
        runCurrent()

        assertEquals(listOf("pty-late"), viewModel.ptys.value.map { it.id })

        viewModel.stopPolling()
        val callsAfterStop = fake.listPtysCalls

        fake.ptyListToReturn = listOf(PtyInfo(id = "pty-never"))
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(callsAfterStop, fake.listPtysCalls)
        assertEquals(listOf("pty-late"), viewModel.ptys.value.map { it.id })
    }

    @Test
    fun `recovering from a list failure clears the error state`() = runTest {
        fake.failWith = RuntimeException("offline")
        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertTrue(viewModel.loadFailed.value)

        fake.failWith = null
        fake.ptyListToReturn = listOf(PtyInfo(id = "pty-back"))
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.loadFailed.value)
        assertNull(viewModel.errorMessage.value)
        assertEquals(listOf("pty-back"), viewModel.ptys.value.map { it.id })
    }

    @Test
    fun `createTerminal creates a PTY and publishes its id to open`() = runTest {
        fake.ptyCreateResult = PtyInfo(id = "pty-new", pid = 42, cols = 80, rows = 24)
        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.createTerminal()
        advanceUntilIdle()

        assertEquals("pty-new", viewModel.createdTerminalIds.tryReceive().getOrNull())
        assertEquals(80 to 24, fake.lastCreatedPtyDims)
        assertFalse(viewModel.isCreating.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `createTerminal failure sets the error message and publishes nothing`() = runTest {
        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("no pty")
        viewModel.createTerminal()
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("no pty"))
        assertNull(viewModel.createdTerminalIds.tryReceive().getOrNull())
        assertFalse(viewModel.isCreating.value)
    }

    @Test
    fun `createTerminal with an empty id reports the server error`() = runTest {
        fake.ptyCreateResult = PtyInfo(id = "", error = "spawn helper missing")
        val viewModel = TerminalsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.createTerminal()
        advanceUntilIdle()

        assertEquals("spawn helper missing", viewModel.errorMessage.value)
        assertNull(viewModel.createdTerminalIds.tryReceive().getOrNull())
    }
}
