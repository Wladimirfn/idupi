package com.idupi.app

import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.model.FileNode
import com.idupi.app.domain.model.InboxConfig
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxMessageRequest
import com.idupi.app.domain.model.KillProcessOutcome
import com.idupi.app.domain.model.OsProcessSample
import com.idupi.app.domain.model.Project
import com.idupi.app.domain.model.PtyInfo
import com.idupi.app.domain.model.QuickCommand
import com.idupi.app.domain.model.ServerStatus
import com.idupi.app.domain.model.SessionCounts
import com.idupi.app.domain.model.SessionCountsResponse
import com.idupi.app.domain.model.SessionsPage
import com.idupi.app.domain.model.SupervisorAlert
import com.idupi.app.domain.repository.AiModelItem
import com.idupi.app.domain.repository.IduPiClient
import com.idupi.app.domain.repository.IduPiClientSource
import com.idupi.app.domain.repository.SessionHistoryItem
import com.idupi.app.data.phone.PhoneStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.idupi.app.domain.model.ScreenMonitor
import com.idupi.app.domain.model.ScreenFrameMeta
import com.idupi.app.domain.model.ScreenRemoteConfig
import com.idupi.app.domain.model.ScreenInputEvent
import com.idupi.app.domain.model.ScreenStreamRequest
import com.idupi.app.domain.model.ScreenWireMessage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    var projectFilesToReturn: List<FileNode> = emptyList()
    var fileContentToReturn: String = ""
    var availableCommandsToReturn: List<QuickCommand> = emptyList()
    var alertsToReturn: List<SupervisorAlert> = emptyList()
    var browseDirectoryToReturn: com.idupi.app.domain.model.DirectoryBrowseResponse = com.idupi.app.domain.model.DirectoryBrowseResponse()
    var removeProjectsResult: Boolean = true
    var orchestratorStatusToReturn: com.idupi.app.domain.model.OrchestratorStatus = com.idupi.app.domain.model.OrchestratorStatus()
    var updateOrchestratorModelResult: Boolean = true
    var runOrchestratorActionResult: com.idupi.app.domain.model.OrchestratorActionResponse = com.idupi.app.domain.model.OrchestratorActionResponse()

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
    var lastUpdatedOrchestratorProviderId: String? = null
    var lastUpdatedOrchestratorEffort: String? = null
    var lastRunOrchestratorAction: String? = null
    var lastMarkedAlertId: String? = null
    var lastApprovedPlanId: String? = null
    var lastRejectedPlanId: String? = null
    var lastSentMessage: String? = null
    var lastSwitchedModel: String? = null

    // Last sendUiResponse call -- the 409-retry tests (Phase 6) will assert
    // these were forwarded with the right token/sessionId, that the ViewModel
    // re-prompted on 409, and that the active request was cleared on 200.
    var lastSentUiRequestId: String? = null
    var lastSentUiValue: Any? = null
    var lastSentUiToken: String? = null
    var lastSentUiSessionId: String? = null

    /**
     * When set, [sendUiResponse] throws this exception instead of recording
     * the call. Used by the Phase 6.4 tests to drive ChatViewModel through
     * the 409-hold and other-error branches without a real server. Recording
     * fields stay `null` on a throw -- the ViewModel must not clear
     * `_activeUiRequest` before the POST, so we can also assert "no body was
     * accepted" by reading the recording fields.
     *
     * Setting `failWith` overrides this for `sendUiResponse` too; setting
     * `sendUiResponseFailure` overrides `failWith`. The two together let a
     * test exercise "200 then 409" without touching the global `failWith`.
     */
    var sendUiResponseFailure: Exception? = null

    /** When > 0, that many next [sendUiResponse] calls throw instead of
     *  recording, then the field decrements. Lets a single test drive
     *  "200 then 409 then 200" without three separate fake instances. */
    var sendUiResponseFailuresRemaining: Int = 0

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

    override suspend fun getSessions(engine: String, cursor: String?, limit: Int, includeAll: Boolean): SessionsPage {
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

    /** Every [startNewSession] call increments this, so tests can assert it was asked for. */
    var newSessionCalls: Int = 0

    override suspend fun startNewSession(): Boolean {
        newSessionCalls++
        return true
    }

    override suspend fun getSessionCounts(includeAll: Boolean): SessionCountsResponse {
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

    // --- Interactive PTY terminals ---

    var ptyCreateResult: PtyInfo = PtyInfo(id = "pty-1", pid = 1001, cols = 80, rows = 24, alive = true)
    var ptyCreateDelayMs: Long = 0
    var ptyListToReturn: List<PtyInfo> = emptyList()
    var ptyStreamChunks: List<ByteArray> = emptyList()
    var ptyStreamHang: Boolean = false

    /** Per-call stream script: each ptyStream() consumes one entry in FIFO order. */
    val ptyStreamScript: ArrayDeque<PtyStreamCall> = ArrayDeque()
    var ptyStreamCalls: Int = 0
    var failPtyStreamWith: Exception? = null
    var lastCreatedPtyDims: Pair<Int, Int>? = null
    var lastCreatedPtyCwd: String? = null
    val ptyInputs = mutableListOf<Pair<String, String>>()
    val ptyResizes = mutableListOf<Triple<String, Int, Int>>()
    var killPtyResult: Boolean = true
    var lastKilledPtyId: String? = null
    var listPtysCalls: Int = 0

    override suspend fun createPty(cols: Int, rows: Int, cwd: String?): PtyInfo {
        maybeFail()
        lastCreatedPtyDims = cols to rows
        lastCreatedPtyCwd = cwd
        if (ptyCreateDelayMs > 0) delay(ptyCreateDelayMs)
        return ptyCreateResult
    }

    override suspend fun listPtys(): List<PtyInfo> {
        maybeFail()
        listPtysCalls++
        return ptyListToReturn
    }

    override fun ptyStream(id: String): Flow<ByteArray> {
        ptyStreamCalls++
        val call = if (ptyStreamScript.isNotEmpty()) {
            ptyStreamScript.removeFirst()
        } else {
            PtyStreamCall(ptyStreamChunks, ptyStreamHang)
        }
        val failure = failPtyStreamWith
        return flow {
            failure?.let { throw it }
            call.chunks.forEach { emit(it) }
            if (call.hang) awaitCancellation()
        }
    }

    override suspend fun ptyInput(id: String, data: String) {
        maybeFail()
        ptyInputs.add(id to data)
    }

    override suspend fun ptyResize(id: String, cols: Int, rows: Int) {
        maybeFail()
        ptyResizes.add(Triple(id, cols, rows))
    }

    override suspend fun killPty(id: String): Boolean {
        maybeFail()
        lastKilledPtyId = id
        return killPtyResult
    }

    // --- APK deliveries ---

    var deliveriesToReturn: List<com.idupi.app.domain.model.DeliveryInfo> = emptyList()
    var deliveryDownloadChunks: Map<String, List<ByteArray>> = emptyMap()
    var failDeliveryDownloadWith: Exception? = null
    var deleteDeliveryResult: Boolean = true
    var listDeliveriesCalls: Int = 0
    val deletedDeliveryIds = mutableListOf<String>()

    override suspend fun listDeliveries(): List<com.idupi.app.domain.model.DeliveryInfo> {
        maybeFail()
        listDeliveriesCalls++
        return deliveriesToReturn
    }

    override fun downloadDelivery(id: String): Flow<ByteArray> {
        val chunks = deliveryDownloadChunks[id] ?: emptyList()
        val failure = failDeliveryDownloadWith ?: failWith
        return flow {
            failure?.let { throw it }
            chunks.forEach { emit(it) }
        }
    }

    override suspend fun deleteDelivery(id: String): Boolean {
        maybeFail()
        deletedDeliveryIds.add(id)
        return deleteDeliveryResult
    }

    // --- Dashboard metrics ---

    var metricsToReturn: com.idupi.app.domain.model.SystemMetrics =
        com.idupi.app.domain.model.SystemMetrics()

    /** When non-null, [getSystemMetrics] throws this while still counting the call. */
    var failMetricsWith: Exception? = null

    /** Counts attempts, including failed ones, so polling tests can assert cadence. */
    var getSystemMetricsCalls: Int = 0

    override suspend fun getSystemMetrics(): com.idupi.app.domain.model.SystemMetrics {
        maybeFail()
        getSystemMetricsCalls++
        failMetricsWith?.let { throw it }
        return metricsToReturn
    }

    // --- OS processes (Procesos screen, T10) ---

    var osProcessesToReturn: OsProcessSample = OsProcessSample()

    /** When non-null, [getOsProcesses] throws this while still counting the call. */
    var failOsProcessesWith: Exception? = null

    /** Counts attempts, including failed ones, so polling tests can assert cadence. */
    var getOsProcessesCalls: Int = 0

    var killOsProcessDefault: KillProcessOutcome = KillProcessOutcome.KILLED
    val killOutcomeByPid = mutableMapOf<Int, KillProcessOutcome>()
    val killedOsPids = mutableListOf<Int>()

    override suspend fun getOsProcesses(): OsProcessSample {
        maybeFail()
        getOsProcessesCalls++
        failOsProcessesWith?.let { throw it }
        return osProcessesToReturn
    }

    override suspend fun killOsProcess(pid: Int): KillProcessOutcome {
        maybeFail()
        killedOsPids += pid
        return killOutcomeByPid[pid] ?: killOsProcessDefault
    }

    override fun connectChat(): Flow<ChatEvent> = chatEvents.asSharedFlow()

    override suspend fun sendMessage(message: String) {
        maybeFail()
        lastSentMessage = message
    }

    override suspend fun sendUiResponse(requestId: String, value: Any, token: String, sessionId: String) {
        sendUiResponseFailure?.let { throw it }
        if (sendUiResponseFailuresRemaining > 0) {
            sendUiResponseFailuresRemaining--
            throw com.idupi.app.data.remote.IduPiHttpException(409, "fake://test", "fake 409")
        }
        maybeFail()
        lastSentUiRequestId = requestId
        lastSentUiValue = value
        lastSentUiToken = token
        lastSentUiSessionId = sessionId
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

    override suspend fun browseDirectory(path: String?): com.idupi.app.domain.model.DirectoryBrowseResponse {
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

    override suspend fun getOrchestratorStatus(): com.idupi.app.domain.model.OrchestratorStatus {
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
        lastUpdatedOrchestratorProviderId = providerId
        lastUpdatedOrchestratorEffort = effort
        return updateOrchestratorModelResult
    }

    override suspend fun runOrchestratorAction(action: String): com.idupi.app.domain.model.OrchestratorActionResponse {
        maybeFail()
        lastRunOrchestratorAction = action
        return runOrchestratorActionResult
    }

    var providerModelsToReturn: List<com.idupi.app.domain.model.ProviderModelItem> = emptyList()
    var lastRequestedProviderId: String? = null

    override suspend fun getProviderModels(providerId: String): List<com.idupi.app.domain.model.ProviderModelItem> {
        maybeFail()
        lastRequestedProviderId = providerId
        return providerModelsToReturn
    }

    var lastAppliedProfileId: String? = null
    var applySddProfileResult: Boolean = true
    var lastSavedProfile: com.idupi.app.domain.model.SddProfileItem? = null
    var saveSddProfileResult: Boolean = true
    var lastDeletedProfileId: String? = null
    var deleteSddProfileResult: Boolean = true

    override suspend fun applySddProfile(profileId: String): Boolean {
        maybeFail()
        lastAppliedProfileId = profileId
        return applySddProfileResult
    }

    override suspend fun saveSddProfile(profile: com.idupi.app.domain.model.SddProfileItem): Boolean {
        maybeFail()
        lastSavedProfile = profile
        return saveSddProfileResult
    }

    override suspend fun deleteSddProfile(profileId: String): Boolean {
        maybeFail()
        lastDeletedProfileId = profileId
        return deleteSddProfileResult
    }

    // --- Phone inbox (phone-inbox T2) ---

    var inboxItemsToReturn: List<InboxItem> = emptyList()
    var inboxConfigToReturn: InboxConfig = InboxConfig(
        agent = "opencode",
        model = "opencode-go/deepseek-v4.1-flash",
        effort = "max",
        agents = listOf("opencode", "claude", "codex", "pi"),
        efforts = listOf("low", "medium", "high", "max"),
    )
    var inboxModelsToReturn: List<String> = emptyList()

    /** When set, [postInboxMessage] returns it; otherwise it derives one. */
    var inboxPostResult: InboxItem? = null
    var inboxStateUpdateResult: InboxItem? = null
    var inboxRetryResult: InboxItem? = null

    /** Every posted message, in order. */
    val postedInboxMessages = mutableListOf<InboxMessageRequest>()

    /** (id, state) pairs sent through [setInboxItemState]. */
    val inboxStateChanges = mutableListOf<Pair<String, String>>()
    val retriedInboxIds = mutableListOf<String>()
    var lastRequestedInboxLimit: Int? = null
    var lastInboxConfigUpdate: Triple<String, String, String>? = null
    var lastRequestedInboxModelsAgent: String? = null

    override suspend fun postInboxMessage(message: InboxMessageRequest): InboxItem {
        maybeFail()
        postedInboxMessages += message
        return inboxPostResult ?: InboxItem(
            id = "itm-fake-${postedInboxMessages.size}",
            app = message.app,
            packageName = message.packageName,
            sender = message.sender,
            conversation = message.conversation,
            text = message.text,
            postedAt = message.postedAt,
            receivedAt = message.postedAt,
            notificationKey = message.notificationKey,
            state = InboxItemState.OPEN,
        )
    }

    override suspend fun getInboxItems(limit: Int): List<InboxItem> {
        maybeFail()
        lastRequestedInboxLimit = limit
        return inboxItemsToReturn
    }

    override suspend fun setInboxItemState(id: String, state: String): InboxItem {
        maybeFail()
        inboxStateChanges += id to state
        return inboxStateUpdateResult ?: InboxItem(id = id, state = state)
    }

    override suspend fun retryInboxItem(id: String): InboxItem {
        maybeFail()
        retriedInboxIds += id
        return inboxRetryResult ?: InboxItem(id = id, status = "analyzing")
    }

    override suspend fun getInboxConfig(): InboxConfig {
        maybeFail()
        return inboxConfigToReturn
    }

    override suspend fun updateInboxConfig(agent: String, model: String, effort: String): InboxConfig {
        maybeFail()
        lastInboxConfigUpdate = Triple(agent, model, effort)
        inboxConfigToReturn = inboxConfigToReturn.copy(agent = agent, model = model, effort = effort)
        return inboxConfigToReturn
    }

    override suspend fun getInboxModels(agent: String): List<String> {
        maybeFail()
        lastRequestedInboxModelsAgent = agent
        return inboxModelsToReturn
    }

    // --- Phone status reporting (phone-agent T2) ---

    /** Every posted snapshot, in order, for asserting reporter behavior. */
    val postedPhoneStatuses = mutableListOf<PhoneStatus>()

    override suspend fun postPhoneStatus(status: PhoneStatus) {
        maybeFail()
        postedPhoneStatuses += status
    }

    // --- Remote screen module ---

    var screenMonitorsToReturn: List<ScreenMonitor> = listOf(
        ScreenMonitor(id = 0, name = "\\\\.\\DISPLAY2", primary = true, width = 1920, height = 1080)
    )
    var screenFramesToEmit: List<ScreenWireMessage> = emptyList()
    var hangScreenStream: Boolean = false
    var lastScreenStreamRequest: ScreenStreamRequest? = null

    data class RecordedScreenAck(
        val sid: String,
        val frameId: Int,
        val bytes: Int,
        val renderMs: Long,
        val frameViewportW: Int,
    )
    val screenAcks = mutableListOf<RecordedScreenAck>()

    val screenQualityChanges = mutableListOf<Pair<String, String>>()

    override suspend fun getScreenMonitors(): List<ScreenMonitor> {
        maybeFail()
        return screenMonitorsToReturn
    }

    override fun screenFrames(request: ScreenStreamRequest): Flow<ScreenWireMessage> {
        maybeFail()
        lastScreenStreamRequest = request
        val frames = screenFramesToEmit
        val hang = hangScreenStream
        return flow {
            frames.forEach { emit(it) }
            if (hang) awaitCancellation()
        }
    }

    /** When > 0, that many next acks throw (transient-failure simulation). */
    var failNextAcks: Int = 0

    var screenConfigToReturn: ScreenRemoteConfig = ScreenRemoteConfig(remoteInputEnabled = false)
    val screenInputs = mutableListOf<ScreenInputEvent>()

    override suspend fun getScreenConfig(): ScreenRemoteConfig {
        maybeFail()
        return screenConfigToReturn
    }

    override suspend fun sendScreenInput(event: ScreenInputEvent) {
        maybeFail()
        screenInputs.add(event)
    }
    override suspend fun acknowledgeScreenFrame(sid: String, frameId: Int, bytes: Int, renderMs: Long) {
        maybeFail()
        if (failNextAcks > 0) {
            failNextAcks--
            throw java.io.IOException("transient ack failure")
        }
        screenAcks.add(
            RecordedScreenAck(
                sid = sid,
                frameId = frameId,
                bytes = bytes,
                renderMs = renderMs,
                frameViewportW = lastScreenStreamRequest?.viewportW ?: -1,
            )
        )
    }

    override suspend fun changeScreenQuality(sid: String, quality: String) {
        maybeFail()
        screenQualityChanges.add(sid to quality)
    }
}

/** A single recorded [getSessions] request, for asserting engine/cursor/limit history. */
data class SessionRequest(val engine: String, val cursor: String?, val limit: Int)

/** One scripted [FakeIduPiClient.ptyStream] call: chunks, then optionally hang. */
data class PtyStreamCall(
    val chunks: List<ByteArray> = emptyList(),
    val hang: Boolean = false
)

/** A queued [getSessions] responder, for controlling response timing deterministically. */
typealias SessionHandler = suspend (engine: String, cursor: String?, limit: Int) -> SessionsPage

/** A queued [getSessionCounts] responder, for controlling counts timing deterministically. */
typealias CountsHandler = suspend () -> SessionCountsResponse

/** Test-only [IduPiClientSource] that always exposes the given [fake]. */
class FakeClientSource(val fake: FakeIduPiClient) : IduPiClientSource {
    override val client: IduPiClient get() = fake
}
