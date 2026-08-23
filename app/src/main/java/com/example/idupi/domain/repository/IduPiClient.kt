package com.example.idupi.domain.repository

import com.example.idupi.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class SessionHistoryItem(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AiModelItem(
    val id: String,
    val name: String,
    val provider: String
)

@Serializable
data class TerminalSessionItem(
    val id: String,
    val name: String,
    val type: String = "shell", // "agent", "server", "shell"
    val status: String = "running",
    val cwd: String? = null,
    val pid: Int = 0,
    val logCount: Int = 0
)

interface IduPiClient {
    suspend fun getStatus(): ServerStatus
    suspend fun getProjects(): List<Project>
    suspend fun addProject(name: String, path: String): String?
    suspend fun selectProject(projectId: String)
    suspend fun getSessions(engine: String = "all", cursor: String? = null, limit: Int = 30, includeAll: Boolean = false): SessionsPage
    suspend fun getSessionCounts(includeAll: Boolean = false): SessionCountsResponse
    /** Starts a fresh CLI session on the active engine, keeping the chosen model. */
    suspend fun startNewSession(): Boolean
    suspend fun getSessionHistory(sessionId: String): List<SessionHistoryItem>
    suspend fun resumeSession(sessionId: String): Boolean
    suspend fun getAvailableModels(): List<AiModelItem>
    suspend fun switchModel(modelName: String, provider: String? = null): Boolean
    suspend fun selectEngine(engineId: String): Boolean
    
    // Multiterminal PC Manager
    suspend fun getTerminals(): List<TerminalSessionItem>
    suspend fun getTerminalLogs(terminalId: String): List<String>
    suspend fun spawnTerminal(name: String? = null): TerminalSessionItem?
    suspend fun sendTerminalCommand(terminalId: String, command: String): String
    suspend fun restartTerminalServer(terminalId: String): String
    suspend fun closeTerminal(terminalId: String): Boolean

    fun connectChat(): Flow<ChatEvent>
    suspend fun sendMessage(message: String)
    suspend fun sendUiResponse(requestId: String, value: Any)
    suspend fun cancelCurrentTask()
    suspend fun getProjectFiles(projectId: String): List<FileNode>
    suspend fun getFileContent(projectId: String, path: String): String
    suspend fun resetTerminal()
    suspend fun getAvailableCommands(): List<QuickCommand>
    suspend fun browseDirectory(path: String? = null): DirectoryBrowseResponse
    suspend fun removeProjects(projectIds: List<String>, deleteFiles: Boolean = false): Boolean
    suspend fun getAlerts(): List<SupervisorAlert>
    suspend fun markAlertAsRead(alertId: String)
    suspend fun approveMasterPlan(planId: String)
    suspend fun rejectMasterPlan(planId: String, reason: String)
    suspend fun toggleGuardrails(enable: Boolean)
    suspend fun getOrchestratorStatus(): OrchestratorStatus
    suspend fun updateOrchestratorModel(engine: String, phase: String, modelId: String, providerId: String? = null, effort: String? = null): Boolean
    suspend fun runOrchestratorAction(action: String): OrchestratorActionResponse
    suspend fun getProviderModels(providerId: String): List<ProviderModelItem>
    suspend fun applySddProfile(profileId: String): Boolean
    suspend fun saveSddProfile(profile: SddProfileItem): Boolean
    suspend fun deleteSddProfile(profileId: String): Boolean

    // Remote screen module (docs/remote-screen-module.md)
    suspend fun getScreenMonitors(): List<ScreenMonitor>

    /**
     * Opens the binary chunked frame stream for one monitor. Frames are raw
     * JPEG with wire framing -- NOT SSE, NOT base64.
     */
    fun screenFrames(request: ScreenStreamRequest): Flow<ScreenWireMessage.Frame>

    /** Must be called AFTER a frame is rendered; the server paces on it. */
    suspend fun acknowledgeScreenFrame(sid: String, frameId: Int, bytes: Int, renderMs: Long)
}
