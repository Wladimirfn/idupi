package com.idupi.app.domain.model

/**
 * One pending `ui_request` SSE frame, decoded for the chat layer.
 *
 * `deadlineAt`, `token`, and `sessionId` are NOT user-facing — they only exist
 * so the view-model can POST the answer back to the server with the exact
 * shape required by `POST /api/v1/chat/ui-response/:requestId`:
 *
 *   { "value": <select|confirm|input>, "token": "<token>", "sessionId": "<id>" }
 *
 * `deadlineAt` is the absolute wall-clock instant (ms) at which the request
 * auto-expires server-side. The server emits the relative `deadlineMs` and the
 * parser converts it; using an absolute timestamp keeps the countdown correct
 * even if the user leaves Chat and returns after a clock skew, and stops it
 * drifting if `System.currentTimeMillis()` jumps.
 *
 * `token` is the per-session monotonic number that rejects stale answers with
 * 409 — kept as a Long so JSON-number precision is preserved when the parser
 * reads it from SSE. Converted to String at the `sendUiResponse` boundary.
 */
data class UiRequest(
    val id: String,
    val method: UiRequestMethod,
    val title: String,
    val message: String,
    val options: List<String> = emptyList(),
    val deadlineAt: Long = 0L,
    val token: Long = 0L,
    val sessionId: String = "",
)

enum class UiRequestMethod {
    CONFIRM, SELECT, INPUT
}
