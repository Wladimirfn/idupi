package com.idupi.app.domain.model

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    // Correlates a TOOL/SUBAGENT message with the tool_start/tool_end (or
    // subagent_start/subagent_end) pair it represents, so the end event can
    // update this same message in place instead of appending a new one.
    val toolId: String? = null,
    // null = still running, true = finished ok, false = finished with a failure.
    val toolOk: Boolean? = null,
    val uiRequest: UiRequest? = null,
    val isStreaming: Boolean = false
)

enum class MessageSender {
    USER, PI, SYSTEM, TOOL, SUBAGENT, ERROR
}
