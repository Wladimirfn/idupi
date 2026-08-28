package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.repository.TerminalSessionItem
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConsoleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init loads terminals and logs from client`() = runTest {
        fake.terminalsToReturn = listOf(TerminalSessionItem(id = "pi-cli", name = "Pi CLI"))
        fake.terminalLogsToReturn = listOf("[System] ready")

        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.terminalsToReturn, viewModel.terminals.value)
        assertEquals(fake.terminalLogsToReturn, viewModel.logs.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshTerminals sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("terminals unavailable")

        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("terminals unavailable"))
    }

    @Test
    fun `sendTerminalCommand failure appends ERROR log line instead of setting errorMessage`() = runTest {
        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("command failed")
        viewModel.sendTerminalCommand("ls")
        advanceUntilIdle()

        assertTrue(viewModel.logs.value.any { it.contains("[ERROR]") })
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `spawnNewTerminal appends ERROR log line when client throws`() = runTest {
        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("spawn failed")
        viewModel.spawnNewTerminal("custom")
        advanceUntilIdle()

        assertTrue(viewModel.logs.value.any { it.contains("[ERROR]") && it.contains("spawn failed") })
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `closeSelectedTerminal appends ERROR log line when client throws`() = runTest {
        fake.terminalsToReturn = listOf(TerminalSessionItem(id = "term-1", name = "Term 1"))
        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals("term-1", viewModel.selectedTerminalId.value)

        fake.failWith = RuntimeException("close failed")
        viewModel.closeSelectedTerminal()
        advanceUntilIdle()

        assertTrue(viewModel.logs.value.any { it.contains("[ERROR]") && it.contains("close failed") })
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `cancelTask appends ERROR log line when client throws`() = runTest {
        val viewModel = ConsoleViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("cancel failed")
        viewModel.cancelTask()
        advanceUntilIdle()

        assertTrue(viewModel.logs.value.any { it.contains("[ERROR]") && it.contains("cancel failed") })
        assertNull(viewModel.errorMessage.value)
    }
}
