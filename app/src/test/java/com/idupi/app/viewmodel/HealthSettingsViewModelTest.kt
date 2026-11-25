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
        adbStatus: suspend () -> com.idupi.app.domain.model.HealthAdbStatus? = { null },
        startRefresh: suspend () -> com.idupi.app.domain.model.HealthRefreshJob = {
            com.idupi.app.domain.model.HealthRefreshJob(id = "hgj-0123456789ab", status = "queued")
        },
        pollRefresh: suspend (String) -> com.idupi.app.domain.model.HealthRefreshJob = {
            com.idupi.app.domain.model.HealthRefreshJob(id = it, status = "done")
        },
        maxPollAttempts: Int = 5,
    ) = HealthSettingsViewModel(
        availabilityProvider = availability,
        grantedProvider = granted,
        sourcesProvider = sourcesProvider,
        reportStateFlow = reportState,
        requiredPermissions = required,
        reportNow = reportResult,
        adbStatusProvider = adbStatus,
        startHuaweiRefresh = startRefresh,
        pollHuaweiRefresh = pollRefresh,
        pollDelayMillis = 10,
        maxPollAttempts = maxPollAttempts,
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

    // --- PC hidden read (phone-health T3) ---

    @Test
    fun `refreshAdb loads the PC status without touching the summary`() = runTest {
        var adbCalls = 0
        val vm = viewModel(
            adbStatus = {
                adbCalls += 1
                com.idupi.app.domain.model.HealthAdbStatus(
                    adb = com.idupi.app.domain.model.HealthAdbTool(path = "adb.exe", found = true),
                    scrcpy = com.idupi.app.domain.model.HealthScrcpyTool(found = true, version = "5.0.1"),
                    connected = true,
                    serial = "192.168.1.50:37123",
                )
            },
        )
        advanceUntilIdle()

        vm.refreshAdb()
        advanceUntilIdle()

        val status = vm.state.value.adbStatus
        assertEquals("192.168.1.50:37123", status?.serial)
        assertEquals(true, status?.connected)
        assertEquals(false, vm.state.value.isLoadingAdb)
        assertEquals(1, adbCalls)
    }

    @Test
    fun `a failing adb status keeps the screen usable`() = runTest {
        val vm = viewModel(adbStatus = { throw IllegalStateException("PC down") })
        advanceUntilIdle()

        vm.refreshAdb()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.adbStatus)
        assertEquals(false, vm.state.value.isLoadingAdb)
    }

    @Test
    fun `readHuaweiNow polls, reports the phone side and refreshes the sources`() = runTest {
        var reports = 0
        var sourceCalls = 0
        var polls = 0
        val vm = viewModel(
            sourcesProvider = {
                sourceCalls += 1
                sources
            },
            reportResult = {
                reports += 1
                true
            },
            pollRefresh = { id ->
                polls += 1
                com.idupi.app.domain.model.HealthRefreshJob(
                    id = id,
                    status = if (polls < 2) "running" else "done",
                )
            },
        )
        advanceUntilIdle()
        val before = sourceCalls

        vm.readHuaweiNow()
        advanceUntilIdle()

        assertEquals(2, polls)
        assertEquals(1, reports)
        assertEquals(before + 1, sourceCalls)
        assertEquals(false, vm.state.value.isReadingHuawei)
        assertEquals(HealthSettingsViewModel.MESSAGE_HUAWEI_DONE, vm.feedback.value.message)
        assertEquals(true, vm.feedback.value.success)
    }

    @Test
    fun `readHuaweiNow with a timeout says so`() = runTest {
        val vm = viewModel(
            pollRefresh = { id ->
                com.idupi.app.domain.model.HealthRefreshJob(id = id, status = "running")
            },
            maxPollAttempts = 2,
        )
        advanceUntilIdle()

        vm.readHuaweiNow()
        advanceUntilIdle()

        assertEquals(HealthSettingsViewModel.MESSAGE_HUAWEI_TIMEOUT, vm.feedback.value.message)
        assertEquals(false, vm.feedback.value.success)
        assertEquals(false, vm.state.value.isReadingHuawei)
    }

    @Test
    fun `readHuaweiNow with a failed job offers the failure copy`() = runTest {
        val vm = viewModel(
            pollRefresh = { id ->
                com.idupi.app.domain.model.HealthRefreshJob(id = id, status = "failed")
            },
        )
        advanceUntilIdle()

        vm.readHuaweiNow()
        advanceUntilIdle()

        assertEquals(HealthSettingsViewModel.MESSAGE_HUAWEI_FAILED, vm.feedback.value.message)
        assertEquals(false, vm.feedback.value.success)
    }

    @Test
    fun `two taps while reading huawei start one job`() = runTest {
        var starts = 0
        val vm = viewModel(
            startRefresh = {
                starts += 1
                com.idupi.app.domain.model.HealthRefreshJob(id = "hgj-0123456789ab", status = "queued")
            },
        )
        advanceUntilIdle()

        vm.readHuaweiNow()
        vm.readHuaweiNow()
        advanceUntilIdle()

        assertEquals(1, starts)
    }
}
