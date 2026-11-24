package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.phone.PhoneStatus
import com.idupi.app.data.phone.PhoneStatusReporter
import com.idupi.app.data.settings.InMemorySettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Ajustes > Asistente del teléfono (phone-agent T2): persisted switch, manual
 * send feedback and the duplicate-tap guard. The manual action goes through a
 * real [PhoneStatusReporter] over [FakeIduPiClient], so the test also pins that
 * "Enviar ahora" actually posts instead of only flipping a flag.
 */
class PhoneAgentSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var repository: InMemorySettingsRepository

    private val snapshot = PhoneStatus(
        collectedAt = "2026-11-24T12:00:00Z",
        appVersion = "test",
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        repository = InMemorySettingsRepository()
    }

    private fun viewModel(
        canReport: () -> Boolean = { true },
        enabled: suspend () -> Boolean = { true },
    ): PhoneAgentSettingsViewModel {
        val reporter = PhoneStatusReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { snapshot },
            isEnabled = enabled,
            canReport = canReport,
        )
        return PhoneAgentSettingsViewModel(repository) { reporter.reportNow() }
    }

    @Test
    fun `status reporting is on by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.phoneStatusEnabled.value)
        assertTrue(repository.phoneStatusEnabled.first())
    }

    @Test
    fun `turning the switch off persists`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setPhoneStatusEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.phoneStatusEnabled.value)
        assertFalse(repository.phoneStatusEnabled.first())
    }

    @Test
    fun `the view model follows an external setting change`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.phoneStatusEnabled.value)

        repository.setPhoneStatusEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.phoneStatusEnabled.value)
    }

    @Test
    fun `sendNow posts a snapshot and reports success`() = runTest {
        val viewModel = viewModel()

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, fake.postedPhoneStatuses.size)
        assertEquals(snapshot, fake.postedPhoneStatuses.single())
        assertTrue(viewModel.reportState.value.success == true)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_SENT, viewModel.reportState.value.message)
        assertFalse(viewModel.reportState.value.isSending)
    }

    @Test
    fun `sendNow reports failure when no profile is saved and posts nothing`() = runTest {
        val viewModel = viewModel(canReport = { false })

        viewModel.sendNow()
        advanceUntilIdle()

        assertTrue(fake.postedPhoneStatuses.isEmpty())
        assertEquals(false, viewModel.reportState.value.success)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_FAILED, viewModel.reportState.value.message)
        assertFalse(viewModel.reportState.value.isSending)
    }

    @Test
    fun `sendNow reports a client failure`() = runTest {
        fake.failWith = RuntimeException("pc off")
        val viewModel = viewModel()

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(false, viewModel.reportState.value.success)
        assertEquals(PhoneAgentSettingsViewModel.MESSAGE_FAILED, viewModel.reportState.value.message)
    }

    @Test
    fun `sendNow ignores a second tap while one is in flight`() = runTest {
        var calls = 0
        val viewModel = PhoneAgentSettingsViewModel(repository) {
            calls++
            delay(1_000)
            true
        }

        viewModel.sendNow()
        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue(viewModel.reportState.value.success == true)
    }

    @Test
    fun `clearFeedback removes the previous message`() = runTest {
        val viewModel = viewModel()
        viewModel.sendNow()
        advanceUntilIdle()

        viewModel.clearFeedback()

        assertNull(viewModel.reportState.value.message)
        assertNull(viewModel.reportState.value.success)
        assertFalse(viewModel.reportState.value.isSending)
    }
}
