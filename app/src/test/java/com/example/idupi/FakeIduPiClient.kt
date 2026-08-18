package com.example.idupi

import com.example.idupi.domain.model.ChatEvent
import com.example.idupi.domain.model.FileNode
import com.example.idupi.domain.model.Project
import com.example.idupi.domain.model.QuickCommand
import com.example.idupi.domain.model.ServerStatus
import com.example.idupi.domain.model.SessionCounts
import com.example.idupi.domain.model.SessionCountsResponse
import com.example.idupi.domain.model.SessionsPage
import com.example.idupi.domain.model.SupervisorAlert
import com.example.idupi.domain.repository.AiModelItem
import com.example.idupi.domain.repository.IduPiClient
import com.example.idupi.domain.repository.IduPiClientSource
import com.example.idupi.domain.repository.SessionHistoryItem
import com.example.idupi.domain.repository.TerminalSessionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory [IduPiClient] test double. Every suspend method returns a sensible
 * empty/default value unless a `*ToReturn` field is set, and throws [failWith]
 * (when set) instead of returning normally — this is what lets tests exercise
 * ViewModel error-handling paths without a real server.
 */
class FakeIduPiClient : IduPiClient {

    /** When non-null, every suspend method throws this instead of succeeding. */
    var failWith: Exception? = null

    // Canned responses, overridable per test.
    var statusToReturn: ServerStatus = ServerStatus(
        connected = true,
        pcName = "fake-pc",
        project = "fake-project",
        agent = "fake-agent",
        busy = false,
        queueSize = 0
    )
    var projectsToReturn: List<Project> = emptyList()
    var addProjectResult: String? = null
    var sessionsToReturn: SessionsPage = SessionsPage(emptyList())
    var countsToReturn: SessionCountsResponse = SessionCountsResponse(SessionCounts())

    /** When non-empty, each [getSessionCounts] call consumes one handler in FIFO
     *  order instead of canned responses, letting tests control counts timing
     *  deterministically (used by stale-counts resurrection regression tests). */
    val countsHandlers: ArrayDeque<CountsHandler> = ArrayDeque()

    /** When non-empty, each [getSessions] call consumes one page in FIFO order. */
    val sessionsQueue: ArrayDeque<SessionsPage> = ArrayDeque()

    /** When non-empty, each [getSessions] call consumes one handler in FIFO order
     *  instead of canned responses, letting tests control response timing deterministically. */
    val sessionHandlers: ArrayDeque<SessionHandler> = ArrayDeque()

    /** When non-null, [getSessionCounts] throws this instead of succeeding. */
    var failCountsWith: Exception? = null

    /** Every [getSessions] call appends here so tests can assert call history. */
    val sessionRequestHistory = mutableListOf<SessionRequest>()
    var sessionHistoryToReturn: List<SessionHistoryItem> = emptyList()
    var resumeSessionResult: Boolean = true
    var availableModelsToReturn: List<AiModelItem> = emptyList()
    var switchModelResult: Boolean = true
    var selectEngineResult: Boolean = true
    var terminalsToReturn: List<TerminalSessionItem> = emptyList()
    var terminalLogsToReturn: List<String> = emptyList()
    var spawnTerminalResult: TerminalSessionItem? = null
    var sendTerminalCommandResult: String = ""
    var restartTerminalServerResult: String = ""
    var closeTerminalResult: Boolean = true
    var projectFilesToReturn: List<FileNode> = emptyList()
    var fileContentToReturn: String = ""
    var availableCommandsToReturn: List<QuickCommand> = emptyList()
    var alertsToReturn: List<SupervisorAlert> = emptyList()
    var browseDirectoryToReturn: com.example.idupi.domain.model.DirectoryBrowseResponse = com.example.idupi.domain.model.DirectoryBrowseResponse()
    var removeProjectsResult: Boolean = true
    var orchestratorStatusToReturn: com.example.idupi.domain.model.OrchestratorStatus = com.example.idupi.domain.model.OrchestratorStatus()
    var updateOrchestratorModelResult: Boolean = true
    var runOrchestratorActionResult: com.example.idupi.domain.model.OrchestratorActionResponse = com.example.idupi.domain.model.OrchestratorActionResponse()

    // Recording fields so tests can assert calls happened with the right arguments.
    var lastSelectedEngineId: String? = null
    var lastSelectedProjectId: String? = null
    var lastAddedProjectName: String? = null
    var lastBrowsedPath: String? = null
    var lastRemovedProjectIds: List<String>? = null
    var lastDeleteFilesFlag: Boolean? = null
    var lastUpdatedOrchestratorEngine: String? = null
    var lastUpdatedOrchestratorPhase: String? = null
    var lastUpdatedOrchestratorModelId: String? = null
    var lastRunOrchestratorAction: String? = null
    var lastMarkedAlertId: String? = null
    var lastApprovedPlanId: String? = null
    var lastRejectedPlanId: String? = null
    var lastSentMessage: String? = null
    var lastSwitchedModel: String? = null
    var lastRequestedSessionEngine: String? = null
    var lastRequestedSessionCursor: String? = null
    var lastRequestedSessionLimit: Int? = null
    var cancelCurrentTaskCalled: Boolean = false

    private val chatEvents = MutableSharedFlow<ChatEvent>(replay = 0, extraBufferCapacity = 16)

    /** Test hook to push a synthetic chat event through [connectChat]'s collectors. */
    suspend fun emitChatEvent(event: ChatEvent) {
        chatEvents.emit(event)
    }

    private fun maybeFail() {
        failWith?.let { throw it }
    }

    override suspend fun getStatus(): ServerStatus {
        maybeFail()
        return statusToReturn
    }

    override suspend fun getProjects(): List<Project> {
        maybeFail()
        return projectsToReturn
    }

    override suspend fun addProject(name: String, path: String): String? {
        maybeFail()
        lastAddedProjectName = name
        return addProjectResult
    }

    override suspend fun selectProject(projectId: String) {
        maybeFail()
        lastSelectedProjectId = projectId
    }

    override suspend fun getSessions(engine: String, cursor: String?, limit: Int): SessionsPage {
        maybeFail()
        lastRequestedSessionEngine = engine
        lastRequestedSessionCursor = cursor
        lastRequestedSessionLimit = limit
        sessionRequestHistory += SessionRequest(engine, cursor, limit)
        if (sessionHandlers.isNotEmpty()) {
            return sessionHandlers.removeFirst().invoke(engine, cursor, limit)
        }
        return if (sessionsQueue.isNotEmpty()) sessionsQueue.removeFirst() else sessionsToReturn
    }

    override suspend fun getSessionCounts(): SessionCountsResponse {
        if (countsHandlers.isNotEmpty()) {
            return countsHandlers.removeFirst().invoke()
        }
        failCountsWith?.let { throw it }
        maybeFail()
        return countsToReturn
    }

    override suspend fun getSessionHistory(sessionId: String): List<SessionHistoryItem> {
        maybeFail()
        return sessionHistoryToReturn
    }

    override suspend fun resumeSession(sessionId: String): Boolean {
        maybeFail()
        return resumeSessionResult
    }

    override suspend fun getAvailableModels(): List<AiModelItem> {
        maybeFail()
        return availableModelsToReturn
    }

    override suspend fun switchModel(modelName: String, provider: String?): Boolean {
        maybeFail()
        lastSwitchedModel = modelName
        return switchModelResult
    }

    override suspend fun selectEngine(engineId: String): Boolean {
        maybeFail()
        lastSelectedEngineId = engineId
        return selectEngineResult
    }

    override suspend fun getTerminals(): List<TerminalSessionItem> {
        maybeFail()
        return terminalsToReturn
    }

    override suspend fun getTerminalLogs(terminalId: String): List<String> {
        maybeFail()
        return terminalLogsToReturn
    }

    override suspend fun spawnTerminal(name: String?): TerminalSessionItem? {
        maybeFail()
        return spawnTerminalResult
    }

    override suspend fun sendTerminalCommand(terminalId: String, command: String): String {
        maybeFail()
        return sendTerminalCommandResult
    }

    override suspend fun restartTerminalServer(terminalId: String): String {
        maybeFail()
        return restartTerminalServerResult
    }

    override suspend fun closeTerminal(terminalId: String): Boolean {
        maybeFail()
        return closeTerminalResult
    }

    override fun connectChat(): Flow<ChatEvent> = chatEvents.asSharedFlow()

    override suspend fun sendMessage(message: String) {
        maybeFail()
        lastSentMessage = message
    }

    override suspend fun sendUiResponse(requestId: String, value: Any) {
        maybeFail()
    }

    override suspend fun cancelCurrentTask() {
        maybeFail()
        cancelCurrentTaskCalled = true
    }

    override suspend fun getProjectFiles(projectId: String): List<FileNode> {
        maybeFail()
        return projectFilesToReturn
    }

    override suspend fun getFileContent(projectId: String, path: String): String {
        maybeFail()
        return fileContentToReturn
    }

    override suspend fun resetTerminal() {
        maybeFail()
    }

    override suspend fun getAvailableCommands(): List<QuickCommand> {
        maybeFail()
        return availableCommandsToReturn
    }

    override suspend fun getAlerts(): List<SupervisorAlert> {
        maybeFail()
        return alertsToReturn
    }

    override suspend fun markAlertAsRead(alertId: String) {
        maybeFail()
        lastMarkedAlertId = alertId
    }

    override suspend fun approveMasterPlan(planId: String) {
        maybeFail()
        lastApprovedPlanId = planId
    }

    override suspend fun rejectMasterPlan(planId: String, reason: String) {
        maybeFail()
        lastRejectedPlanId = planId
    }

    override suspend fun toggleGuardrails(enable: Boolean) {
        maybeFail()
    }

    override suspend fun browseDirectory(path: String?): com.example.idupi.domain.model.DirectoryBrowseResponse {
        maybeFail()
        lastBrowsedPath = path
        return browseDirectoryToReturn
    }

    override suspend fun removeProjects(projectIds: List<String>, deleteFiles: Boolean): Boolean {
        maybeFail()
        lastRemovedProjectIds = projectIds
        lastDeleteFilesFlag = deleteFiles
        return removeProjectsResult
    }

    override suspend fun getOrchestratorStatus(): com.example.idupi.domain.model.OrchestratorStatus {
        maybeFail()
        return orchestratorStatusToReturn
    }

    override suspend fun updateOrchestratorModel(
        engine: String,
        phase: String,
        modelId: String,
        providerId: String?,
        effort: String?
    ): Boolean {
        maybeFail()
        lastUpdatedOrchestratorEngine = engine
        lastUpdatedOrchestratorPhase = phase
        lastUpdatedOrchestratorModelId = modelId
        return updateOrchestratorModelResult
    }

    override suspend fun runOrchestratorAction(action: String): com.example.idupi.domain.model.OrchestratorActionResponse {
        maybeFail()
        lastRunOrchestratorAction = action
        return runOrchestratorActionResult
    }

    var providerModelsToReturn: List<com.example.idupi.domain.model.ProviderModelItem> = emptyList()
    var lastRequestedProviderId: String? = null

    override suspend fun getProviderModels(providerId: String): List<com.example.idupi.domain.model.ProviderModelItem> {
        maybeFail()
        lastRequestedProviderId = providerId
        return providerModelsToReturn
    }

    var lastAppliedProfileId: String? = null
    var applySddProfileResult: Boolean = true
    var lastSavedProfile: com.example.idupi.domain.model.SddProfileItem? = null
    var saveSddProfileResult: Boolean = true
    var lastDeletedProfileId: String? = null
    var deleteSddProfileResult: Boolean = true

    override suspend fun applySddProfile(profileId: String): Boolean {
        maybeFail()
        lastAppliedProfileId = profileId
        return applySddProfileResult
    }

    override suspend fun saveSddProfile(profile: com.example.idupi.domain.model.SddProfileItem): Boolean {
        maybeFail()
        lastSavedProfile = profile
        return saveSddProfileResult
    }

    override suspend fun deleteSddProfile(profileId: String): Boolean {
        maybeFail()
        lastDeletedProfileId = profileId
        return deleteSddProfileResult
    }
}

/** A single recorded [getSessions] request, for asserting engine/cursor/limit history. */
data class SessionRequest(val engine: String, val cursor: String?, val limit: Int)

/** A queued [getSessions] responder, for controlling response timing deterministically. */
typealias SessionHandler = suspend (engine: String, cursor: String?, limit: Int) -> SessionsPage

/** A queued [getSessionCounts] responder, for controlling counts timing deterministically. */
typealias CountsHandler = suspend () -> SessionCountsResponse

/** Test-only [IduPiClientSource] that always exposes the given [fake]. */
class FakeClientSource(val fake: FakeIduPiClient) : IduPiClientSource {
    override val client: IduPiClient get() = fake
}
