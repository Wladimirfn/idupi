package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.ServerStatus
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init loads status from client via refreshStatus`() = runTest {
        fake.statusToReturn = ServerStatus(
            connected = true, pcName = "pc-1", project = "proj-1", agent = "agent-1",
            busy = false, queueSize = 2
        )

        val viewModel = MainViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.statusToReturn, viewModel.status.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshStatus sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("network down")

        val viewModel = MainViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("network down"))
    }

    @Test
    fun `selectEngine sets errorMessage when client throws`() = runTest {
        val viewModel = MainViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        fake.failWith = RuntimeException("engine failure")

        viewModel.selectEngine("engine-2")
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("engine failure"))
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = MainViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }
}
