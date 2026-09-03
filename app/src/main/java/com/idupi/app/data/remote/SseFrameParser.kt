package com.idupi.app.data.remote

import android.util.Log
import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.model.UiRequest
import com.idupi.app.domain.model.UiRequestMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One decoded SSE frame: the `event:` name (null if the frame carried no
 * `event:` line, e.g. a bare `data:` "message" event) and the concatenated
 * `data:` payload.
 */
data class SseFrame(val event: String?, val data: String)

/**
 * Stateful, line-at-a-time parser for the `text/event-stream` wire format.
 * Pure aside from its own accumulation state, so it can be unit-tested
 * without a network connection: feed it lines one at a time (in any grouping
 * -- a single frame may arrive split across several reads) and it hands back
 * a completed [SseFrame] exactly when a blank line terminates one.
 *
 * Per the SSE spec: `event:` and `data:` lines accumulate, lines starting
 * with `:` are comments/heartbeats and are ignored, and a blank line
 * dispatches the accumulated frame.
 */
class SseFrameParser {
    private var currentEvent: String? = null
    private val dataLines = mutableListOf<String>()

    /**
     * Feed one line (already stripped of its trailing newline). Returns the
     * completed frame if this line was the blank terminator of a non-empty
     * frame, otherwise null.
     */
    fun feedLine(line: String): SseFrame? {
        return when {
            line.startsWith(":") -> null // comment / heartbeat, e.g. ": ping"
            line.isEmpty() -> dispatch()
            line.startsWith("event:") -> {
                currentEvent = line.removePrefix("event:").trim()
                null
            }
            line.startsWith("data:") -> {
                dataLines.add(line.removePrefix("data:").trim())
                null
            }
            else -> null // unrecognized line type within a frame; ignore rather than crash
        }
    }

    private fun dispatch(): SseFrame? {
        if (currentEvent == null && dataLines.isEmpty()) return null
        val frame = SseFrame(currentEvent, dataLines.joinToString("\n"))
        currentEvent = null
        dataLines.clear()
        return frame
    }
}

@Serializable private data class ThinkingPayload(val active: Boolean)
@Serializable private data class TextDeltaPayload(val text: String)
@Serializable private data class ToolStartPayload(val id: String, val name: String, val detail: String? = null)
@Serializable private data class ToolEndPayload(val id: String, val name: String, val ok: Boolean)
@Serializable private data class SubagentStartPayload(val id: String, val name: String, val task: String? = null)
@Serializable private data class SubagentUpdatePayload(val id: String, val name: String, val delta: String = "")
@Serializable private data class SubagentEndPayload(val id: String, val name: String, val summary: String? = null)
@Serializable private data class MessageEndPayload(val text: String)
@Serializable private data class EngineChangedPayload(val engine: String, val model: String, val provider: String? = null)
@Serializable private data class ErrorPayload(val message: String)

// fix-ui-request-selection / Phase 4 (Android wire-up): the server emits one
// `ui_request` frame per pending engine prompt (select / confirm / input)
// with the exact body the chat layer needs to render the dialog AND echo the
// answer back to `POST /api/v1/chat/ui-response/:requestId`:
//   { requestId, token, method, title, message, options, deadlineMs, sessionId }
// `engine` is also present in the wire frame but is intentionally not modelled:
// Phase 4 only needs the fields the dialog responds with, and the existing
// `ChatEvent.UiRequestReceived(UiRequest)` shape is what the ViewModel
// already consumes.
@Serializable private data class UiRequestPayload(
    val requestId: String = "",
    val token: Long = 0L,
    val method: String = "",
    val title: String = "",
    val message: String = "",
    val options: List<String> = emptyList(),
    val deadlineMs: Long = 0L,
    val sessionId: String = "",
)

// fix-ui-request-selection / leftover: the server emits one `ui_request_resolved`
// frame per terminal request lifecycle (client answer 200 OR the 120s timer
// auto-approve), shaped `{ requestId, sessionId, engine, resolution, value }`.
// The app only consumes `requestId` -- the ViewModel matches it against the
// currently pending request so a stale resolve for a superseded id is a no-op.
@Serializable private data class UiRequestResolvedPayload(
    val requestId: String = "",
)

// Activity payloads (Change A). `server` and `detail` are omitted by the server
// when absent, so every optional field needs a default -- see encodeActivity in
// idupi-server/lib/activity.mjs.
@Serializable private data class ActivityStartPayload(
    val id: String, val streamId: String = "", val kind: String = "tool", val name: String = "",
    val server: String? = null, val detail: String? = null, val startedAt: Long = 0L,
)
@Serializable private data class ActivityUpdatePayload(
    val id: String, val streamId: String = "",
    val server: String? = null, val detail: String? = null, val lastUpdateAt: Long = 0L,
)
@Serializable private data class ActivityHeartbeatPayload(
    val id: String, val streamId: String = "",
    val elapsedMs: Long = 0L, val sinceLastUpdateMs: Long = 0L, val inflight: Boolean = true,
)
@Serializable private data class ActivityEndPayload(
    val id: String, val streamId: String = "", val ok: Boolean = false,
    val server: String? = null, val detail: String? = null,
)
@Serializable private data class ActivityFailurePayload(
    val id: String, val streamId: String = "", val errorClass: String? = null,
    val server: String? = null, val detail: String? = null,
)
@Serializable private data class ActivityTimeoutPayload(
    val id: String, val streamId: String = "",
    val server: String? = null, val detail: String? = null,
)

private const val SSE_TAG = "SseFrameParser"

/**
 * Maps one decoded [SseFrame] to a [ChatEvent], per the wire contract in
 * `idupi-server/chat-events.mjs`. Returns null when the frame carries no
 * corresponding UI event (an unknown event type, or a known-but-unmapped
 * type such as `engine_changed`) or when the payload fails to parse --
 * malformed JSON is logged and skipped, never fatal to the stream.
 */
fun parseSseEvent(json: Json, event: String?, data: String): ChatEvent? {
    return try {
        when (event) {
            "thinking" ->
                ChatEvent.Thinking(json.decodeFromString(ThinkingPayload.serializer(), data).active)

            "text_delta" ->
                ChatEvent.AssistantDelta(json.decodeFromString(TextDeltaPayload.serializer(), data).text)

            "tool_start" -> {
                val p = json.decodeFromString(ToolStartPayload.serializer(), data)
                ChatEvent.ToolStarted(toolName = p.name, message = p.detail ?: "", id = p.id)
            }

            "tool_end" -> {
                val p = json.decodeFromString(ToolEndPayload.serializer(), data)
                ChatEvent.ToolEnded(id = p.id, toolName = p.name, ok = p.ok)
            }

            "subagent_start" -> {
                val p = json.decodeFromString(SubagentStartPayload.serializer(), data)
                ChatEvent.SubagentStarted(id = p.id, name = p.name, task = p.task)
            }

            "subagent_update" -> {
                val p = json.decodeFromString(SubagentUpdatePayload.serializer(), data)
                ChatEvent.SubagentUpdated(id = p.id, name = p.name, delta = p.delta)
            }

            "subagent_end" -> {
                val p = json.decodeFromString(SubagentEndPayload.serializer(), data)
                ChatEvent.SubagentEnded(id = p.id, name = p.name, summary = p.summary)
            }

            "message_end" ->
                ChatEvent.MessageEnded(json.decodeFromString(MessageEndPayload.serializer(), data).text)

            "engine_changed" -> {
                // Validated for forward-compatibility (a malformed frame still gets
                // logged below) but there is no ChatEvent for it yet: nothing in the
                // UI currently reacts to an engine/model/provider switch mid-stream.
                json.decodeFromString(EngineChangedPayload.serializer(), data)
                null
            }

            "activity_start" -> {
                val p = json.decodeFromString(ActivityStartPayload.serializer(), data)
                ChatEvent.ActivityStarted(
                    id = p.id, streamId = p.streamId, kind = p.kind, name = p.name,
                    server = p.server, detail = p.detail, startedAt = p.startedAt,
                )
            }

            "activity_update" -> {
                val p = json.decodeFromString(ActivityUpdatePayload.serializer(), data)
                ChatEvent.ActivityUpdated(
                    id = p.id, streamId = p.streamId,
                    server = p.server, detail = p.detail, lastUpdateAt = p.lastUpdateAt,
                )
            }

            "activity_heartbeat" -> {
                val p = json.decodeFromString(ActivityHeartbeatPayload.serializer(), data)
                ChatEvent.ActivityHeartbeat(
                    id = p.id, streamId = p.streamId,
                    elapsedMs = p.elapsedMs, sinceLastUpdateMs = p.sinceLastUpdateMs, inflight = p.inflight,
                )
            }

            "activity_end" -> {
                val p = json.decodeFromString(ActivityEndPayload.serializer(), data)
                ChatEvent.ActivityEnded(
                    id = p.id, streamId = p.streamId, ok = p.ok, server = p.server, detail = p.detail,
                )
            }

            "activity_failure" -> {
                val p = json.decodeFromString(ActivityFailurePayload.serializer(), data)
                ChatEvent.ActivityFailed(
                    id = p.id, streamId = p.streamId, errorClass = p.errorClass,
                    server = p.server, detail = p.detail,
                )
            }

            "activity_timeout" -> {
                val p = json.decodeFromString(ActivityTimeoutPayload.serializer(), data)
                ChatEvent.ActivityTimedOut(
                    id = p.id, streamId = p.streamId, server = p.server, detail = p.detail,
                )
            }

            "ui_request" -> {
                val p = json.decodeFromString(UiRequestPayload.serializer(), data)
                val method = when (p.method.lowercase()) {
                    "select" -> UiRequestMethod.SELECT
                    "confirm" -> UiRequestMethod.CONFIRM
                    "input" -> UiRequestMethod.INPUT
                    else -> {
                        // Spec only emits select/confirm/input; an unknown value
                        // here means a new server-side method we don't know how
                        // to render. Surface as CONFIRM so the user sees a
                        // dialog instead of a silent drop, and log loudly so
                        // the build that introduces a new method is caught.
                        Log.w(SSE_TAG, "ui_request has unknown method='${p.method}'; falling back to CONFIRM")
                        UiRequestMethod.CONFIRM
                    }
                }
                ChatEvent.UiRequestReceived(
                    UiRequest(
                        id = p.requestId,
                        method = method,
                        title = p.title,
                        message = p.message,
                        options = p.options,
                        // Server sends `deadlineMs` as "ms until expiry".
                        // Convert to an absolute wall-clock instant so the
                        // countdown renders correctly even if the user leaves
                        // Chat and returns later, and so a future tick that
                        // reads `System.currentTimeMillis()` once per second
                        // never drifts across a clock skew.
                        deadlineAt = if (p.deadlineMs > 0) System.currentTimeMillis() + p.deadlineMs else 0L,
                        token = p.token,
                        sessionId = p.sessionId,
                    )
                )
            }

            "ui_request_resolved" -> {
                // Terminal acknowledgement from the registry: a 200 from
                // /api/v1/chat/ui-response/:requestId OR an auto-approve
                // emitted by the 120s timer. Surfaced as a ChatEvent so the
                // chat layer can close the card when the server resolves the
                // request without a client answer (expiry). The ViewModel
                // guards on the requestId so a stale resolve cannot close a
                // newer card. Old APKs MAY ignore this frame (spec §"UI
                // Request Surfacing") -- a client that does not handle it
                // simply keeps the card until its own countdown logic.
                val p = json.decodeFromString(UiRequestResolvedPayload.serializer(), data)
                ChatEvent.UiRequestResolved(requestId = p.requestId)
            }

            "error" ->
                ChatEvent.ErrorOccurred(json.decodeFromString(ErrorPayload.serializer(), data).message)

            else -> {
                Log.w(SSE_TAG, "Ignoring unknown SSE event type: $event")
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(SSE_TAG, "Malformed SSE data for event=$event: $data", e)
        null
    }
}
