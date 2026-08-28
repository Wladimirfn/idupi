package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SddTaskProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val pending: Int = 0,
    val allComplete: Boolean = false
)

@Serializable
data class SddStatusInfo(
    val changeName: String? = null,
    val applyState: String = "idle",
    val nextRecommended: String = "sdd-new",
    val taskProgress: SddTaskProgress = SddTaskProgress(),
    val blockedReasons: List<String> = emptyList()
)

@Serializable
data class ClaudePhaseConfig(
    val model: String = "sonnet"
)

@Serializable
data class OpenCodeModelAssignment(
    val provider_id: String = "opencode-go",
    val model_id: String = "",
    val effort: String? = null
)

@Serializable
data class ProviderModelItem(
    val id: String,
    val fullId: String = "",
    val name: String = "",
    val provider: String = ""
)

@Serializable
data class ProviderModelsResponse(
    val provider: String,
    val models: List<ProviderModelItem> = emptyList()
)

@Serializable
data class SddProfileItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val isCustom: Boolean = true,
    val modelAssignments: Map<String, OpenCodeModelAssignment> = emptyMap(),
    val claudeAssignments: Map<String, ClaudePhaseConfig> = emptyMap()
)

@Serializable
data class OrchestratorStatus(
    val persona: String = "gentleman",
    val preset: String = "full-gentleman",
    val installedAgents: List<String> = emptyList(),
    val components: List<String> = emptyList(),
    val rddMode: String = "on",
    val sddStatus: SddStatusInfo = SddStatusInfo(),
    val claudePhaseAssignments: Map<String, ClaudePhaseConfig> = emptyMap(),
    val modelAssignments: Map<String, OpenCodeModelAssignment> = emptyMap(),
    val providers: List<String> = emptyList(),
    val sddProfiles: List<SddProfileItem> = emptyList(),
    val activeProfile: String? = null
)

@Serializable
data class OrchestratorActionResponse(
    val status: String = "ok",
    val action: String = "",
    val output: String = "",
    val error: String? = null
)
