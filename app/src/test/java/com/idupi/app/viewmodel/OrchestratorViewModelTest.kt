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

        // Shared 4-tab set (PR3 — owner-confirmed vision: no per-engine tab swaps).
        assertEquals(OrchestratorTab.FASES, viewModel.activeTab.value)

        viewModel.selectTab(OrchestratorTab.MODELOS)
        assertEquals(OrchestratorTab.MODELOS, viewModel.activeTab.value)

        viewModel.selectTab(OrchestratorTab.HERRAMIENTAS)
        assertEquals(OrchestratorTab.HERRAMIENTAS, viewModel.activeTab.value)
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

    // ------------------------------------------------------------------
    // PR3 — Pi engine + gentle-ai detection (orchestrator-engine-generalize)
    // ------------------------------------------------------------------

    @Test
    fun `activeEngine defaults to opencode and selects pi or claude`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals("opencode", viewModel.activeEngine.value)

        viewModel.selectEngine("pi")
        assertEquals("pi", viewModel.activeEngine.value)

        viewModel.selectEngine("claude")
        assertEquals("claude", viewModel.activeEngine.value)

        // Unknown engines do NOT silently fall back to opencode — they keep the
        // last known good selection so the UI keeps rendering against valid data.
        viewModel.selectEngine("copilot")
        assertEquals("claude", viewModel.activeEngine.value)
    }

    @Test
    fun `init exposes piPhaseAssignments and gentleAiDetected from status envelope`() = runTest {
        fake.orchestratorStatusToReturn = com.idupi.app.domain.model.OrchestratorStatus(
            piPhaseAssignments = mapOf(
                "sdd-apply" to com.idupi.app.domain.model.PiPhaseConfig(
                    provider_id = "anthropic",
                    model_id = "claude-sonnet-4-5",
                    effort = "high"
                )
            ),
            gentleAiDetected = true
        )

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val st = viewModel.status.value
        assertEquals(1, st?.piPhaseAssignments?.size)
        assertEquals("claude-sonnet-4-5", st?.piPhaseAssignments?.get("sdd-apply")?.model_id)
        assertEquals("high", st?.piPhaseAssignments?.get("sdd-apply")?.effort)
        assertEquals(true, viewModel.gentleAiDetected.value)
    }

    @Test
    fun `updateModel round-trips engine pi through the client and refreshes status`() = runTest {
        fake.orchestratorStatusToReturn = com.idupi.app.domain.model.OrchestratorStatus(
            piPhaseAssignments = mapOf(
                "sdd-tasks" to com.idupi.app.domain.model.PiPhaseConfig(
                    provider_id = "openai",
                    model_id = "gpt-5-mini"
                )
            )
        )

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var onSuccessCalled = false
        viewModel.updateModel(
            engine = "pi",
            phase = "sdd-tasks",
            modelId = "gpt-5",
            providerId = "openai",
            effort = "medium",
            onSuccess = { onSuccessCalled = true }
        )
        advanceUntilIdle()

        assertEquals("pi", fake.lastUpdatedOrchestratorEngine)
        assertEquals("sdd-tasks", fake.lastUpdatedOrchestratorPhase)
        assertEquals("gpt-5", fake.lastUpdatedOrchestratorModelId)
        assertEquals("openai", fake.lastUpdatedOrchestratorProviderId)
        assertEquals("medium", fake.lastUpdatedOrchestratorEffort)
        assertEquals(true, onSuccessCalled)
        assertNull(viewModel.errorMessage.value)
        // Status refreshed with the Pi assignment now in place.
        assertEquals("gpt-5-mini", viewModel.status.value?.piPhaseAssignments?.get("sdd-tasks")?.model_id)
    }

    @Test
    fun `piAssignmentsFor exposes Pi assignments independent of active engine`() = runTest {
        fake.orchestratorStatusToReturn = com.idupi.app.domain.model.OrchestratorStatus(
            piPhaseAssignments = mapOf(
                "sdd-apply" to com.idupi.app.domain.model.PiPhaseConfig(
                    provider_id = "anthropic",
                    model_id = "claude-sonnet-4-5"
                )
            ),
            modelAssignments = mapOf(
                "sdd-apply" to com.idupi.app.domain.model.OpenCodeModelAssignment(
                    provider_id = "opencode-go",
                    model_id = "hy3"
                )
            ),
            claudePhaseAssignments = mapOf(
                "sdd-apply" to com.idupi.app.domain.model.ClaudePhaseConfig(model = "opus")
            )
        )

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        // piAssignmentsFor always returns the Pi map (the screen decides which
        // map to render based on activeEngine).
        val piFor = viewModel.piAssignmentsFor(viewModel.status.value)
        assertEquals("claude-sonnet-4-5", piFor["sdd-apply"]?.model_id)

        // OpenCode helper returns the OpenCode-shaped map.
        val openCodeFor = viewModel.openCodeAssignmentsFor(viewModel.status.value)
        assertEquals("hy3", openCodeFor["sdd-apply"]?.model_id)

        // Claude helper returns the Claude-shaped map.
        val claudeFor = viewModel.claudeAssignmentsFor(viewModel.status.value)
        assertEquals("opus", claudeFor["sdd-apply"]?.model)

        // Switch active engine — helpers keep returning the same maps.
        viewModel.selectEngine("pi")
        assertEquals("claude-sonnet-4-5", viewModel.piAssignmentsFor(viewModel.status.value)["sdd-apply"]?.model_id)
    }

    @Test
    fun `status parses older server payload missing piPhaseAssignments and gentleAiDetected`() = runTest {
        // Simulate a pre-PR2 server: the fake's default OrchestratorStatus has no
        // piPhaseAssignments / gentleAiDetected set. The ViewModel must surface
        // safe defaults rather than crash.
        fake.orchestratorStatusToReturn = com.idupi.app.domain.model.OrchestratorStatus(
            installedAgents = listOf("opencode")
        )

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val st = viewModel.status.value
        assertNotNull(st)
        assertTrue(st!!.piPhaseAssignments.isEmpty())
        assertEquals(false, viewModel.gentleAiDetected.value)
    }
}
