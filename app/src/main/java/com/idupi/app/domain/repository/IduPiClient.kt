package com.idupi.app.domain.repository

import com.idupi.app.data.health.HealthSamplesUpload
import com.idupi.app.data.phone.PhoneStatus
import com.idupi.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    
    // Multiterminal PC Manager: live interactive PTYs only. The older
    // /api/v1/terminals log-stream list is gone; Procesos shows the real OS
    // process list instead (see getOsProcesses below).

    // Interactive PTY terminals (ConPTY on the PC, xterm.js on the phone).
    // The stream is raw VT bytes over a chunked response: it replays recent
    // output first, then goes live, and ENDS when the shell exits or when the
    // client falls far enough behind that the server drops it -- reconnect is
    // the recovery path for that case, not an error.
    suspend fun createPty(cols: Int, rows: Int, cwd: String? = null): PtyInfo
    suspend fun listPtys(): List<PtyInfo>
    fun ptyStream(id: String): Flow<ByteArray>
    suspend fun ptyInput(id: String, data: String)
    suspend fun ptyResize(id: String, cols: Int, rows: Int)
    suspend fun killPty(id: String): Boolean

    // APK deliveries pushed from the PC (`idupi-push <archivo.apk>`).
    suspend fun listDeliveries(): List<DeliveryInfo>

    /**
     * Live PC metrics for the Dashboard: CPU/RAM usage, GPU via nvidia-smi and
     * optional CPU temperature/power from LibreHardwareMonitor. Every sensor
     * may be null; the server never fabricates a reading.
     */
    suspend fun getSystemMetrics(): SystemMetrics

    /**
     * Every process on the PC (`GET /api/v1/os/processes`), with window titles,
     * working-set memory, CPU % and the server's category/protected flags.
     */
    suspend fun getOsProcesses(): OsProcessSample

    /**
     * Asks the PC to end a process. 404 means it was already gone ([KillProcessOutcome.GONE]),
     * 409 means the server protects it ([KillProcessOutcome.PROTECTED]); any other
     * failure throws so the UI can surface the real error.
     */
    suspend fun killOsProcess(pid: Int): KillProcessOutcome

    /**
     * Streams one delivery's APK bytes. Chunked like [ptyStream] so the
     * installer can write straight into a PackageInstaller session instead of
     * buffering the whole APK in memory.
     */
    fun downloadDelivery(id: String): Flow<ByteArray>

    /** `false` when the server reports 404 (already gone). */
    suspend fun deleteDelivery(id: String): Boolean

    fun connectChat(): Flow<ChatEvent>
    suspend fun sendMessage(message: String)

    /**
     * POST [requestId]'s answer back to the server. The body is the structured
     * JSON `{ value, token, sessionId }` -- NOT a stringified boolean, which is
     * the bug that motivated this change (the previous call sent
     * `value.toString()`, turning `true` into the literal text "true" and
     * getting rejected by the server's exact-type validation).
     *
     * `token` and `sessionId` MUST come from the same `UiRequest` that the SSE
     * `ui_request` frame delivered -- they are how the server binds the answer
     * to the session and rejects stale answers with 409.
     *
     * @param value   Any JSON-serializable answer; select/input take a String,
     *                confirm takes a Boolean. `RealIduPiClient` lifts it into
     *                a `JsonElement` before posting so the wire type matches
     *                the registry's `validateUiAnswer` predicate.
     */
    suspend fun sendUiResponse(requestId: String, value: Any, token: String, sessionId: String)
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

    // Phone inbox (phone-inbox T2). `POST /inbox/messages` answers 202 with the
    // stored item while the PC analyzes it asynchronously; polling
    // [getInboxItems] is how the UI sees status/importance/summary appear.
    suspend fun postInboxMessage(message: InboxMessageRequest): InboxItem
    suspend fun getInboxItems(limit: Int = 50): List<InboxItem>
    suspend fun setInboxItemState(id: String, state: String): InboxItem
    suspend fun retryInboxItem(id: String): InboxItem
    suspend fun getInboxConfig(): InboxConfig
    suspend fun updateInboxConfig(agent: String, model: String, effort: String): InboxConfig
    /** Empty list for agents whose catalog the server does not cache yet. */
    suspend fun getInboxModels(agent: String): List<String>

    // Conversation digest (inbox-management T4). `requestInboxDigest` asks the
    // PC to summarize the chat's open items (202 while it runs); repeated calls
    // are cached by the server while the included ids and texts do not change.
    suspend fun requestInboxDigest(app: String, conversation: String): InboxDigest
    suspend fun getInboxDigest(app: String, conversation: String): InboxDigest

    /** Closes every open item of one chat as `handled` or `dismissed`. */
    suspend fun setInboxConversationState(app: String, conversation: String, state: String): Int

    // Phone status reporting (phone-agent T2). The server stores the snapshot
    // as the phone's latest state and its history; the body field names are the
    // contract in idupi-server/docs/phone.md ("Status snapshot contract").
    suspend fun postPhoneStatus(status: PhoneStatus)

    // Phone calendar reporting (phone-calendar T2). The phone owns the
    // calendars; this replaces the PC's snapshot of the next 14 days.
    suspend fun postPhoneCalendar(snapshot: PhoneCalendarSnapshot)

    // Phone health (phone-health T2). The phone owns the sensors and Health
    // Connect; the PC keeps the merged daily history. `installApps` travels
    // with every upload so the PC knows which apps have a reading route.
    suspend fun postHealthSamples(upload: HealthSamplesUpload)
    suspend fun getHealthSummary(days: Int = 7): HealthSummary
    suspend fun getHealthSources(): HealthSourcesResponse

    /**
     * Asks the PC to read the apps that do not publish to Health Connect
     * (phone-health T3). `sources` empty means the server's default source set;
     * the answer is a job the UI polls with [getHealthRefresh].
     */
    suspend fun refreshHealth(sources: List<String> = emptyList()): HealthRefreshJob
    suspend fun getHealthRefresh(id: String): HealthRefreshJob

    /**
     * Pending PC proposals, newest first (phone-calendar T3). The PC never
     * writes to a calendar: the owner approves or rejects each one here.
     */
    suspend fun getPhoneCalendarProposals(
        status: String = PhoneCalendarProposalStatus.PENDING,
        limit: Int = 50,
    ): PhoneCalendarProposalsResponse

    /**
     * Reports the owner's decision. Throws [com.idupi.app.data.remote.IduPiHttpException]
     * with 409 when the proposal was already decided or expired, and 404 when
     * it no longer exists.
     */
    suspend fun decidePhoneCalendarProposal(
        id: String,
        decision: String,
        eventId: String? = null,
    ): PhoneCalendarProposal

    /**
     * Phone long-poll for the next PC command (phone-agent T3).
     *
     * @return the raw JSON of the `command` object, or null when the server
     *   answers 204. Parsing/validation belongs to `CommandParser`, and the raw
     *   form keeps a rejected command's id available for the failure result.
     */
    suspend fun getNextPhoneCommand(waitSeconds: Int): String?

    /** Posts the execution result for one command; the server marks it done/failed. */
    suspend fun postPhoneCommandResult(
        commandId: String,
        ok: Boolean,
        result: JsonElement? = null,
        error: String? = null
    )

    // Remote screen module (docs/remote-screen-module.md)
    suspend fun getScreenMonitors(): List<ScreenMonitor>

    /**
     * Opens the binary chunked frame stream for one monitor. Frames are raw
     * JPEG with wire framing -- NOT SSE, NOT base64.
     */
    fun screenFrames(request: ScreenStreamRequest): Flow<ScreenWireMessage>

    /** Must be called AFTER a frame is rendered; the server paces on it. */
    suspend fun acknowledgeScreenFrame(sid: String, frameId: Int, bytes: Int, renderMs: Long)

    /**
     * Changes stream quality LIVE: "auto" hands the server ladder the wheel,
     * a preset name ("baja"|"media"|"alta"|"ultra") pins it. No restart.
     */
    suspend fun changeScreenQuality(sid: String, quality: String)

    /** What the server allows: remote input ships OFF behind an explicit opt-in. */
    suspend fun getScreenConfig(): ScreenRemoteConfig

    /** Sends one mouse event with NORMALISED coordinates (hito 6). */
    suspend fun sendScreenInput(event: ScreenInputEvent)
}
