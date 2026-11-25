package com.idupi.app.viewmodel

import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthInstalledApp
import com.idupi.app.data.health.HealthReportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Ajustes > SALUD view model (phone-health T2): platform state loading, the
 * permission result, source detection and the manual send, all with injected
 * providers so no Android or Health Connect is involved.
 */
class HealthSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val required = setOf("android.permission.health.READ_STEPS", "android.permission.health.READ_SLEEP")

    private val sources = listOf(
        HealthInstalledApp("com.sec.android.app.shealth", "Samsung Health", "health_connect"),
        HealthInstalledApp("com.huawei.health", "Huawei Health", "hidden_display"),
    )

    private fun viewModel(
        availability: () -> HealthConnectAvailability = { HealthConnectAvailability.AVAILABLE },
        granted: suspend () -> Set<String> = { setOf(required.first()) },
        sourcesProvider: suspend () -> List<HealthInstalledApp> = { sources },
        reportResult: suspend () -> Boolean = { true },
        reportState: MutableStateFlow<HealthReportState> = MutableStateFlow(HealthReportState()),
    ) = HealthSettingsViewModel(
        availabilityProvider = availability,
        grantedProvider = granted,
        sourcesProvider = sourcesProvider,
        reportStateFlow = reportState,
        requiredPermissions = required,
        reportNow = reportResult,
    )

    @Test
    fun `init loads availability, permissions and sources`() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(HealthConnectAvailability.AVAILABLE, state.availability)
        assertEquals(1, state.grantedCount)
        assertEquals(2, state.sources.size)
        assertFalse(state.hasAllPermissions)
        assertFalse(state.isLoadingSources)
    }

    @Test
    fun `without Health Connect no permission query is used`() = runTest {
        var grantedAsked = 0
        val vm = viewModel(
            availability = { HealthConnectAvailability.UNAVAILABLE },
            granted = { grantedAsked++; setOf("unexpected") },
        )

        advanceUntilIdle()

        assertEquals(HealthConnectAvailability.UNAVAILABLE, vm.state.value.availability)
        assertEquals(0, grantedAsked)
        assertTrue(vm.state.value.grantedPermissions.isEmpty())
    }

    @Test
    fun `permission result marks every required grant`() = runTest {
        var granted = setOf(required.first())
        val vm = viewModel(granted = { granted })

        advanceUntilIdle()
        granted = required
        vm.onPermissionResult(granted)
        advanceUntilIdle()

        assertTrue(vm.state.value.hasAllPermissions)
        assertEquals(2, vm.state.value.grantedCount)
    }

    @Test
    fun `a source failure keeps the previous list`() = runTest {
        var fail = false
        val vm = viewModel(
            sourcesProvider = {
                if (fail) throw IllegalStateException("PackageManager down")
                sources
            },
        )
        advanceUntilIdle()

        fail = true
        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.sources.size)
        assertFalse(vm.state.value.isLoadingSources)
    }

    @Test
    fun `sendNow reports success`() = runTest {
        val vm = viewModel()

        vm.sendNow()
        advanceUntilIdle()

        assertFalse(vm.feedback.value.isSending)
        assertEquals(true, vm.feedback.value.success)
        assertEquals(HealthSettingsViewModel.MESSAGE_SENT, vm.feedback.value.message)
    }

    @Test
    fun `sendNow reports failure`() = runTest {
        val vm = viewModel(reportResult = { false })

        vm.sendNow()
        advanceUntilIdle()

        assertEquals(false, vm.feedback.value.success)
        assertEquals(HealthSettingsViewModel.MESSAGE_FAILED, vm.feedback.value.message)
    }

    @Test
    fun `two taps while sending start one report`() = runTest {
        var calls = 0
        val vm = viewModel(
            reportResult = {
                calls++
                delay(1_000)
                true
            },
        )

        vm.sendNow()
        vm.sendNow()
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `the observed report state comes from the reporter`() = runTest {
        val reportState = MutableStateFlow(HealthReportState())
        val vm = viewModel(reportState = reportState)

        reportState.value = HealthReportState(
            lastAttemptAtMillis = 1_000,
            lastSuccessAtMillis = 1_000,
            success = true,
            sampleCount = 7,
        )

        assertEquals(7, vm.reportState.value.sampleCount)
    }
}
