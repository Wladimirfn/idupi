package com.idupi.app.viewmodel

import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.calendar.CalendarPermissionState
import com.idupi.app.data.calendar.CalendarReportState
import com.idupi.app.data.settings.InMemorySettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

class CalendarSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: InMemorySettingsRepository
    private lateinit var reportState: MutableStateFlow<CalendarReportState>
    private var permission = CalendarPermissionState(canRead = false, canWrite = false)

    @Before
    fun setUp() {
        repository = InMemorySettingsRepository()
        reportState = MutableStateFlow(CalendarReportState())
        permission = CalendarPermissionState(canRead = false, canWrite = false)
    }

    private fun viewModel(reportNow: suspend () -> Boolean = { true }) =
        CalendarSettingsViewModel(
            settingsRepository = repository,
            reportStateFlow = reportState,
            permissionProvider = { permission },
            reportNow = reportNow,
        )

    @Test
    fun `sharing is on by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.shareEnabled.value)
        assertTrue(repository.calendarShareEnabled.first())
    }

    @Test
    fun `turning sharing off persists`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setShareEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.shareEnabled.value)
        assertFalse(repository.calendarShareEnabled.first())
    }

    @Test
    fun `permission state starts from the platform provider`() = runTest {
        permission = CalendarPermissionState(canRead = true, canWrite = false)
        val viewModel = viewModel()

        assertTrue(viewModel.permission.value.canRead)
        assertFalse(viewModel.permission.value.canWrite)
        assertFalse(viewModel.permission.value.granted)
    }

    @Test
    fun `resume refreshes the platform permission`() = runTest {
        val viewModel = viewModel()
        assertFalse(viewModel.permission.value.canRead)

        permission = CalendarPermissionState(canRead = true, canWrite = true)
        viewModel.refreshPermission()

        assertTrue(viewModel.permission.value.granted)
    }

    @Test
    fun `updatePermission reflects the launcher result`() = runTest {
        val viewModel = viewModel()

        viewModel.updatePermission(CalendarPermissionState(canRead = true, canWrite = false))

        assertTrue(viewModel.permission.value.canRead)
        assertFalse(viewModel.permission.value.granted)
    }

    @Test
    fun `sendNow reports success`() = runTest {
        var calls = 0
        val viewModel = viewModel { calls++; true }

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue(viewModel.feedback.value.success == true)
        assertEquals(CalendarSettingsViewModel.MESSAGE_SENT, viewModel.feedback.value.message)
        assertFalse(viewModel.feedback.value.isSending)
    }

    @Test
    fun `sendNow reports failure`() = runTest {
        val viewModel = viewModel { false }

        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(false, viewModel.feedback.value.success)
        assertEquals(CalendarSettingsViewModel.MESSAGE_FAILED, viewModel.feedback.value.message)
    }

    @Test
    fun `sendNow ignores a second tap while one is in flight`() = runTest {
        var calls = 0
        val viewModel = viewModel {
            calls++
            delay(1_000)
            true
        }

        viewModel.sendNow()
        viewModel.sendNow()
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `the last report state is exposed from the reporter`() = runTest {
        val viewModel = viewModel()
        val state = CalendarReportState(
            lastAttemptAtMillis = 10L,
            lastSuccessAtMillis = 10L,
            success = true,
            eventCount = 3,
        )

        reportState.value = state

        assertEquals(state, viewModel.reportState.value)
    }

    @Test
    fun `clearFeedback removes the previous message`() = runTest {
        val viewModel = viewModel()
        viewModel.sendNow()
        advanceUntilIdle()

        viewModel.clearFeedback()

        assertNull(viewModel.feedback.value.message)
        assertNull(viewModel.feedback.value.success)
        assertFalse(viewModel.feedback.value.isSending)
    }
}
