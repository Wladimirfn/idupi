package com.idupi.app.viewmodel

import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.health.HealthMetrics
import com.idupi.app.domain.model.HealthDaySummary
import com.idupi.app.domain.model.HealthDigest
import com.idupi.app.domain.model.HealthRefreshJob
import com.idupi.app.domain.model.HealthSummary
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

/**
 * Salud view model (phone-health T3): the initial load, "Actualizar ahora"
 * polling (done/failed/timeout), the digest states and the phone-side report,
 * all with injected fakes so no Android, Ktor or agent is involved.
 */
class HealthSaludViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneOffset.ofHours(-3)
    private val nowMillis = 1_795_532_400_000L // 2026-11-24T15:00:00Z, 12:00 at -03
    private val today = "2026-11-24"

    private val richSummary = HealthSummary(
        rangeDays = 7,
        tzOffsetMinutes = -180,
        days = (18..24).map { day ->
            HealthDaySummary(
                date = "2026-11-$day",
                metrics = if (day == 24) {
                    HealthMetrics(steps = 12400, sleepMinutes = 480)
                } else {
                    HealthMetrics(steps = 5000 + day * 100)
                },
                sources = mapOf("steps" to "huawei_health"),
            )
        },
        latest = null,
    )

    private fun viewModel(
        loadSummary: suspend (Int) -> HealthSummary = { richSummary },
        loadDigest: suspend (String?) -> HealthDigest = { HealthDigest(date = today) },
        requestDigest: suspend (Boolean) -> HealthDigest = { HealthDigest(date = today) },
        startRefresh: suspend () -> HealthRefreshJob = {
            HealthRefreshJob(id = "hgj-0123456789ab", status = "queued")
        },
        pollRefresh: suspend (String) -> HealthRefreshJob = {
            HealthRefreshJob(id = it, status = "done")
        },
        reportPhoneSide: suspend () -> Boolean = { true },
        maxPollAttempts: Int = 5,
        maxDigestPollAttempts: Int = 5,
    ) = HealthSaludViewModel(
        loadSummary = loadSummary,
        loadDigest = loadDigest,
        requestDigest = requestDigest,
        startRefresh = startRefresh,
        pollRefresh = pollRefresh,
        reportPhoneSide = reportPhoneSide,
        zone = zone,
        nowMillis = { nowMillis },
        pollDelayMillis = 10,
        digestPollDelayMillis = 10,
        maxPollAttempts = maxPollAttempts,
        maxDigestPollAttempts = maxDigestPollAttempts,
    )

    @Test
    fun `init loads the summary into cards and trends`() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(today, state.view.dateKey)
        assertEquals("martes 24 de noviembre", state.view.dateLabel)
        assertTrue(state.view.hasData)
        assertEquals("12,400", state.view.cards.first { it.key == "steps" }.value)
        assertEquals("8 h", state.view.cards.first { it.key == "sleep" }.value)
        assertTrue(state.view.trends.isNotEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun `a failing summary keeps the previous view and surfaces one error`() = runTest {
        var fail = false
        val vm = viewModel(
            loadSummary = {
                if (fail) throw IllegalStateException("PC down")
                richSummary
            },
            loadDigest = { throw IllegalStateException("PC down") },
        )
        advanceUntilIdle()

        fail = true
        vm.refresh()
        advanceUntilIdle()

        assertEquals("12,400", vm.state.value.view.cards.first { it.key == "steps" }.value)
        assertEquals(HealthSaludViewModel.MESSAGE_LOAD_FAILED, vm.state.value.errorMessage)
    }

    @Test
    fun `refreshNow polls until done, reports phone side and reloads`() = runTest {
        var summaryCalls = 0
        var reportCalls = 0
        var polls = 0
        val vm = viewModel(
            loadSummary = {
                summaryCalls += 1
                richSummary
            },
            reportPhoneSide = {
                reportCalls += 1
                true
            },
            pollRefresh = { id ->
                polls += 1
                if (polls < 2) {
                    HealthRefreshJob(id = id, status = "running")
                } else {
                    HealthRefreshJob(id = id, status = "done")
                }
            },
        )
        advanceUntilIdle()
        val loadsBefore = summaryCalls

        vm.refreshNow()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, polls)
        assertEquals(1, reportCalls)
        assertEquals(loadsBefore + 1, summaryCalls)
        assertFalse(state.isRefreshing)
        assertNull(state.refreshMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `refreshNow with a failed job surfaces the retry message and still reloads`() = runTest {
        var loads = 0
        val vm = viewModel(
            loadSummary = {
                loads += 1
                richSummary
            },
            pollRefresh = { id -> HealthRefreshJob(id = id, status = "failed") },
        )
        advanceUntilIdle()
        val before = loads

        vm.refreshNow()
        advanceUntilIdle()

        assertEquals(before + 1, loads)
        assertEquals(HealthSaludViewModel.MESSAGE_REFRESH_FAILED, vm.state.value.errorMessage)
        assertFalse(vm.state.value.isRefreshing)
        assertNull(vm.state.value.refreshMessage)
    }

    @Test
    fun `refreshNow that never settles times out and reports it`() = runTest {
        val vm = viewModel(
            pollRefresh = { id -> HealthRefreshJob(id = id, status = "running") },
            maxPollAttempts = 3,
        )
        advanceUntilIdle()

        vm.refreshNow()
        advanceUntilIdle()

        assertEquals(HealthSaludViewModel.MESSAGE_REFRESH_TIMEOUT, vm.state.value.errorMessage)
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun `refreshNow shows the reading message while the job runs`() = runTest {
        val vm = viewModel(
            pollRefresh = { id ->
                HealthRefreshJob(id = id, status = "done")
            },
        )
        advanceUntilIdle()

        vm.refreshNow()
        // Before the coroutine settles the state announces what the PC is doing.
        assertEquals(HealthSaludViewModel.MESSAGE_READING, vm.state.value.refreshMessage)
        advanceUntilIdle()
        assertNull(vm.state.value.refreshMessage)
    }

    @Test
    fun `refreshNow is a no-op while one run is in flight`() = runTest {
        var starts = 0
        val vm = viewModel(
            startRefresh = {
                starts += 1
                HealthRefreshJob(id = "hgj-0123456789ab", status = "queued")
            },
        )
        advanceUntilIdle()

        vm.refreshNow()
        vm.refreshNow()
        advanceUntilIdle()

        assertEquals(1, starts)
    }

    @Test
    fun `generateDigest polls analyzing until ready`() = runTest {
        var digestLoads = 0
        val vm = viewModel(
            requestDigest = { HealthDigest(date = today, status = "analyzing") },
            loadDigest = { _ ->
                digestLoads += 1
                if (digestLoads < 2) {
                    HealthDigest(date = today, status = "analyzing")
                } else {
                    HealthDigest(date = today, status = "ready", summary = "Hoy caminaste más.")
                }
            },
        )
        advanceUntilIdle()

        vm.generateDigest()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("ready", state.digest.status)
        assertEquals("Hoy caminaste más.", state.digest.summary)
        assertFalse(state.isDigestBusy)
        assertEquals(2, digestLoads)
    }

    @Test
    fun `a failed digest keeps the error and offers retry copy`() = runTest {
        val vm = viewModel(
            requestDigest = {
                HealthDigest(
                    date = today,
                    status = "failed",
                    error = "agent timed out after 180000 ms",
                    available = true,
                )
            },
        )
        advanceUntilIdle()

        vm.generateDigest()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("failed", state.digest.status)
        assertEquals("agent timed out after 180000 ms", state.digest.error)
        assertFalse(state.isDigestBusy)
        assertNull(state.digestMessage)
    }

    @Test
    fun `a digest request that throws shows the retry message`() = runTest {
        val vm = viewModel(
            requestDigest = { throw IllegalStateException("PC down") },
        )
        advanceUntilIdle()

        vm.generateDigest()
        advanceUntilIdle()

        assertEquals(HealthSaludViewModel.MESSAGE_DIGEST_FAILED, vm.state.value.digestMessage)
        assertFalse(vm.state.value.isDigestBusy)
    }

    @Test
    fun `generateDigest is a no-op while the agent runs`() = runTest {
        var requests = 0
        val vm = viewModel(
            requestDigest = {
                requests += 1
                HealthDigest(date = today, status = "analyzing")
            },
            loadDigest = { HealthDigest(date = today, status = "analyzing") },
        )
        advanceUntilIdle()

        vm.generateDigest()
        vm.generateDigest()
        advanceUntilIdle()

        assertEquals(1, requests)
    }

    @Test
    fun `clearError and clearDigestMessage reset their streams`() = runTest {
        val vm = viewModel(loadSummary = { throw IllegalStateException("down") }, loadDigest = { throw IllegalStateException("down") })
        advanceUntilIdle()
        assertEquals(HealthSaludViewModel.MESSAGE_LOAD_FAILED, vm.state.value.errorMessage)

        vm.clearError()
        assertNull(vm.state.value.errorMessage)
    }
}
