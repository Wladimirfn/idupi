package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.AlertCategory
import com.idupi.app.domain.model.AlertSeverity
import com.idupi.app.domain.model.SupervisorAlert
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AlertsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun sampleAlert(id: String = "a1") = SupervisorAlert(
        id = id,
        title = "Test alert",
        description = "Something happened",
        severity = AlertSeverity.WARNING,
        category = AlertCategory.GOVERNANCE_GATE,
        timestamp = "now"
    )

    @Test
    fun `init loads alerts from client`() = runTest {
        fake.alertsToReturn = listOf(sampleAlert())

        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.alertsToReturn, viewModel.alerts.value)
        assertEquals(false, viewModel.isLoading.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshAlerts sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("alerts unavailable")

        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("alerts unavailable"))
        assertTrue(viewModel.alerts.value.isEmpty())
    }

    @Test
    fun `markAsRead delegates to client with the given id`() = runTest {
        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.markAsRead("a1")
        advanceUntilIdle()

        assertEquals("a1", fake.lastMarkedAlertId)
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `markAsRead sets errorMessage when client throws`() = runTest {
        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("mark as read failed")
        viewModel.markAsRead("a1")
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("mark as read failed"))
    }

    @Test
    fun `approvePlan sets errorMessage when client throws`() = runTest {
        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("approve failed")
        viewModel.approvePlan("plan1")
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("approve failed"))
    }

    @Test
    fun `rejectPlan sets errorMessage when client throws`() = runTest {
        val viewModel = AlertsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("reject failed")
        viewModel.rejectPlan("plan1", "not needed")
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("reject failed"))
    }
}
