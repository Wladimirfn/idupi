package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.PtyStreamCall
import com.idupi.app.domain.model.PtyAgent
import com.idupi.app.domain.model.PtyContext
import com.idupi.app.domain.model.PtyInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PtyTerminalViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `creates a terminal, forwards output and coalesces input in order`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateResult = PtyInfo(id = "pty-7", pid = 42, cols = 100, rows = 30, alive = true)
            ptyStreamScript.addLast(PtyStreamCall(listOf("hello".toByteArray()), hang = true))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        viewModel.onViewportReady(100, 30)
        advanceUntilIdle()

        assertEquals(PtyConnectionState.CONNECTED, viewModel.uiState.value.state)
        assertEquals("pty-7", viewModel.uiState.value.terminalId)
        assertEquals(100 to 30, fake.lastCreatedPtyDims)
        // The PTY is aligned to the fitted viewport right after creation.
        assertEquals(Triple("pty-7", 100, 30), fake.ptyResizes.first())
        // Raw bytes go to the WebView as base64 of the original chunk.
        assertEquals("aGVsbG8=", viewModel.output.tryReceive().getOrNull())

        // A burst inside the coalescing window leaves as ONE ordered POST.
        viewModel.onInput("l")
        viewModel.onInput("s")
        viewModel.onInput("\r")
        advanceUntilIdle()
        assertEquals(listOf("pty-7" to "ls\r"), fake.ptyInputs)

        viewModel.detach()
    }

    @Test
    fun `a resize that lands while the terminal is being created is not lost`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateResult = PtyInfo(id = "pty-3", alive = true)
            ptyCreateDelayMs = 500
            ptyStreamScript.addLast(PtyStreamCall(emptyList(), hang = true))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        // First layout pass: the WebView is not laid out yet (1 row).
        viewModel.onViewportReady(47, 1)
        runCurrent()
        // Real size arrives while the create request is still in flight.
        viewModel.onResize(47, 38)
        advanceUntilIdle()

        assertEquals(47 to 1, fake.lastCreatedPtyDims)
        assertEquals(Triple("pty-3", 47, 38), fake.ptyResizes.last())

        viewModel.detach()
    }

    @Test
    fun `attaching resizes and reports the exit code of a dead terminal`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyStreamScript.addLast(PtyStreamCall(emptyList()))
            ptyListToReturn = listOf(PtyInfo(id = "pty-9", pid = 7, alive = false, exitCode = 3))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare("pty-9")
        viewModel.onViewportReady(90, 30)
        advanceUntilIdle()

        // Attach must not create a second terminal.
        assertNull(fake.lastCreatedPtyDims)
        assertEquals(Triple("pty-9", 90, 30), fake.ptyResizes.first())
        assertEquals(PtyConnectionState.EXITED, viewModel.uiState.value.state)
        assertEquals(3, viewModel.uiState.value.exitCode)
        assertEquals("pty-9", viewModel.uiState.value.terminalId)

        viewModel.detach()
    }

    @Test
    fun `an ended stream with a live terminal enters reconnecting with backoff`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateResult = PtyInfo(id = "pty-1", alive = true)
            ptyStreamScript.addLast(PtyStreamCall(emptyList()))
            ptyListToReturn = listOf(PtyInfo(id = "pty-1", alive = true))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(PtyConnectionState.RECONNECTING, viewModel.uiState.value.state)
        assertEquals(1, fake.ptyStreamCalls)

        // 500 ms later the ViewModel opens the stream again (replay path).
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, fake.ptyStreamCalls)

        viewModel.detach()
    }

    @Test
    fun `kill asks the client to tree-kill and signals the screen to close`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateResult = PtyInfo(id = "pty-5", alive = true)
            ptyStreamScript.addLast(PtyStreamCall(listOf("ok".toByteArray()), hang = true))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceUntilIdle()

        viewModel.kill()
        advanceUntilIdle()

        assertEquals("pty-5", fake.lastKilledPtyId)
        assertEquals(PtyTerminalEvent.Closed, viewModel.events.tryReceive().getOrNull())
    }

    @Test
    fun `kill during a create in flight tree-kills the freshly created terminal`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateDelayMs = 100
            ptyCreateResult = PtyInfo(id = "pty-race", alive = true)
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceTimeBy(50)
        runCurrent()

        // The terminal exists on the PC but its id is not back yet: kill must
        // follow through once the create answers (no orphan process).
        viewModel.kill()
        advanceUntilIdle()

        assertEquals("pty-race", fake.lastKilledPtyId)
        assertEquals(PtyTerminalEvent.Closed, viewModel.events.tryReceive().getOrNull())
    }

    @Test
    fun `a kill event does not leak into the next terminal session`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyCreateResult = PtyInfo(id = "pty-1", alive = true)
            ptyStreamScript.addLast(PtyStreamCall(listOf("ok".toByteArray()), hang = true))
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))
        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceUntilIdle()

        // Kill and leave immediately: nobody consumes the event on the way out.
        viewModel.kill()
        advanceUntilIdle()
        viewModel.detach()

        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceUntilIdle()

        assertNull(viewModel.events.tryReceive().getOrNull())
        viewModel.detach()
    }

    @Test
    fun `header refresh exposes the attached terminal context`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyListToReturn = listOf(
                PtyInfo(
                    id = "pty-9",
                    pid = 7,
                    shell = "powershell.exe -NoLogo",
                    context = PtyContext(
                        cwd = "C:\\work\\IDUPI",
                        project = "IDUPI",
                        branch = "main",
                        agent = PtyAgent(id = "claude", name = "Claude Code", pid = 99)
                    )
                )
            )
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare("pty-9")
        viewModel.refreshHeader()
        advanceUntilIdle()

        val header = viewModel.headerInfo.value
        assertEquals("IDUPI", header?.context?.project)
        assertEquals("main", header?.context?.branch)
        assertEquals("Claude Code", header?.context?.agent?.name)
        assertEquals(1, fake.listPtysCalls)

        viewModel.detach()
    }

    @Test
    fun `a failed header refresh keeps the last known context`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyListToReturn = listOf(
                PtyInfo(id = "pty-9", context = PtyContext(project = "IDUPI"))
            )
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare("pty-9")
        viewModel.refreshHeader()
        advanceUntilIdle()
        assertEquals("IDUPI", viewModel.headerInfo.value?.context?.project)

        fake.failWith = RuntimeException("offline")
        viewModel.refreshHeader()
        advanceUntilIdle()

        assertEquals("IDUPI", viewModel.headerInfo.value?.context?.project)

        viewModel.detach()
    }

    @Test
    fun `preparing another terminal clears the previous header`() = runTest {
        val fake = FakeIduPiClient().apply {
            ptyListToReturn = listOf(
                PtyInfo(id = "pty-9", context = PtyContext(project = "IDUPI"))
            )
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare("pty-9")
        viewModel.refreshHeader()
        advanceUntilIdle()

        viewModel.prepare("pty-10")

        assertNull(viewModel.headerInfo.value)
    }

    @Test
    fun `a failing create surfaces an actionable error state`() = runTest {
        val fake = FakeIduPiClient().apply {
            failWith = RuntimeException("sin conexión")
        }
        val viewModel = PtyTerminalViewModel(FakeClientSource(fake))

        viewModel.prepare(null)
        viewModel.onViewportReady(80, 24)
        advanceUntilIdle()

        assertEquals(PtyConnectionState.ERROR, viewModel.uiState.value.state)
        assertEquals("sin conexión", viewModel.uiState.value.error)

        viewModel.detach()
    }
}
