package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.inbox.InboxCaptureGateway
import com.idupi.app.data.inbox.InboxCaptureState
import com.idupi.app.data.inbox.InboxScanResult
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.InboxConfig
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Ajustes > Alertas view-model behavior (phone-inbox T5): agent config
 * load/selection/save feedback and the persisted phone-side settings.
 */
class InboxSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var repository: InMemorySettingsRepository

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        repository = InMemorySettingsRepository()
    }

    private fun viewModel(
        gateway: InboxCaptureGateway = FakeInboxCaptureGateway()
    ) = InboxSettingsViewModel(FakeClientSource(fake), repository, gateway)

    private fun config(
        agent: String = "opencode",
        model: String = "opencode-go/deepseek-v4.1-flash",
        effort: String = "max",
    ) = InboxConfig(
        agent = agent,
        model = model,
        effort = effort,
        agents = listOf("opencode", "claude", "codex", "pi"),
        efforts = listOf("low", "medium", "high", "max")
    )

    @Test
    fun `loadConfig populates agent model effort and options`() = runTest {
        fake.inboxConfigToReturn = config(agent = "claude", model = "sonnet", effort = "high")
        fake.inboxModelsToReturn = emptyList()

        val viewModel = viewModel()
        viewModel.loadConfig()
        advanceUntilIdle()

        val state = viewModel.agentConfig.value
        assertEquals("claude", state.agent)
        assertEquals("sonnet", state.model)
        assertEquals("high", state.effort)
        assertEquals(listOf("opencode", "claude", "codex", "pi"), state.agents)
        assertEquals(listOf("low", "medium", "high", "max"), state.efforts)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `loadConfig fetches the opencode model catalog`() = runTest {
        fake.inboxConfigToReturn = config(agent = "opencode")
        fake.inboxModelsToReturn = listOf("opencode-go/deepseek-v4.1-flash", "opencode-go/other")

        val viewModel = viewModel()
        viewModel.loadConfig()
        advanceUntilIdle()

        assertEquals("opencode", fake.lastRequestedInboxModelsAgent)
        assertEquals(fake.inboxModelsToReturn, viewModel.agentConfig.value.models)
    }

    @Test
    fun `loadConfig only refetches when forced`() = runTest {
        fake.inboxConfigToReturn = config(agent = "opencode")
        val viewModel = viewModel()
        viewModel.loadConfig()
        advanceUntilIdle()

        fake.inboxConfigToReturn = config(agent = "claude", model = "sonnet", effort = "low")
        viewModel.loadConfig()
        advanceUntilIdle()
        assertEquals("opencode", viewModel.agentConfig.value.agent)

        viewModel.loadConfig(force = true)
        advanceUntilIdle()
        assertEquals("claude", viewModel.agentConfig.value.agent)
    }

    @Test
    fun `loadConfig failure reports an error`() = runTest {
        fake.failWith = RuntimeException("pc off")

        val viewModel = viewModel()
        viewModel.loadConfig()
        advanceUntilIdle()

        assertNotNull(viewModel.agentConfig.value.errorMessage)
        assertTrue(viewModel.agentConfig.value.errorMessage!!.contains("pc off"))
        assertFalse(viewModel.agentConfig.value.isLoading)
    }

    @Test
    fun `selecting opencode loads its models`() = runTest {
        fake.inboxModelsToReturn = listOf("model-a")
        val viewModel = viewModel()

        viewModel.setAgent("opencode")
        advanceUntilIdle()

        assertEquals("opencode", fake.lastRequestedInboxModelsAgent)
        assertEquals(listOf("model-a"), viewModel.agentConfig.value.models)
    }

    @Test
    fun `selecting another agent clears the opencode catalog`() = runTest {
        fake.inboxModelsToReturn = listOf("model-a")
        val viewModel = viewModel()
        viewModel.setAgent("opencode")
        advanceUntilIdle()
        assertTrue(viewModel.agentConfig.value.models.isNotEmpty())

        viewModel.setAgent("claude")
        advanceUntilIdle()

        assertTrue(viewModel.agentConfig.value.models.isEmpty())
    }

    @Test
    fun `saveConfig sends agent model and effort and reports success`() = runTest {
        val viewModel = viewModel()
        viewModel.setAgent("claude")
        viewModel.setModel("sonnet")
        viewModel.setEffort("high")

        viewModel.saveConfig()
        advanceUntilIdle()

        assertEquals(Triple("claude", "sonnet", "high"), fake.lastInboxConfigUpdate)
        assertEquals("Configuración guardada", viewModel.agentConfig.value.successMessage)
        assertNull(viewModel.agentConfig.value.errorMessage)
        assertFalse(viewModel.agentConfig.value.isSaving)
    }

    @Test
    fun `saveConfig rejects an empty model without calling the client`() = runTest {
        val viewModel = viewModel()
        viewModel.setAgent("claude")
        viewModel.setModel("")

        viewModel.saveConfig()
        advanceUntilIdle()

        assertNull(fake.lastInboxConfigUpdate)
        assertNotNull(viewModel.agentConfig.value.errorMessage)
        assertTrue(viewModel.agentConfig.value.errorMessage!!.contains("Completa"))
    }

    @Test
    fun `saveConfig failure reports an error`() = runTest {
        val viewModel = viewModel()
        viewModel.setModel("sonnet")
        fake.failWith = RuntimeException("save rejected")

        viewModel.saveConfig()
        advanceUntilIdle()

        assertNotNull(viewModel.agentConfig.value.errorMessage)
        assertTrue(viewModel.agentConfig.value.errorMessage!!.contains("save rejected"))
        assertNull(viewModel.agentConfig.value.successMessage)
    }

    @Test
    fun `app toggles persist in the repository`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setAppEnabled("gmail", false)
        advanceUntilIdle()

        assertFalse(repository.inboxSettings.first().enabledApps.contains("gmail"))
        assertEquals(
            InboxSettings().enabledApps - "gmail",
            repository.inboxSettings.first().enabledApps
        )
    }

    @Test
    fun `reminder and importance settings persist`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setReminderInterval(30)
        viewModel.setRemindMinImportance(InboxImportance.MEDIUM)
        advanceUntilIdle()

        val stored = repository.inboxSettings.first()
        assertEquals(30, stored.reminderIntervalMinutes)
        assertEquals(InboxImportance.MEDIUM, stored.remindMinImportance)
    }

    @Test
    fun `auto-reply settings persist`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setAutoReplyEnabled(true)
        viewModel.setAutoReplyDelay(60)
        viewModel.setAutoReplyTemplate("Te contesto más tarde.")
        advanceUntilIdle()

        val stored = repository.inboxSettings.first()
        assertTrue(stored.autoReplyEnabled)
        assertEquals(60, stored.autoReplyDelayMinutes)
        assertEquals("Te contesto más tarde.", stored.autoReplyTemplate)
    }

    @Test
    fun `inboxSettings state follows the repository`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setReminderInterval(10)
        advanceUntilIdle()

        assertEquals(10, viewModel.inboxSettings.value.reminderIntervalMinutes)
    }

    // --- Capture reliability (inbox capture fix T1) ---

    @Test
    fun `scanNow reports how many notifications were sent`() = runTest {
        val gateway = FakeInboxCaptureGateway().apply {
            scanResult = InboxScanResult.Completed(captured = 3, sent = 2)
        }
        val viewModel = viewModel(gateway)

        viewModel.scanNow()
        advanceUntilIdle()

        assertEquals(1, gateway.scanCalls)
        assertEquals("2 notificaciones enviadas", viewModel.captureAction.value.scanMessage)
        assertFalse(viewModel.captureAction.value.isScanning)
    }

    @Test
    fun `scanNow uses the singular for one notification`() = runTest {
        val gateway = FakeInboxCaptureGateway().apply {
            scanResult = InboxScanResult.Completed(captured = 1, sent = 1)
        }
        val viewModel = viewModel(gateway)

        viewModel.scanNow()
        advanceUntilIdle()

        assertEquals("1 notificación enviada", viewModel.captureAction.value.scanMessage)
    }

    @Test
    fun `scanNow reports a disconnected listener`() = runTest {
        val gateway = FakeInboxCaptureGateway().apply {
            scanResult = InboxScanResult.NotConnected
        }
        val viewModel = viewModel(gateway)

        viewModel.scanNow()
        advanceUntilIdle()

        assertEquals("Lector no conectado", viewModel.captureAction.value.scanMessage)
    }

    @Test
    fun `scanNow shows progress and ignores a second tap while running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeInboxCaptureGateway().apply {
            scanGate = gate
            scanResult = InboxScanResult.Completed(captured = 1, sent = 1)
        }
        val viewModel = viewModel(gateway)

        viewModel.scanNow()
        advanceUntilIdle()
        assertTrue(viewModel.captureAction.value.isScanning)
        assertEquals(1, gateway.scanCalls)

        viewModel.scanNow()
        assertEquals(1, gateway.scanCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.captureAction.value.isScanning)
        assertEquals("1 notificación enviada", viewModel.captureAction.value.scanMessage)
    }

    @Test
    fun `scanNow failure keeps the button usable and reports a message`() = runTest {
        val gateway = FakeInboxCaptureGateway().apply {
            scanError = IllegalStateException("boom")
        }
        val viewModel = viewModel(gateway)

        viewModel.scanNow()
        advanceUntilIdle()

        assertEquals("No se pudo leer las notificaciones", viewModel.captureAction.value.scanMessage)
        assertFalse(viewModel.captureAction.value.isScanning)
    }

    @Test
    fun `capture status mirrors the gateway`() = runTest {
        val gateway = FakeInboxCaptureGateway()
        val viewModel = viewModel(gateway)

        gateway.status.value = InboxCaptureState(
            listenerConnected = true,
            lastCaptureApp = "whatsapp",
            pendingRetryCount = 2
        )

        assertTrue(viewModel.captureStatus.value.listenerConnected)
        assertEquals("whatsapp", viewModel.captureStatus.value.lastCaptureApp)
        assertEquals(2, viewModel.captureStatus.value.pendingRetryCount)
    }

    @Test
    fun `refreshCaptureStatus asks the gateway for the pending count`() = runTest {
        val gateway = FakeInboxCaptureGateway()
        val viewModel = viewModel(gateway)

        viewModel.refreshCaptureStatus()
        advanceUntilIdle()

        assertEquals(1, gateway.pendingRefreshCalls)
    }
}

/** Test double for the capture seam; no Android service involved. */
private class FakeInboxCaptureGateway : InboxCaptureGateway {
    override val status = MutableStateFlow(InboxCaptureState())
    var scanResult: InboxScanResult = InboxScanResult.Completed(captured = 0, sent = 0)
    var scanError: Exception? = null
    var scanGate: CompletableDeferred<Unit>? = null
    var scanCalls = 0
    var pendingRefreshCalls = 0

    override suspend fun scanNow(): InboxScanResult {
        scanCalls++
        scanGate?.await()
        scanError?.let { throw it }
        return scanResult
    }

    override suspend fun refreshPendingCount() {
        pendingRefreshCalls++
    }
}
