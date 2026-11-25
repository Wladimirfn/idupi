package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.InboxConfig
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxSettings
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

    private fun viewModel() = InboxSettingsViewModel(FakeClientSource(fake), repository)

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
        assertTrue(viewModel.agentConfig.value.errorMessage!!.contains("Completá"))
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
}
