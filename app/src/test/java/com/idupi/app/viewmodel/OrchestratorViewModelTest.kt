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
    fun `activeEngine hydrates from server status and selects pi or claude`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        // The OPENCODE seed is a pre-hydration placeholder: init refreshes
        // from status, and the fake (like the server) reports "pi-cli".
        assertEquals("pi", viewModel.activeEngine.value)

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

    // -- syncEngine: local-only engine sync from the resumed session -----------
    //
    // When the user taps Reanudar on a session, navigation threads the
    // session's engine (pre-test audit: it was discarded, so the selector
    // stayed stale). syncEngine flips the local engine immediately with NO
    // server write; the post-resume refreshStatus() confirms server truth.

    @Test
    fun `syncEngine flips the local engine without any server write`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        // The fake's default OrchestratorStatus.activeEngine is "pi-cli", so
        // init's refreshStatus already synced the engine to PI.
        assertEquals(OrchestratorEngine.PI, viewModel.activeEngine.value)

        viewModel.syncEngine("claude")
        assertEquals(OrchestratorEngine.CLAUDE, viewModel.activeEngine.value)
        assertNull(
            "syncEngine is local-only -- it must not write to the server",
            fake.lastSelectedEngineId,
        )
    }

    @Test
    fun `syncEngine maps pi-cli to PI and keeps canonical engine ids`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.syncEngine("pi-cli")
        assertEquals(OrchestratorEngine.PI, viewModel.activeEngine.value)

        viewModel.syncEngine("opencode")
        assertEquals(OrchestratorEngine.OPENCODE, viewModel.activeEngine.value)
    }

    @Test
    fun `syncEngine ignores unknown and null engines`() = runTest {
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        val before = viewModel.activeEngine.value

        viewModel.syncEngine("weird-engine")
        assertEquals("an unknown engine id must be a no-op", before, viewModel.activeEngine.value)

        viewModel.syncEngine(null)
        assertEquals("a null engine must be a no-op", before, viewModel.activeEngine.value)
    }

    // ------------------------------------------------------------------
    // fix/orchestrator-engine-tabs — regression: tapping OpenCode/Claude
    // in the motor selector must NOT snap the active engine back to Pi.
    // Root cause: selectEngine() flipped _activeEngine optimistically and
    // then called refreshStatus(), whose syncEngineFromStatus(st) would
    // overwrite _activeEngine with the server's still-stale activeEngine
    // (default "pi-cli" on the real server between round-trips, and on
    // any test double that doesn't echo the selectEngine write). The
    // selector visually jumped back to Pi and the per-engine tabs became
    // unreachable. Pin the contract: once the user has chosen an engine
    // in this ViewModel, server snapshots are ignored until recreation.
    // ------------------------------------------------------------------

    @Test
    fun `selectEngine survives refreshStatus that still reports the old engine`() = runTest {
        // The fake's default orchestratorStatusToReturn keeps activeEngine
        // pinned at "pi-cli" — i.e. it does NOT echo back selectEngine calls.
        // That matches the real server's brief window between POST /engine
        // and the next GET /status. With the bug present, this scenario
        // snaps the selector back to Pi on every tap (regression).
        assertEquals("pi-cli", fake.orchestratorStatusToReturn.activeEngine)

        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        // init re-hydrated from the server's default → engine is Pi.
        assertEquals(OrchestratorEngine.PI, viewModel.activeEngine.value)

        viewModel.selectEngine(OrchestratorEngine.OPENCODE)
        advanceUntilIdle()
        assertEquals(
            "user-selected engine must NOT be clobbered by a stale server status",
            OrchestratorEngine.OPENCODE,
            viewModel.activeEngine.value,
        )

        viewModel.selectEngine(OrchestratorEngine.CLAUDE)
        advanceUntilIdle()
        assertEquals(
            "switching engines must also stick when the server hasn't confirmed yet",
            OrchestratorEngine.CLAUDE,
            viewModel.activeEngine.value,
        )

        // A subsequent manual refreshStatus must still not clobber the user choice.
        viewModel.refreshStatus()
        advanceUntilIdle()
        assertEquals(
            "manual refresh must respect an explicit engine selection",
            OrchestratorEngine.CLAUDE,
            viewModel.activeEngine.value,
        )
    }

    @Test
    fun `syncEngine from resumed session survives refreshStatus`() = runTest {
        // Same regression class, navigation entry point: AppNavigation calls
        // syncEngine(engine) on session resume, then refreshStatus() runs
        // from the post-resume hook. Without the latch, the stale
        // activeEngine="pi-cli" status overwrites the resumed engine.
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(OrchestratorEngine.PI, viewModel.activeEngine.value)

        viewModel.syncEngine(OrchestratorEngine.CLAUDE)
        viewModel.refreshStatus()
        advanceUntilIdle()
        assertEquals(
            "resumed-session engine must NOT be clobbered by a stale server status",
            OrchestratorEngine.CLAUDE,
            viewModel.activeEngine.value,
        )
    }

    @Test
    fun `selectEngine with unknown id keeps the previous engine and leaves the latch armed`() = runTest {
        // Unknown ids are a no-op, but the latch state must remain consistent:
        // a prior explicit choice is still respected, AND the latch stays
        // armed so a stale server status does not undo it.
        fake.orchestratorStatusToReturn = OrchestratorStatus(activeEngine = "pi-cli")
        val viewModel = OrchestratorViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.selectEngine(OrchestratorEngine.OPENCODE)
        advanceUntilIdle()
        assertEquals(OrchestratorEngine.OPENCODE, viewModel.activeEngine.value)

        viewModel.selectEngine("copilot") // unknown
        advanceUntilIdle()
        assertEquals(
            "unknown id must NOT clobber the prior explicit engine",
            OrchestratorEngine.OPENCODE,
            viewModel.activeEngine.value,
        )
    }
}
