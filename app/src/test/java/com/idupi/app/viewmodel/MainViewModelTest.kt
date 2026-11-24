package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.ServerStatus
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
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

    private fun createViewModel(settings: InMemorySettingsRepository = InMemorySettingsRepository()) =
        MainViewModel(FakeClientSource(fake), settings)

    @Test
    fun `init loads status from client via refreshStatus`() = runTest {
        fake.statusToReturn = ServerStatus(
            connected = true, pcName = "pc-1", project = "proj-1", agent = "agent-1",
            busy = false, queueSize = 2
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(fake.statusToReturn, viewModel.status.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshStatus sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("network down")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("network down"))
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `theme settings are exposed from the repository and setters persist`() = runTest {
        val settings = InMemorySettingsRepository()
        val viewModel = createViewModel(settings)
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setThemePreset(ThemePreset.AMBER)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.themeSettings.value.mode)
        assertEquals(ThemePreset.AMBER, viewModel.themeSettings.value.preset)
        assertEquals(ThemeMode.LIGHT, settings.themeSettings.first().mode)
    }
}
