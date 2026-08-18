package com.example.idupi.data.remote

import android.util.Log
import com.example.idupi.IduPiApp
import com.example.idupi.domain.model.*
import com.example.idupi.domain.repository.IduPiClient
import com.example.idupi.service.IduPiForegroundService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Thrown when the server is reachable but rejects the request. Carries the real
 * status code so callers can tell "rejected" apart from "unreachable".
 */
class IduPiHttpException(
    val statusCode: Int,
    val url: String,
    override val message: String
) : Exception(message)

@Serializable
private data class MessagePayload(val message: String)

@Serializable
private data class ChatResponse(val status: String, val output: String? = null, val error: String? = null)

@Serializable
private data class ActiveTaskResponse(
    val id: String? = null,
    val message: String? = null,
    val status: String? = null,
    val output: String? = null,
    val error: String? = null
)

@Serializable
private data class SessionHistoryResponse(
    val sessionId: String? = null,
    val history: List<com.example.idupi.domain.repository.SessionHistoryItem> = emptyList(),
    val model: String? = null
)

@Serializable
private data class RejectPayload(val planId: String, val reason: String)

@Serializable
private data class GuardrailPayload(val enable: Boolean)

@Serializable
private data class RemoveProjectsPayload(val projectIds: List<String>, val deleteFiles: Boolean = false)

@Serializable
private data class UpdateOrchestratorModelPayload(
    val engine: String,
    val phase: String,
    val modelId: String,
    val providerId: String? = null,
    val effort: String? = null
)

@Serializable
private data class OrchestratorActionPayload(val action: String)

class RealIduPiClient : IduPiClient {

    private var host: String = "10.0.2.2"
    private var port: Int = 8788
    private var token: String = ""
    private var useHttps: Boolean = false

    private val chatEventFlow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000 // 5 minutos para tareas complejas
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
        install(ContentNegotiation) {
            json(this@RealIduPiClient.json)
        }
    }

    fun configure(host: String, port: Int, token: String, useHttps: Boolean) {
        val trimmed = host.trim()
        this.host = if (trimmed.isEmpty()) "10.0.2.2" else trimmed
        this.port = if (port <= 0) 8788 else port
        // Pasting from a terminal drags whitespace and invisible characters along.
        this.token = sanitizeAuthToken(token)
        this.useHttps = useHttps
    }

    private val httpScheme: String get() = if (useHttps) "https" else "http"
    private val wsScheme: String get() = if (useHttps) "wss" else "ws"
    private val baseUrl: String get() = "$httpScheme://$host:$port"
    private val wsUrl: String get() = "$wsScheme://$host:$port"

    override suspend fun getStatus(): ServerStatus {
        // The only endpoint that reports failure as a value instead of throwing:
        // the dashboard and the connection test both need a ServerStatus back.
        return try {
            send(HttpMethod.Get, "/api/v1/status").body()
        } catch (rejected: IduPiHttpException) {
            // A reachable server that rejects us is NOT an unreachable server.
            // Reporting an auth failure as "no responde" sends the user to debug
            // the network when the real problem is the token.
            disconnectedStatus(rejected.message)
        } catch (e: Exception) {
            disconnectedStatus("Servidor no responde en $baseUrl: ${e.localizedMessage}")
        }
    }

    private fun describeHttpFailure(statusCode: Int): String = when (statusCode) {
        401, 403 -> "El servidor respondió $statusCode en $baseUrl: token inválido o ausente. " +
            "Revisá el token en Conexión — tiene que ser el que imprime el servidor al arrancar."
        404 -> "El servidor respondió 404 en $baseUrl: la ruta no existe. ¿Puerto correcto?"
        else -> "El servidor respondió $statusCode en $baseUrl."
    }

    private fun disconnectedStatus(reason: String) = ServerStatus(
        connected = false,
        pcName = host,
        project = "Desconectado",
        agent = "Error de conexión",
        busy = false,
        queueSize = 0,
        activeAgents = emptyList(),
        cliTask = reason,
        operatingAi = "Offline"
    )

    /**
     * The single definition of the Authorization header. Every authenticated
     * request -- [send] and the chat SSE stream alike -- must go through this
     * instead of attaching the header inline at the call site: that is exactly
     * how a second, silently-unauthenticated request path creeps back in.
     */
    private fun HttpRequestBuilder.authorize() {
        header("Authorization", "Bearer $token")
    }

    /**
     * Central request path: attaches the auth header, checks the response status,
     * and throws [IduPiHttpException] on a non-2xx response instead of letting a
     * clean HTTP rejection masquerade as a deserialization failure.
     */
    private suspend fun send(
        method: HttpMethod,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        val url = "$baseUrl$path"
        val response = client.request(url) {
            this.method = method
            authorize()
            block()
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "${method.value} $path -> ${response.status.value}")
            throw IduPiHttpException(response.status.value, url, describeHttpFailure(response.status.value))
        }
        return response
    }

    override suspend fun getProjects(): List<Project> =
        send(HttpMethod.Get, "/api/v1/projects").body()

    override suspend fun selectProject(projectId: String) {
        send(HttpMethod.Post, "/api/v1/projects/switch") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("projectId" to projectId))
        }
    }

    override suspend fun getSessions(engine: String, cursor: String?, limit: Int): SessionsPage =
        send(HttpMethod.Get, "/api/v1/sessions") {
            parameter("engine", engine)
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
        }.body()

    override suspend fun getSessionCounts(): SessionCountsResponse =
        send(HttpMethod.Get, "/api/v1/sessions/counts").body()

    override suspend fun getSessionHistory(sessionId: String): List<com.example.idupi.domain.repository.SessionHistoryItem> {
        val res: SessionHistoryResponse = send(HttpMethod.Get, "/api/v1/sessions/$sessionId/history").body()
        return res.history
    }

    override suspend fun resumeSession(sessionId: String): Boolean {
        send(HttpMethod.Post, "/api/v1/sessions/resume") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("sessionId" to sessionId))
        }
        return true
    }

    override suspend fun getAvailableModels(): List<com.example.idupi.domain.repository.AiModelItem> =
        send(HttpMethod.Get, "/api/v1/models").body()

    override suspend fun switchModel(modelName: String, provider: String?): Boolean {
        val payload = mutableMapOf("model" to modelName)
        if (provider != null) payload["provider"] = provider
        send(HttpMethod.Post, "/api/v1/model/switch") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        return true
    }

    override suspend fun selectEngine(engineId: String): Boolean {
        send(HttpMethod.Post, "/api/v1/engine/select") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("engineId" to engineId))
        }
        return true
    }

    override suspend fun getTerminals(): List<com.example.idupi.domain.repository.TerminalSessionItem> =
        send(HttpMethod.Get, "/api/v1/terminals").body()

    override suspend fun getTerminalLogs(terminalId: String): List<String> =
        send(HttpMethod.Get, "/api/v1/terminals/$terminalId/logs").body()

    override suspend fun spawnTerminal(name: String?): com.example.idupi.domain.repository.TerminalSessionItem? {
        val payload = if (name != null) mapOf("name" to name) else emptyMap<String, String>()
        val res: Map<String, JsonElement> = send(HttpMethod.Post, "/api/v1/terminals/spawn") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()

        val termObj = res["terminal"]
        return if (termObj != null) {
            json.decodeFromJsonElement(com.example.idupi.domain.repository.TerminalSessionItem.serializer(), termObj)
        } else null
    }

    override suspend fun sendTerminalCommand(terminalId: String, command: String): String {
        val res: Map<String, String> = send(HttpMethod.Post, "/api/v1/terminals/exec") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("termId" to terminalId, "command" to command))
        }.body()
        return res["output"] ?: "Comando enviado."
    }

    override suspend fun restartTerminalServer(terminalId: String): String {
        val res: Map<String, String> = send(HttpMethod.Post, "/api/v1/terminals/restart") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("termId" to terminalId))
        }.body()
        return res["message"] ?: "Servidor reseteado."
    }

    override suspend fun closeTerminal(terminalId: String): Boolean {
        send(HttpMethod.Delete, "/api/v1/terminals/$terminalId")
        return true
    }

    /**
     * Connects to the server's chat SSE stream and translates each frame into a
     * [ChatEvent], merged with [chatEventFlow] -- the in-process events
     * [sendMessage] still emits directly (the "pi_cli" tool marker, the final
     * blob it POSTs/polls for). Both sources feed the same collector so neither
     * consumer of [connectChat] has to know which transport produced an event.
     *
     * The SSE side reconnects on a dropped connection with a bounded exponential
     * backoff (1s, 2s, 4s, 8s, capped at 15s) -- except when the server itself
     * rejected the request ([IduPiHttpException], e.g. a bad token): that will
     * not fix itself by retrying, so it is surfaced once as [ChatEvent.ErrorOccurred]
     * and that side of the merge ends instead of looping forever against a 401.
     */
    override fun connectChat(): Flow<ChatEvent> = merge(chatEventFlow.asSharedFlow(), sseChatFlow())

    private fun sseChatFlow(): Flow<ChatEvent> = flow {
        var attempt = 0
        while (true) {
            try {
                streamChatOnce(this)
                attempt = 0 // the server closed the stream cleanly; retry promptly
            } catch (e: CancellationException) {
                throw e
            } catch (e: IduPiHttpException) {
                Log.w(TAG, "Chat stream rejected by server (${e.statusCode}), not retrying")
                emit(ChatEvent.ErrorOccurred(e.message))
                return@flow
            } catch (e: Exception) {
                Log.w(TAG, "Chat stream connection lost, reconnecting", e)
            }
            val backoffMillis = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
            attempt++
            delay(backoffMillis)
        }
    }

    /**
     * Opens a single SSE connection to `/api/v1/chat/stream` and emits a
     * [ChatEvent] for every well-formed frame until the connection ends (the
     * server closes it, the socket drops, or the collector is cancelled).
     *
     * Deliberately does NOT go through [send]: that helper calls
     * `client.request(...)`, which reads the whole response body before
     * returning -- fine for normal JSON endpoints, but it would block forever
     * on a stream that never completes. `prepareGet(...).execute { ... }`
     * instead hands back a response whose body can be read incrementally as a
     * channel while the connection stays open.
     */
    private suspend fun streamChatOnce(collector: FlowCollector<ChatEvent>) {
        val url = "$baseUrl/api/v1/chat/stream"
        client.prepareGet(url) {
            authorize()
            // The stream is long-lived by design; the client's default 5-minute
            // *request* timeout would otherwise tear it down mid-conversation, so
            // that one stays unbounded. socketTimeoutMillis is different: it is a
            // per-read idle timeout, not a total-duration one. The server writes a
            // ": ping\n\n" heartbeat every 20s specifically so a stale connection
            // (network handoff, NAT drop, phone doze) can be detected -- but only
            // if something on this side is actually watching for it. Leaving this
            // infinite too means a half-dead socket just suspends readUTF8Line()
            // forever with no exception, so sseChatFlow()'s reconnect/backoff loop
            // never fires: the CLI finishes, the server publishes the events, and
            // this connection never learns about it. Bounding it just past the
            // heartbeat interval turns a silently-dead connection into a
            // SocketTimeoutException, which IS caught below and triggers a
            // reconnect.
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 45_000
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(TAG, "GET /api/v1/chat/stream -> ${response.status.value}")
                throw IduPiHttpException(response.status.value, url, describeHttpFailure(response.status.value))
            }
            val channel = response.bodyAsChannel()
            val parser = SseFrameParser()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                val frame = parser.feedLine(line) ?: continue
                val event = parseSseEvent(json, frame.event, frame.data) ?: continue
                collector.emit(event)
            }
        }
    }

    override suspend fun sendMessage(message: String) {
        try {
            // Arrancar Foreground Service en Android para evitar que el sistema cancele la red al minimizar
            try {
                IduPiForegroundService.start(IduPiApp.instance, "Procesando en tu PC: ${message.take(30)}...")
            } catch (e: Exception) {}

            chatEventFlow.emit(ChatEvent.Thinking(true))

            var outputText: String? = null

            try {
                // This request stays open for as long as the CLI takes to answer --
                // and with subagent delegation that can be minutes, with zero bytes
                // flowing in either direction the whole time. That is exactly the
                // shape of connection a mobile-carrier NAT silently drops after
                // 30-60s of idle: no error on either end, the request just never
                // returns. A short timeout here deliberately abandons that fragile
                // long-lived connection quickly and falls back to the poll loop
                // below, which uses short, discrete requests -- each one lives only
                // a couple of seconds, so there is never a long idle window for a
                // NAT to kill.
                val response: ChatResponse = send(HttpMethod.Post, "/api/v1/chat/message") {
                    contentType(ContentType.Application.Json)
                    setBody(MessagePayload(message))
                    timeout {
                        requestTimeoutMillis = 20_000
                        socketTimeoutMillis = 20_000
                    }
                }.body()

                outputText = response.output?.ifBlank { "Procesado correctamente por Pi CLI." }
                    ?: response.error

            } catch (rejected: IduPiHttpException) {
                // The server answered and refused. Retrying cannot change that, so
                // fail fast instead of polling over a bad token.
                throw rejected
            } catch (netErr: Exception) {
                // Falls here on the 20s bail-out above, on a minimized app, or on a
                // transient socket drop. Poll /api/v1/chat/active-task on short,
                // discrete requests until the task completes -- up to 5 minutes,
                // matching the app's other long-task timeouts, since real
                // subagent-delegated work can legitimately take several minutes.
                for (attempt in 1..150) {
                    delay(2000)
                    try {
                        val taskRes: ActiveTaskResponse = send(HttpMethod.Get, "/api/v1/chat/active-task").body()

                        if (taskRes.status == "completed" && !taskRes.output.isNullOrBlank()) {
                            outputText = taskRes.output
                            break
                        } else if (taskRes.status == "error") {
                            outputText = taskRes.error ?: "Error en procesamiento en segundo plano."
                            break
                        }
                    } catch (rejected: IduPiHttpException) {
                        // Same reasoning: a rejection will not become an acceptance.
                        throw rejected
                    } catch (e: Exception) {
                        // Transient: keep polling until the task shows up.
                    }
                }
            }

            if (outputText != null) {
                chatEventFlow.emit(ChatEvent.MessageEnded(outputText))
            }

        } catch (e: Exception) {
            chatEventFlow.emit(ChatEvent.Thinking(false))
            chatEventFlow.emit(ChatEvent.ErrorOccurred("Error de comunicación: ${e.localizedMessage}"))
        } finally {
            chatEventFlow.emit(ChatEvent.Thinking(false))
            // Detener el Foreground Service una vez completada la tarea
            try {
                IduPiForegroundService.stop(IduPiApp.instance)
            } catch (e: Exception) {}
        }
    }

    override suspend fun sendUiResponse(requestId: String, value: Any) {
        send(HttpMethod.Post, "/api/v1/chat/ui-response/$requestId") {
            contentType(ContentType.Application.Json)
            setBody(value.toString())
        }
    }

    override suspend fun cancelCurrentTask() {
        send(HttpMethod.Post, "/api/v1/chat/cancel")
    }

    override suspend fun addProject(name: String, path: String): String? {
        send(HttpMethod.Post, "/api/v1/projects/add") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("name" to name, "path" to path))
        }
        return name
    }

    override suspend fun getProjectFiles(projectId: String): List<FileNode> =
        send(HttpMethod.Get, "/api/v1/projects/$projectId/files").body()

    override suspend fun getFileContent(projectId: String, path: String): String {
        val res: Map<String, String> = send(HttpMethod.Get, "/api/v1/projects/$projectId/file-content?path=$path").body()
        return res["content"] ?: ""
    }

    override suspend fun resetTerminal() {
        send(HttpMethod.Post, "/api/v1/terminal/reset")
    }

    override suspend fun getAvailableCommands(): List<QuickCommand> =
        send(HttpMethod.Get, "/api/v1/commands").body()

    override suspend fun getAlerts(): List<SupervisorAlert> =
        send(HttpMethod.Get, "/api/v1/alerts").body()

    override suspend fun markAlertAsRead(alertId: String) {
        send(HttpMethod.Post, "/api/v1/alerts/$alertId/read")
    }

    override suspend fun approveMasterPlan(planId: String) {
        send(HttpMethod.Post, "/api/v1/plans/$planId/approve")
    }

    override suspend fun rejectMasterPlan(planId: String, reason: String) {
        send(HttpMethod.Post, "/api/v1/plans/$planId/reject") {
            contentType(ContentType.Application.Json)
            setBody(RejectPayload(planId, reason))
        }
    }

    override suspend fun toggleGuardrails(enable: Boolean) {
        send(HttpMethod.Post, "/api/v1/guardrails") {
            contentType(ContentType.Application.Json)
            setBody(GuardrailPayload(enable))
        }
    }

    override suspend fun browseDirectory(path: String?): DirectoryBrowseResponse {
        val queryParam = if (!path.isNullOrBlank()) "?path=" + java.net.URLEncoder.encode(path, "UTF-8") else ""
        return send(HttpMethod.Get, "/api/v1/fs/browse$queryParam").body()
    }

    override suspend fun removeProjects(projectIds: List<String>, deleteFiles: Boolean): Boolean {
        return try {
            send(HttpMethod.Post, "/api/v1/projects/remove") {
                contentType(ContentType.Application.Json)
                setBody(RemoveProjectsPayload(projectIds, deleteFiles))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove projects: ${e.localizedMessage}")
            false
        }
    }

    override suspend fun getOrchestratorStatus(): OrchestratorStatus =
        send(HttpMethod.Get, "/api/v1/orchestrator/status").body()

    override suspend fun updateOrchestratorModel(
        engine: String,
        phase: String,
        modelId: String,
        providerId: String?,
        effort: String?
    ): Boolean {
        return try {
            send(HttpMethod.Post, "/api/v1/orchestrator/models/update") {
                contentType(ContentType.Application.Json)
                setBody(UpdateOrchestratorModelPayload(engine, phase, modelId, providerId, effort))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update orchestrator model: ${e.localizedMessage}")
            false
        }
    }

    override suspend fun runOrchestratorAction(action: String): OrchestratorActionResponse =
        send(HttpMethod.Post, "/api/v1/orchestrator/action") {
            contentType(ContentType.Application.Json)
            setBody(OrchestratorActionPayload(action))
        }.body()

    override suspend fun getProviderModels(providerId: String): List<ProviderModelItem> {
        return try {
            val res: ProviderModelsResponse = send(HttpMethod.Get, "/api/v1/orchestrator/providers/$providerId/models").body()
            res.models
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get provider models: ${e.localizedMessage}")
            emptyList()
        }
    }

    override suspend fun applySddProfile(profileId: String): Boolean {
        return try {
            send(HttpMethod.Post, "/api/v1/orchestrator/profiles/apply") {
                contentType(ContentType.Application.Json)
                setBody(ApplySddProfilePayload(profileId))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply SDD profile: ${e.localizedMessage}")
            false
        }
    }

    override suspend fun saveSddProfile(profile: SddProfileItem): Boolean {
        return try {
            send(HttpMethod.Post, "/api/v1/orchestrator/profiles/save") {
                contentType(ContentType.Application.Json)
                setBody(profile)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save SDD profile: ${e.localizedMessage}")
            false
        }
    }

    override suspend fun deleteSddProfile(profileId: String): Boolean {
        return try {
            send(HttpMethod.Post, "/api/v1/orchestrator/profiles/delete") {
                contentType(ContentType.Application.Json)
                setBody(ApplySddProfilePayload(profileId))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete SDD profile: ${e.localizedMessage}")
            false
        }
    }

    companion object {
        private const val TAG = "RealIduPiClient"
    }
}

@Serializable
private data class ApplySddProfilePayload(val profileId: String)
