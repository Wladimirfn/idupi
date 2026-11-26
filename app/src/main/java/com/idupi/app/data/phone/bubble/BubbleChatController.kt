package com.idupi.app.data.phone.bubble

import com.idupi.app.domain.model.PhoneAgentTurn
import com.idupi.app.domain.model.PhoneAgentTurnStartResult
import com.idupi.app.domain.model.PhoneAgentTurnStatus
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a bubble line is: the owner's request, a server step, or the answer. */
enum class BubbleMessageRole { OWNER, STEP, ANSWER, ERROR }

data class BubbleChatMessage(
    val role: BubbleMessageRole,
    val text: String,
)

/** Where the active request is in its lifecycle. */
enum class BubbleChatPhase { IDLE, SENDING, RUNNING, DONE, FAILED }

/**
 * Everything the expanded panel renders. Messages accumulate for the life of
 * the bubble, so the owner can still read the previous answer after sending a
 * new request; [phase] describes only the active turn.
 */
data class BubbleChatState(
    val messages: List<BubbleChatMessage> = emptyList(),
    val phase: BubbleChatPhase = BubbleChatPhase.IDLE,
    val canRetry: Boolean = false,
    val lastRequest: String = "",
) {
    val isBusy: Boolean
        get() = phase == BubbleChatPhase.SENDING || phase == BubbleChatPhase.RUNNING

    /** "Pensando…" while the PC works, null otherwise. */
    fun statusLine(): String? =
        if (isBusy) BubbleChatController.MESSAGE_THINKING else null
}

/**
 * Bubble chat reducer (phone-bubble T6): one owner request becomes a POST,
 * then a long poll of that turn until a terminal status. It is deliberately
 * Android-free so the whole flow is unit-testable against the fake client.
 *
 * [scope] is injected (production uses an IO scope, tests a TestScope), which
 * keeps the poll retry on virtual time. [close] cancels the poll when the panel
 * is minimized; [resume] follows the same turn again when it reopens, so a
 * minimized bubble does not lose an answer that is still running on the PC.
 */
class BubbleChatController(
    private val clientSource: IduPiClientSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollWaitSeconds: Int = DEFAULT_POLL_WAIT_SECONDS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
) {

    private val _state = MutableStateFlow(BubbleChatState())
    val state: StateFlow<BubbleChatState> = _state.asStateFlow()

    private var turnJob: Job? = null

    /** Last turn observed, so [resume] can follow it after [close]. */
    @Volatile
    private var lastTurn: PhoneAgentTurn? = null

    private var activeTurnId: String? = null
    private var renderedSteps = 0
    private var answerRendered = false
    private var errorRendered = false

    /** Sends one request. Blank text and a busy turn are no-ops. */
    fun send(text: String) {
        val request = text.trim()
        if (request.isEmpty() || _state.value.isBusy) return
        start(request)
    }

    /** Re-sends the last request after a failure. */
    fun retry() {
        val request = _state.value.lastRequest
        if (request.isNotBlank() && !_state.value.isBusy) start(request)
    }

    /**
     * Cancels the long poll. The server turn keeps running; [resume] reattaches
     * to it when the owner opens the panel again.
     */
    fun close() {
        turnJob?.cancel()
        turnJob = null
        if (_state.value.isBusy) _state.update { it.copy(phase = BubbleChatPhase.IDLE) }
    }

    /** Reattaches to the last turn when the panel reopens mid-request. */
    fun resume() {
        val turn = lastTurn ?: return
        if (!PhoneAgentTurnStatus.isActive(turn.status)) return
        if (turnJob?.isActive == true) return
        turnJob = scope.launch { follow(turn) }
    }

    private fun start(text: String) {
        turnJob?.cancel()
        activeTurnId = null
        renderedSteps = 0
        answerRendered = false
        errorRendered = false
        _state.update {
            it.copy(
                messages = it.messages + BubbleChatMessage(BubbleMessageRole.OWNER, text),
                phase = BubbleChatPhase.SENDING,
                canRetry = false,
                lastRequest = text,
            )
        }
        turnJob = scope.launch {
            val result = try {
                clientSource.client.startPhoneAgentTurn(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PhoneAgentTurnStartResult.Failure()
            }
            when (result) {
                is PhoneAgentTurnStartResult.Started -> follow(result.turn)
                is PhoneAgentTurnStartResult.Invalid -> fail(result.message)
                is PhoneAgentTurnStartResult.Unavailable -> fail(result.message)
                is PhoneAgentTurnStartResult.Failure -> fail(result.message)
            }
        }
    }

    private suspend fun follow(turn: PhoneAgentTurn) {
        apply(turn)
        var current = turn
        var failures = 0
        while (PhoneAgentTurnStatus.isActive(current.status)) {
            val next = try {
                clientSource.client.getPhoneAgentTurn(current.id, pollWaitSeconds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += 1
                if (failures >= MAX_POLL_FAILURES) {
                    fail(MESSAGE_POLL_FAILED)
                    return
                }
                delay(retryDelayMillis)
                continue
            }
            failures = 0
            apply(next)
            current = next
        }
    }

    /**
     * Folds one server snapshot into the message list. Steps are appended by
     * count (the server only ever appends), the answer once, and a failure or
     * cancellation once. The `answer` step is skipped: the answer itself
     * follows it and saying "Respuesta lista" right before it is noise.
     */
    private fun apply(turn: PhoneAgentTurn) {
        lastTurn = turn
        if (turn.id != activeTurnId) {
            activeTurnId = turn.id
            renderedSteps = 0
            answerRendered = false
            errorRendered = false
        }
        val newSteps = turn.steps.drop(renderedSteps)
            .filterNot { it.kind == STEP_KIND_ANSWER }
        renderedSteps = turn.steps.size

        val terminalMessage = when {
            errorRendered -> null
            turn.status == PhoneAgentTurnStatus.FAILED -> MESSAGE_FAILED
            turn.status == PhoneAgentTurnStatus.CANCELLED -> MESSAGE_CANCELLED
            !PhoneAgentTurnStatus.isActive(turn.status) && turn.status != PhoneAgentTurnStatus.DONE ->
                MESSAGE_FAILED
            else -> null
        }
        if (terminalMessage != null) errorRendered = true

        val answer = turn.answer.trim().takeIf {
            it.isNotEmpty() && turn.status == PhoneAgentTurnStatus.DONE && !answerRendered
        }
        if (answer != null) answerRendered = true

        val phase = when {
            PhoneAgentTurnStatus.isActive(turn.status) -> BubbleChatPhase.RUNNING
            turn.status == PhoneAgentTurnStatus.DONE -> BubbleChatPhase.DONE
            else -> BubbleChatPhase.FAILED
        }

        _state.update { state ->
            val messages = buildList {
                addAll(state.messages)
                newSteps.forEach { add(BubbleChatMessage(BubbleMessageRole.STEP, it.summary)) }
                terminalMessage?.let { add(BubbleChatMessage(BubbleMessageRole.ERROR, it)) }
                answer?.let { add(BubbleChatMessage(BubbleMessageRole.ANSWER, it)) }
            }
            state.copy(
                messages = messages,
                phase = phase,
                canRetry = phase == BubbleChatPhase.FAILED,
            )
        }
    }

    private fun fail(message: String) {
        errorRendered = true
        _state.update {
            it.copy(
                messages = it.messages + BubbleChatMessage(BubbleMessageRole.ERROR, message),
                phase = BubbleChatPhase.FAILED,
                canRetry = true,
            )
        }
    }

    companion object {
        const val MESSAGE_THINKING = "Pensando…"
        const val MESSAGE_FAILED = "El agente no pudo completar el pedido."
        const val MESSAGE_CANCELLED = "El pedido se canceló."
        const val MESSAGE_POLL_FAILED =
            "No se pudo seguir el pedido. Revisa la conexión con la PC."

        /** The server's final step duplicates the answer; it is not shown. */
        private const val STEP_KIND_ANSWER = "answer"

        const val DEFAULT_POLL_WAIT_SECONDS = 25
        const val DEFAULT_RETRY_DELAY_MS = 2_000L

        /** Consecutive long-poll failures tolerated before the turn fails. */
        const val MAX_POLL_FAILURES = 3
    }
}
