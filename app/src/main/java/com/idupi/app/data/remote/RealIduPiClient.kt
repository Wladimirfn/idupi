package com.idupi.app.data.remote

import android.util.Log
import com.idupi.app.IduPiApp
import com.idupi.app.data.health.HealthSamplesUpload
import com.idupi.app.data.phone.PhoneStatus
import com.idupi.app.domain.model.*
import com.idupi.app.domain.repository.IduPiClient
import com.idupi.app.service.IduPiForegroundService
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
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Thrown when the server is reachable but rejects the request. Carries the real
 * status code so callers can tell "rejected" apart from "unreachable".
 */
class IduPiHttpException(
    val statusCode: Int,
    val url: String,
    override val message: String,
    /** Server error `code` when the body carried one (`EXPIRED`, …). */
    val code: String? = null,
) : Exception(message)

@Serializable
// clientTaskId is generated here, not by the server: the POST below is
// abandoned after 20s, so the client never sees a server-assigned id and has
// nothing else to correlate its poll against.
private data class MessagePayload(val message: String, val clientTaskId: String)

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
    val history: List<com.idupi.app.domain.repository.SessionHistoryItem> = emptyList(),
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

// fix-ui-request-selection / Phase 4 (Android wire-up): the body posted to
// /api/v1/chat/ui-response/:requestId. `value` is typed as JsonElement so the
// wire type matches the registry's exact-type validation:
//   select  -> JSON string (one of options)
//   confirm -> JSON boolean
//   input   -> JSON string (non-empty)
// The previous implementation used `value.toString()` which turned a Kotlin
// `true` into the text "true" and the server's `typeof value !== "boolean"`
// check rejected it. Lifting to JsonElement preserves the original type.
@Serializable
private data class UiResponsePayload(
    val value: JsonElement,
    val token: String,
    val sessionId: String,
)

/**
 * Result posted to `POST /api/v1/phone/commands/{id}/result` (phone-agent T3).
 * Defaults stay null so kotlinx omits them; the server treats a missing result
 * or error exactly like an explicit null.
 */
@Serializable
private data class PhoneCommandResultPayload(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
)

/**
 * Lifts the caller's [Any] into the [JsonElement] the wire format needs.
 * Anything we don't recognise is stringified; the registry's `validateUiAnswer`
 * rejects unrecognised types with 400, so the failure is visible rather than
 * silent.
 */
private fun Any.toJsonElement(): JsonElement = when (this) {
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is JsonElement -> this
    else -> JsonPrimitive(this.toString())
}

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

    /**
     * Read-only snapshot of the values every request is built from. Used by
     * [com.idupi.app.data.IduPiClientProvider.clear]'s test and by diagnostics;
     * it must never become a second way to configure the client.
     */
    internal fun connectionSnapshot(): ClientConnectionSnapshot = ClientConnectionSnapshot(
        host = host,
        port = port,
        token = token,
        useHttps = useHttps
    )

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
            "Revisa el token en Conexión — tiene que ser el que imprime el servidor al arrancar."
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
            // The error body carries a short `code` (EXPIRED, ALREADY_DECIDED…).
            // Reading it here keeps every caller from parsing bodies itself.
            val code = runCatching {
                json.parseToJsonElement(response.bodyAsText())
                    .jsonObject["code"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw IduPiHttpException(
                response.status.value,
                url,
                describeHttpFailure(response.status.value),
                code,
            )
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

    override suspend fun getSessions(engine: String, cursor: String?, limit: Int, includeAll: Boolean): SessionsPage =
        send(HttpMethod.Get, "/api/v1/sessions") {
            parameter("engine", engine)
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
            // Only sent when on: the server's default is already the filtered
            // list, so an absent parameter and "false" mean the same thing.
            if (includeAll) parameter("includeAll", "true")
        }.body()

    override suspend fun getSessionCounts(includeAll: Boolean): SessionCountsResponse =
        send(HttpMethod.Get, "/api/v1/sessions/counts") {
            if (includeAll) parameter("includeAll", "true")
        }.body()

    override suspend fun startNewSession(): Boolean {
        send(HttpMethod.Post, "/api/v1/sessions/new")
        return true
    }

    override suspend fun getSessionHistory(sessionId: String): List<com.idupi.app.domain.repository.SessionHistoryItem> {
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

    override suspend fun getAvailableModels(): List<com.idupi.app.domain.repository.AiModelItem> =
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

    // --- Interactive PTY terminals ---

    override suspend fun createPty(cols: Int, rows: Int, cwd: String?): PtyInfo =
        send(HttpMethod.Post, "/api/v1/pty") {
            contentType(ContentType.Application.Json)
            setBody(CreatePtyPayload(cols = cols, rows = rows, cwd = cwd))
        }.body()

    override suspend fun listPtys(): List<PtyInfo> =
        send(HttpMethod.Get, "/api/v1/pty").body<PtyListResponse>().terminals

    /**
     * Raw VT bytes over the existing chunked-response pattern. Same shape as
     * [screenFrames] -- plain `send` would buffer the whole body, which never
     * completes on a live terminal -- but with NO idle socket timeout: an
     * interactive terminal legitimately produces zero bytes while the user
     * reads the screen, so a per-read timeout would tear down healthy idle
     * sessions every 30 seconds.
     */
    override fun ptyStream(id: String): Flow<ByteArray> = flow {
        val url = "$baseUrl/api/v1/pty/$id/stream"
        client.prepareGet(url) {
            authorize()
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(TAG, "GET /api/v1/pty/$id/stream -> ${response.status.value}")
                throw IduPiHttpException(response.status.value, url, describeHttpFailure(response.status.value))
            }
            val channel = response.bodyAsChannel()
            val buf = ByteArray(32 * 1024)
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n <= 0) continue
                emit(buf.copyOfRange(0, n))
            }
        }
    }

    override suspend fun ptyInput(id: String, data: String) {
        send(HttpMethod.Post, "/api/v1/pty/$id/input") {
            contentType(ContentType.Application.Json)
            setBody(PtyInputPayload(data))
        }
    }

    override suspend fun ptyResize(id: String, cols: Int, rows: Int) {
        send(HttpMethod.Post, "/api/v1/pty/$id/resize") {
            contentType(ContentType.Application.Json)
            setBody(PtyResizePayload(cols = cols, rows = rows))
        }
    }

    /**
     * Tree-kill request. A 404 means the terminal was already gone, which is
     * the outcome the caller wanted -- reporting `false` instead of throwing
     * keeps "stop an already-dead terminal" from surfacing as an error.
     */
    override suspend fun killPty(id: String): Boolean = try {
        send(HttpMethod.Delete, "/api/v1/pty/$id")
        true
    } catch (e: IduPiHttpException) {
        if (e.statusCode == 404) false else throw e
    }

    // --- APK deliveries (PC -> phone) ---

    override suspend fun listDeliveries(): List<DeliveryInfo> =
        send(HttpMethod.Get, "/api/v1/deliveries").body<DeliveryListResponse>().deliveries

    override suspend fun getSystemMetrics(): SystemMetrics =
        send(HttpMethod.Get, "/api/v1/system/metrics").body()

    // --- OS processes (Procesos screen, T10) ---

    override suspend fun getOsProcesses(): OsProcessSample =
        send(HttpMethod.Get, "/api/v1/os/processes").body()

    /**
     * 200 -> killed; 404 -> already gone (the outcome the caller wanted);
     * 409 -> the server refuses because the process is protected. Anything else
     * is a real failure and propagates as [IduPiHttpException].
     */
    override suspend fun killOsProcess(pid: Int): KillProcessOutcome = try {
        send(HttpMethod.Post, "/api/v1/os/processes/$pid/kill")
        KillProcessOutcome.KILLED
    } catch (e: IduPiHttpException) {
        when (e.statusCode) {
            404 -> KillProcessOutcome.GONE
            409 -> KillProcessOutcome.PROTECTED
            else -> throw e
        }
    }

    /**
     * Raw APK bytes over the chunked-response pattern: the caller writes each
     * chunk straight into the PackageInstaller session, so neither side ever
     * holds a whole APK in memory. Same timeout shape as [ptyStream]: an APK
     * on a slow link can legitimately pause between reads, so the client does
     * not impose a per-read deadline -- the installer bounds the attempt by
     * cancelling the coroutine instead.
     */
    override fun downloadDelivery(id: String): Flow<ByteArray> = flow {
        val url = "$baseUrl/api/v1/deliveries/$id/download"
        client.prepareGet(url) {
            authorize()
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(TAG, "GET /api/v1/deliveries/$id/download -> ${response.status.value}")
                throw IduPiHttpException(response.status.value, url, describeHttpFailure(response.status.value))
            }
            val channel = response.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n <= 0) continue
                emit(buf.copyOfRange(0, n))
            }
        }
    }

    override suspend fun deleteDelivery(id: String): Boolean = try {
        send(HttpMethod.Delete, "/api/v1/deliveries/$id")
        true
    } catch (e: IduPiHttpException) {
        if (e.statusCode == 404) false else throw e
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
    // --- Remote screen module ---

    override suspend fun getScreenMonitors(): List<ScreenMonitor> =
        send(HttpMethod.Get, "/api/v1/screen/monitors").body()

    /**
     * Opens /api/v1/screen/stream as binary chunked HTTP and decodes the helper
     * wire framing incrementally. Mirrors [streamChatOnce]: plain `send` reads
     * the whole response body first, which never completes on this stream.
     * NOT SSE and NOT base64 -- base64 would add 33% to the hottest path.
     */
    override fun screenFrames(request: ScreenStreamRequest): Flow<ScreenWireMessage> = flow {
        val url = "$baseUrl/api/v1/screen/stream"
        client.prepareGet(url) {
            authorize()
            parameter("sid", request.sid)
            parameter("monitor", request.monitor)
            parameter("viewportW", request.viewportW)
            parameter("viewportH", request.viewportH)
            parameter("quality", request.quality)
            timeout {
                // Ack-paced stream: the server sends nothing while we render.
                // A per-read idle bound turns a dead socket into an exception
                // instead of a silent hang; total duration stays unbounded.
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 30_000
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(TAG, "GET /api/v1/screen/stream -> ${response.status.value}")
                throw IduPiHttpException(response.status.value, url, describeHttpFailure(response.status.value))
            }
            val channel = response.bodyAsChannel()
            val codec = ScreenFrameCodec()
            val buf = ByteArray(64 * 1024)
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n <= 0) continue
                for (message in codec.feed(buf.copyOfRange(0, n))) {
                    // Frames render; controls (quality_changed) update state.
                    emit(message)
                }
            }
        }
    }

    /** Called only AFTER the frame is rendered; the server paces captures on it. */
    override suspend fun acknowledgeScreenFrame(sid: String, frameId: Int, bytes: Int, renderMs: Long) {
        send(HttpMethod.Post, "/api/v1/screen/ack") {
            contentType(ContentType.Application.Json)
            setBody(ScreenAckPayload(sid = sid, frameId = frameId, bytes = bytes, renderMs = renderMs))
        }
    }

    /** Live quality switch -- the server validates and announces the result. */
    override suspend fun changeScreenQuality(sid: String, quality: String) {
        send(HttpMethod.Post, "/api/v1/screen/quality") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("sid" to sid, "quality" to quality))
        }
    }

    override suspend fun getScreenConfig(): ScreenRemoteConfig =
        send(HttpMethod.Get, "/api/v1/screen/config").body()

    /** Normalised coordinates only -- pixel maths lives in the Go helper. */
    override suspend fun sendScreenInput(event: ScreenInputEvent) {
        send(HttpMethod.Post, "/api/v1/screen/input") {
            contentType(ContentType.Application.Json)
            setBody(event)
        }
    }
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
            val clientTaskId = java.util.UUID.randomUUID().toString()

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
                    setBody(MessagePayload(message, clientTaskId))
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
                        // Ask for THIS task. Without the id the server answers
                        // with whatever task is current, so a second message
                        // handed its answer to this poll -- or this poll never
                        // saw its own result at all.
                        val taskRes: ActiveTaskResponse =
                            send(HttpMethod.Get, "/api/v1/chat/active-task?clientTaskId=$clientTaskId").body()

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

    override suspend fun sendUiResponse(requestId: String, value: Any, token: String, sessionId: String) {
        send(HttpMethod.Post, "/api/v1/chat/ui-response/$requestId") {
            contentType(ContentType.Application.Json)
            setBody(
                UiResponsePayload(
                    value = value.toJsonElement(),
                    token = token,
                    sessionId = sessionId,
                )
            )
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

    // --- Phone inbox (phone-inbox T2) ---

    override suspend fun postInboxMessage(message: InboxMessageRequest): InboxItem =
        send(HttpMethod.Post, "/api/v1/inbox/messages") {
            contentType(ContentType.Application.Json)
            setBody(message)
        }.body<InboxItemEnvelope>().item

    override suspend fun getInboxItems(limit: Int): List<InboxItem> =
        send(HttpMethod.Get, "/api/v1/inbox") {
            parameter("limit", limit)
        }.body<InboxItemsEnvelope>().items

    override suspend fun setInboxItemState(id: String, state: String): InboxItem =
        send(HttpMethod.Post, "/api/v1/inbox/$id/state") {
            contentType(ContentType.Application.Json)
            setBody(InboxStatePayload(state))
        }.body<InboxItemEnvelope>().item

    override suspend fun retryInboxItem(id: String): InboxItem =
        send(HttpMethod.Post, "/api/v1/inbox/$id/retry")
            .body<InboxItemEnvelope>().item

    override suspend fun interactWithInboxItem(
        id: String,
        instruction: String,
        projectId: String?
    ): InboxItem {
        // The 202 body carries only the interaction; the rest of the item is
        // unchanged on the server, so the caller merges this block in.
        val envelope = send(HttpMethod.Post, "/api/v1/inbox/$id/interact") {
            contentType(ContentType.Application.Json)
            setBody(InboxInteractionRequest(instruction = instruction, projectId = projectId))
        }.body<InboxInteractionEnvelope>()
        return InboxItem(id = id, interaction = envelope.interaction)
    }

    override suspend fun getInboxConfig(): InboxConfig =
        send(HttpMethod.Get, "/api/v1/inbox/config").body()

    override suspend fun updateInboxConfig(
        agent: String,
        model: String,
        effort: String,
        proactive: Boolean?
    ): InboxConfig =
        send(HttpMethod.Put, "/api/v1/inbox/config") {
            contentType(ContentType.Application.Json)
            // Built by hand so a null proactive is omitted instead of encoded
            // as an explicit null the server rejects (alerts-flow A4).
            setBody(buildJsonObject {
                put("agent", agent)
                put("model", model)
                put("effort", effort)
                if (proactive != null) put("proactive", proactive)
            })
        }.body()

    override suspend fun getInboxModels(agent: String): List<String> =
        send(HttpMethod.Get, "/api/v1/inbox/models") {
            parameter("agent", agent)
        }.body<InboxModelsEnvelope>().models

    // --- Conversation digest (inbox-management T4) ---

    override suspend fun requestInboxDigest(app: String, conversation: String): InboxDigest =
        send(HttpMethod.Post, "/api/v1/inbox/digest") {
            contentType(ContentType.Application.Json)
            setBody(InboxDigestRequest(app = app, conversation = conversation))
        }.body<InboxDigestEnvelope>().digest

    override suspend fun getInboxDigest(app: String, conversation: String): InboxDigest =
        send(HttpMethod.Get, "/api/v1/inbox/digest") {
            parameter("app", app)
            parameter("conversation", conversation)
        }.body<InboxDigestEnvelope>().digest

    override suspend fun setInboxConversationState(
        app: String,
        conversation: String,
        state: String
    ): Int =
        send(HttpMethod.Post, "/api/v1/inbox/conversation/state") {
            contentType(ContentType.Application.Json)
            setBody(
                InboxConversationStateRequest(
                    app = app,
                    conversation = conversation,
                    state = state
                )
            )
        }.body<InboxConversationStateResult>().closed

    // --- Promotions digest and bulk close (alerts-flow A2) ---

    override suspend fun requestInboxPromotionsDigest(): InboxDigest =
        send(HttpMethod.Post, "/api/v1/inbox/digest") {
            contentType(ContentType.Application.Json)
            setBody(InboxScopeDigestRequest(scope = InboxDigestScope.PROMOTIONS))
        }.body<InboxDigestEnvelope>().digest

    override suspend fun getInboxPromotionsDigest(): InboxDigest =
        send(HttpMethod.Get, "/api/v1/inbox/digest") {
            parameter("scope", InboxDigestScope.PROMOTIONS)
        }.body<InboxDigestEnvelope>().digest

    override suspend fun setInboxPromotionsState(state: String): Int =
        send(HttpMethod.Post, "/api/v1/inbox/promotions/state") {
            contentType(ContentType.Application.Json)
            setBody(InboxStatePayload(state))
        }.body<InboxConversationStateResult>().closed

    // --- Phone status reporting (phone-agent T2) ---

    override suspend fun postPhoneStatus(status: PhoneStatus) {
        send(HttpMethod.Post, "/api/v1/phone/status") {
            contentType(ContentType.Application.Json)
            setBody(status)
        }
    }

    // --- Phone calendar reporting (phone-calendar T2) ---

    override suspend fun postPhoneCalendar(snapshot: PhoneCalendarSnapshot) {
        send(HttpMethod.Post, "/api/v1/phone/calendar") {
            contentType(ContentType.Application.Json)
            setBody(snapshot)
        }
    }

    override suspend fun getPhoneCalendarProposals(
        status: String,
        limit: Int,
    ): PhoneCalendarProposalsResponse =
        send(HttpMethod.Get, "/api/v1/phone/calendar/proposals") {
            parameter("status", status)
            parameter("limit", limit)
        }.body()

    override suspend fun decidePhoneCalendarProposal(
        id: String,
        decision: String,
        eventId: String?,
    ): PhoneCalendarProposal =
        send(
            HttpMethod.Post,
            "/api/v1/phone/calendar/proposals/$id/decision",
        ) {
            contentType(ContentType.Application.Json)
            setBody(ProposalDecisionPayload(decision = decision, eventId = eventId))
        }.body<ProposalDecisionEnvelope>().proposal

    // --- Phone health (phone-health T2) ---

    override suspend fun postHealthSamples(upload: HealthSamplesUpload) {
        send(HttpMethod.Post, "/api/v1/health/samples") {
            contentType(ContentType.Application.Json)
            setBody(upload)
        }
    }

    override suspend fun getHealthSummary(days: Int): HealthSummary =
        send(HttpMethod.Get, "/api/v1/health/summary") {
            parameter("days", days.coerceIn(1, 120))
        }.body()

    override suspend fun getHealthSources(): HealthSourcesResponse =
        send(HttpMethod.Get, "/api/v1/health/sources").body()

    override suspend fun refreshHealth(sources: List<String>): HealthRefreshJob =
        send(HttpMethod.Post, "/api/v1/health/refresh") {
            contentType(ContentType.Application.Json)
            setBody(HealthRefreshRequest(sources = sources.takeIf { it.isNotEmpty() }))
        }.body<HealthRefreshEnvelope>().job

    override suspend fun getHealthRefresh(id: String): HealthRefreshJob =
        send(HttpMethod.Get, "/api/v1/health/refresh/$id").body<HealthRefreshEnvelope>().job

    override suspend fun getHealthDigest(date: String?): HealthDigest =
        send(HttpMethod.Get, "/api/v1/health/digest") {
            if (date != null) parameter("date", date)
        }.body()

    override suspend fun requestHealthDigest(refresh: Boolean, date: String?): HealthDigest =
        send(HttpMethod.Post, "/api/v1/health/digest") {
            contentType(ContentType.Application.Json)
            // Built by hand so an absent date is omitted instead of encoded as
            // an explicit null the server would have to ignore.
            setBody(buildJsonObject {
                put("refresh", refresh)
                if (date != null) put("date", date)
            })
        }.body()

    override suspend fun getHealthAdbStatus(): HealthAdbStatus =
        send(HttpMethod.Get, "/api/v1/health/adb").body()

    // --- Phone control (phone-agent T3) ---

    /**
     * Long-polls for the next command. The request timeout must outlive the
     * server's hold, or the client would cancel a healthy poll every cycle.
     * A 204 is the normal "nothing queued" answer and is not an error.
     */
    override suspend fun getNextPhoneCommand(waitSeconds: Int): String? {
        val wait = waitSeconds.coerceIn(1, 30)
        val timeoutMillis = wait * 1000L + 15_000L
        val response = send(HttpMethod.Get, "/api/v1/phone/commands/next") {
            parameter("wait", wait)
            timeout {
                requestTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }
        if (response.status.value == 204) return null
        val body = response.body<JsonObject>()
        return body["command"]?.toString()
    }

    override suspend fun postPhoneCommandResult(
        commandId: String,
        ok: Boolean,
        result: JsonElement?,
        error: String?,
    ) {
        send(HttpMethod.Post, "/api/v1/phone/commands/$commandId/result") {
            contentType(ContentType.Application.Json)
            setBody(PhoneCommandResultPayload(ok = ok, result = result, error = error))
        }
    }

    // --- Phone approvals (phone-approvals T2) ---

    override suspend fun getPhoneApprovals(state: String, limit: Int): PhoneApprovalsResponse =
        send(HttpMethod.Get, "/api/v1/phone/approvals") {
            parameter("state", state)
            parameter("limit", limit)
        }.body()

    override suspend fun decidePhoneApproval(
        id: String,
        approve: Boolean,
    ): PhoneApprovalDecisionResult =
        try {
            val approval = send(
                HttpMethod.Post,
                "/api/v1/phone/approvals/$id/decision",
            ) {
                contentType(ContentType.Application.Json)
                setBody(PhoneApprovalDecisionPayload(if (approve) "approve" else "reject"))
            }.body<PhoneApprovalDecisionEnvelope>().approval
            PhoneApprovalDecisionResult.Decided(approval)
        } catch (e: IduPiHttpException) {
            when (e.statusCode) {
                404 -> PhoneApprovalDecisionResult.NotFound
                409 -> PhoneApprovalDecisionResult.Conflict(
                    e.code ?: PhoneApprovalDecisionResult.CODE_ALREADY_DECIDED
                )
                else -> throw e
            }
        }

    companion object {
        private const val TAG = "RealIduPiClient"
    }
}

@Serializable
private data class ApplySddProfilePayload(val profileId: String)

/** `POST /api/v1/phone/approvals/{id}/decision` body. */
@Serializable
private data class PhoneApprovalDecisionPayload(val decision: String)

/** The approval decision route wraps the updated approval. */
@Serializable
private data class PhoneApprovalDecisionEnvelope(val approval: PhoneApproval)

/** `POST /api/v1/phone/calendar/proposals/{id}/decision` body. */
@Serializable
private data class ProposalDecisionPayload(val decision: String, val eventId: String? = null)

/** The decision route wraps the updated proposal. */
@Serializable
private data class ProposalDecisionEnvelope(val proposal: PhoneCalendarProposal)

/** `POST /api/v1/health/refresh` body; an empty list keeps the server default. */
@Serializable
private data class HealthRefreshRequest(val sources: List<String>? = null)

/** Both health refresh routes wrap the job. */
@Serializable
private data class HealthRefreshEnvelope(val job: HealthRefreshJob)

// --- Phone inbox wire payloads (phone-inbox T2) ---

/** `POST /inbox/messages`, `/inbox/{id}/state` and `/inbox/{id}/retry` wrap the item. */
@Serializable
private data class InboxItemEnvelope(val item: InboxItem)

/** `GET /inbox?limit=N`. */
@Serializable
private data class InboxItemsEnvelope(val items: List<InboxItem> = emptyList())

/** `GET /inbox/models?agent=...`. */
@Serializable
private data class InboxModelsEnvelope(val models: List<String> = emptyList())

/** `POST /inbox/{id}/state` body. */
@Serializable
private data class InboxStatePayload(val state: String)

/** `POST|GET /inbox/digest` wraps the conversation digest. */
@Serializable
private data class InboxDigestEnvelope(val digest: InboxDigest)

/** `POST /inbox/{id}/interact` wraps the item's `interaction` (alerts-flow A3). */
@Serializable
private data class InboxInteractionEnvelope(val interaction: InboxInteraction = InboxInteraction())

/** `POST /inbox/digest` body when the digest spans a scope (alerts-flow A2). */
@Serializable
private data class InboxScopeDigestRequest(val scope: String)


/**
 * The host/port/token every request is built from, as one immutable value.
 * Package-internal so tests can assert a reset without exposing mutators.
 */
internal data class ClientConnectionSnapshot(
    val host: String,
    val port: Int,
    val token: String,
    val useHttps: Boolean
)

// --- Interactive PTY wire payloads ---

/** POST /api/v1/pty body. A null cwd makes the server use its default. */
@Serializable
private data class CreatePtyPayload(val cols: Int, val rows: Int, val cwd: String? = null)

/** GET /api/v1/pty response: `{ "terminals": [info...] }`. */
@Serializable
private data class PtyListResponse(val terminals: List<PtyInfo> = emptyList())

/** GET /api/v1/deliveries response: `{ "deliveries": [info...] }`, newest first. */
@Serializable
private data class DeliveryListResponse(val deliveries: List<DeliveryInfo> = emptyList())

/** POST /api/v1/pty/{id}/input body: UTF-8 keystrokes, max 256 KiB. */
@Serializable
private data class PtyInputPayload(val data: String)

/** POST /api/v1/pty/{id}/resize body. */
@Serializable
private data class PtyResizePayload(val cols: Int, val rows: Int)

