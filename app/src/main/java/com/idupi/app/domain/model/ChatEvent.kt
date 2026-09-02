package com.idupi.app.domain.model

sealed class ChatEvent {
    data class StatusChanged(val status: ServerStatus) : ChatEvent()
    data class AssistantDelta(val delta: String) : ChatEvent()
    // `id` correlates a tool_start with its matching tool_end frame. Defaulted to ""
    // so the one pre-existing call site (the synthetic "pi_cli" tool marker) keeps compiling.
    data class ToolStarted(val toolName: String, val message: String, val id: String = "") : ChatEvent()
    data class UiRequestReceived(val request: UiRequest) : ChatEvent()
    data class SupervisorAlertReceived(val alert: SupervisorAlert) : ChatEvent()
    data class MessageEnded(val finalMessage: String) : ChatEvent()
    data class ErrorOccurred(val error: String) : ChatEvent()
    data class Thinking(val active: Boolean) : ChatEvent()
    data class ToolEnded(val id: String, val toolName: String, val ok: Boolean) : ChatEvent()
    data class SubagentStarted(val id: String, val name: String, val task: String?) : ChatEvent()
    data class SubagentUpdated(val id: String, val name: String, val delta: String) : ChatEvent()
    data class SubagentEnded(val id: String, val name: String, val summary: String?) : ChatEvent()

    // -- Live CLI activity (Change A) ----------------------------------------
    //
    // One stable `id` correlates every frame of a single operation:
    // started -> (update | heartbeat)* -> exactly one of ended/failed/timedOut.
    // `streamId` identifies the invocation the operation belongs to.
    //
    // The server already filters activity frames to the subscriber's own
    // engine/project/session, so those three are not carried here: by the time a
    // frame is decoded it is known to belong to this chat.
    //
    // A heartbeat proves ONLY that the server and the operation are still open.
    // It is not evidence that the CLI subprocess is healthy, and must never be
    // presented as such.
    data class ActivityStarted(
        val id: String,
        val streamId: String,
        val kind: String,
        val name: String,
        val server: String? = null,
        val detail: String? = null,
        val startedAt: Long = 0L,
    ) : ChatEvent()

    data class ActivityUpdated(
        val id: String,
        val streamId: String,
        val server: String? = null,
        val detail: String? = null,
        val lastUpdateAt: Long = 0L,
    ) : ChatEvent()

    data class ActivityHeartbeat(
        val id: String,
        val streamId: String,
        val elapsedMs: Long,
        val sinceLastUpdateMs: Long,
        val inflight: Boolean,
    ) : ChatEvent()

    data class ActivityEnded(
        val id: String,
        val streamId: String,
        val ok: Boolean,
        val server: String? = null,
        val detail: String? = null,
    ) : ChatEvent()

    data class ActivityFailed(
        val id: String,
        val streamId: String,
        val errorClass: String? = null,
        val server: String? = null,
        val detail: String? = null,
    ) : ChatEvent()

    data class ActivityTimedOut(
        val id: String,
        val streamId: String,
        val server: String? = null,
        val detail: String? = null,
    ) : ChatEvent()
}
