package com.idupi.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the wire shape of PiPhaseConfig and the new envelope fields on
 * OrchestratorStatus / SddProfileItem added in PR3. The orchestrator-side
 * envelope already emits `piPhaseAssignments` + `gentleAiDetected`
 * (PR2 committed `cc0052d`); this test guards that the Kotlin deserializer
 * matches the JSON the server actually sends AND that an OLDER server payload
 * (pre-PR2, missing every new field) still parses without throwing — the
 * default values are the contract surface that keeps older clients alive.
 */
class PiPhaseConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `PiPhaseConfig round-trips with provider_id, model_id and effort`() {
        val original = PiPhaseConfig(
            provider_id = "anthropic",
            model_id = "claude-sonnet-4-5",
            effort = "high"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PiPhaseConfig>(encoded)

        assertEquals("anthropic", decoded.provider_id)
        assertEquals("claude-sonnet-4-5", decoded.model_id)
        assertEquals("high", decoded.effort)
    }

    @Test
    fun `PiPhaseConfig effort is optional and defaults to null`() {
        val original = PiPhaseConfig(
            provider_id = "openai",
            model_id = "gpt-5"
        )
        assertEquals(null, original.effort)

        val encoded = json.encodeToString(original)
        // kotlinx.serialization omits null-valued primitives, so effort is dropped from JSON.
        assertFalse("effort should be omitted from JSON when null", encoded.contains("\"effort\""))

        val decoded = json.decodeFromString<PiPhaseConfig>(encoded)
        assertEquals(null, decoded.effort)
    }

    @Test
    fun `PiPhaseConfig tolerates absent provider_id and model_id with sane fallbacks`() {
        // Older Pi clients may emit just a model string; the orchestrator must still
        // accept the payload without crashing and surface the model_id.
        val raw = """{"model_id":"claude-haiku"}"""
        val decoded = json.decodeFromString<PiPhaseConfig>(raw)

        assertEquals("claude-haiku", decoded.model_id)
        // provider_id defaults to empty so the UI can render "Sin asignar" instead of crashing.
        assertEquals("", decoded.provider_id)
    }

    @Test
    fun `OrchestratorStatus defaults piPhaseAssignments to empty and gentleAiDetected to false`() {
        val status = OrchestratorStatus()

        assertTrue("piPhaseAssignments must default to empty map", status.piPhaseAssignments.isEmpty())
        assertFalse("gentleAiDetected must default to false (base mode)", status.gentleAiDetected)
    }

    @Test
    fun `OrchestratorStatus parses newer envelope with piPhaseAssignments and gentleAiDetected`() {
        val raw = """
            {
              "persona": "gentleman",
              "preset": "full-gentleman",
              "piPhaseAssignments": {
                "sdd-apply": { "provider_id": "anthropic", "model_id": "claude-sonnet-4-5", "effort": "high" },
                "sdd-explore": { "provider_id": "anthropic", "model_id": "claude-haiku" }
              },
              "gentleAiDetected": true
            }
        """.trimIndent()

        val status = json.decodeFromString<OrchestratorStatus>(raw)

        assertTrue(status.gentleAiDetected)
        assertEquals(2, status.piPhaseAssignments.size)
        val apply = status.piPhaseAssignments["sdd-apply"]
        assertEquals("anthropic", apply?.provider_id)
        assertEquals("claude-sonnet-4-5", apply?.model_id)
        assertEquals("high", apply?.effort)
        val explore = status.piPhaseAssignments["sdd-explore"]
        assertEquals("claude-haiku", explore?.model_id)
        assertEquals(null, explore?.effort)
    }

    @Test
    fun `OrchestratorStatus tolerates older server payload missing piPhaseAssignments and gentleAiDetected`() {
        // Pre-PR2 servers send neither field. ignoreUnknownKeys + defaults keep us alive.
        val raw = """
            {
              "persona": "gentleman",
              "preset": "full-gentleman",
              "installedAgents": ["opencode", "claude-code"]
            }
        """.trimIndent()

        val status = json.decodeFromString<OrchestratorStatus>(raw)

        assertTrue(status.piPhaseAssignments.isEmpty())
        assertFalse(status.gentleAiDetected)
        // Existing envelope must still parse cleanly.
        assertEquals(listOf("opencode", "claude-code"), status.installedAgents)
    }

    @Test
    fun `SddProfileItem defaults piAssignments to empty map`() {
        val profile = SddProfileItem(id = "strong", name = "Strong")

        assertTrue("piAssignments must default to empty map for pre-PR2 server payloads",
            profile.piAssignments.isEmpty())
    }

    @Test
    fun `SddProfileItem round-trips piAssignments alongside modelAssignments and claudeAssignments`() {
        val original = SddProfileItem(
            id = "strong",
            name = "Strong (transversal)",
            description = "top-tier models for pi, opencode, claude",
            isCustom = false,
            modelAssignments = mapOf(
                "sdd-apply" to OpenCodeModelAssignment(provider_id = "opencode-go", model_id = "hy3", effort = "high")
            ),
            claudeAssignments = mapOf(
                "sdd-apply" to ClaudePhaseConfig(model = "opus")
            ),
            piAssignments = mapOf(
                "sdd-apply" to PiPhaseConfig(provider_id = "anthropic", model_id = "claude-sonnet-4-5", effort = "high"),
                "sdd-spec" to PiPhaseConfig(provider_id = "openai", model_id = "gpt-5")
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SddProfileItem>(encoded)

        assertEquals(2, decoded.piAssignments.size)
        assertEquals("claude-sonnet-4-5", decoded.piAssignments["sdd-apply"]?.model_id)
        assertEquals("high", decoded.piAssignments["sdd-apply"]?.effort)
        assertEquals("gpt-5", decoded.piAssignments["sdd-spec"]?.model_id)
        // Transversal guarantees every map is preserved independently.
        assertEquals("hy3", decoded.modelAssignments["sdd-apply"]?.model_id)
        assertEquals("opus", decoded.claudeAssignments["sdd-apply"]?.model)
    }
}