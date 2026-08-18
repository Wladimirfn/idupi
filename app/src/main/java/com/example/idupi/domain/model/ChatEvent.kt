package com.example.idupi.domain.model

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
}
