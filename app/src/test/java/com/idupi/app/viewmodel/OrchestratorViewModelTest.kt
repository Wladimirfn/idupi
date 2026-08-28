package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.ClaudePhaseConfig
import com.idupi.app.domain.model.OpenCodeModelAssignment
import com.idupi.app.domain.model.OrchestratorActionResponse
import com.idupi.app.domain.model.OrchestratorStatus
import com.idupi.app.domain.model.SddStatusInfo
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OrchestratorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init loads orchestrator status from client`() = runTest {
        val sampleStatus = OrchestratorStatus(
            persona = "gentleman",
            preset = "full-gentleman",
            sddStatus = SddStatusInfo(applyState = "active"),
            claudePhaseAssignments = mapOf("sdd-apply" to ClaudePhaseConfig(model = "sonnet")),
            modelAssignments = mapOf("sdd-apply" to OpenCodeModelAssignment(model_id = "hy3"))
        )
        fake.orchestratorStatusToReturn = sampleStatus

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(sampleStatus, viewModel.status.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `updateModel delegates to client and refreshes status on success`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var onSuccessCalled = false
        viewModel.updateModel(
            engine = "opencode",
            phase = "sdd-tasks",
            modelId = "gpt-5.6-luna",
            providerId = "opencode-go",
            effort = "high",
            onSuccess = { onSuccessCalled = true }
        )
        advanceUntilIdle()

        assertEquals("opencode", fake.lastUpdatedOrchestratorEngine)
        assertEquals("sdd-tasks", fake.lastUpdatedOrchestratorPhase)
        assertEquals("gpt-5.6-luna", fake.lastUpdatedOrchestratorModelId)
        assertEquals(true, onSuccessCalled)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `runAction calls client and records actionOutput`() = runTest {
        fake.runOrchestratorActionResult = OrchestratorActionResponse(
            status = "ok",
            action = "doctor",
            output = "Summary: 6 passed, 0 failed"
        )

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.runAction("doctor")
        advanceUntilIdle()

        assertEquals("doctor", fake.lastRunOrchestratorAction)
        assertEquals("Summary: 6 passed, 0 failed", viewModel.actionOutput.value)
        assertEquals(false, viewModel.isActionRunning.value)
    }

    @Test
    fun `selectTab changes activeTab`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(OrchestratorTab.SDD_PHASES, viewModel.activeTab.value)

        viewModel.selectTab(OrchestratorTab.OPENCODE_MODELS)
        assertEquals(OrchestratorTab.OPENCODE_MODELS, viewModel.activeTab.value)

        viewModel.selectTab(OrchestratorTab.ECOSYSTEM_TOOLS)
        assertEquals(OrchestratorTab.ECOSYSTEM_TOOLS, viewModel.activeTab.value)
    }

    @Test
    fun `applyProfile delegates to client and refreshes on success`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var onSuccessCalled = false
        viewModel.applyProfile("strong", onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertEquals("strong", fake.lastAppliedProfileId)
        assertTrue(onSuccessCalled)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `saveProfile delegates to client and invokes callback on success`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val sampleProf = com.idupi.app.domain.model.SddProfileItem(
            id = "mi-perfil",
            name = "Mi Perfil DeepSeek"
        )
        var onSuccessCalled = false
        viewModel.saveProfile(sampleProf, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertEquals("mi-perfil", fake.lastSavedProfile?.id)
        assertTrue(onSuccessCalled)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `deleteProfile delegates to client and invokes callback on success`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var onSuccessCalled = false
        viewModel.deleteProfile("mi-perfil", onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertEquals("mi-perfil", fake.lastDeletedProfileId)
        assertTrue(onSuccessCalled)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `loadProviderModels fetches models and updates state`() = runTest {
        fake.providerModelsToReturn = listOf(
            com.idupi.app.domain.model.ProviderModelItem("gpt-5.6-luna", "opencode-go/gpt-5.6-luna", "gpt-5.6-luna", "opencode-go")
        )
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.loadProviderModels("opencode-go")
        advanceUntilIdle()

        assertEquals("opencode-go", fake.lastRequestedProviderId)
        assertEquals(1, viewModel.providerModels.value["opencode-go"]?.size)
    }
}
