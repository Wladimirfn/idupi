package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Phone bubble turn wire models (phone-bubble T6).
 *
 * Shapes mirror `idupi-server/docs/phone.md` "Bubble agent turns". Every field
 * has a default so a document from a newer server decodes instead of failing
 * (the client uses `encodeDefaults = false`, so a missing field must be legal).
 */
@Serializable
data class PhoneAgentTurnStep(
    val at: String = "",
    val kind: String = "",
    val summary: String = "",
)

@Serializable
data class PhoneAgentTurn(
    val id: String = "",
    val text: String = "",
    val status: String = PhoneAgentTurnStatus.QUEUED,
    val answer: String = "",
    val steps: List<PhoneAgentTurnStep> = emptyList(),
    val error: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val agent: String = "",
)

/** `POST /api/v1/phone/agent/turns` and `GET .../turns/{id}` wrap the turn. */
@Serializable
data class PhoneAgentTurnEnvelope(
    val turn: PhoneAgentTurn = PhoneAgentTurn(),
)

/** `GET /api/v1/phone/agent/turns?limit=N`, newest first. */
@Serializable
data class PhoneAgentTurnsEnvelope(
    val turns: List<PhoneAgentTurn> = emptyList(),
)

/** Turn lifecycle values the server uses. */
object PhoneAgentTurnStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Queued or running: the bubble keeps long-polling. */
    fun isActive(status: String): Boolean = status == QUEUED || status == RUNNING

    /** Done, failed or cancelled: nothing will change without a new request. */
    fun isTerminal(status: String): Boolean =
        status == DONE || status == FAILED || status == CANCELLED
}

/**
 * Typed answer to the POST that starts a turn. The client maps the HTTP status
 * and error code here so the bubble never branches on exceptions.
 *
 * Copy is neutral Latin American Spanish and lives with the type, so the
 * controller and the tests agree on the exact strings the owner reads.
 */
sealed interface PhoneAgentTurnStartResult {

    /** 202: the turn exists and is queued on the PC. */
    data class Started(val turn: PhoneAgentTurn) : PhoneAgentTurnStartResult

    /** 400 / 413: the text was empty, too long or malformed. */
    data class Invalid(val message: String = MESSAGE_INVALID) : PhoneAgentTurnStartResult

    /** 503 `AGENT_UNAVAILABLE`: the configured agent cannot run phone turns. */
    data class Unavailable(
        val message: String = MESSAGE_UNAVAILABLE,
    ) : PhoneAgentTurnStartResult

    /** Anything else: unreachable PC, bad token, unexpected status. */
    data class Failure(val message: String = MESSAGE_CONNECTION) : PhoneAgentTurnStartResult

    companion object {
        const val MESSAGE_INVALID = "Escribe un pedido de hasta 2000 caracteres."
        const val MESSAGE_UNAVAILABLE = "El agente del teléfono no está disponible en la PC."
        const val MESSAGE_CONNECTION = "No se pudo enviar el pedido. Revisa la conexión con la PC."
    }
}
