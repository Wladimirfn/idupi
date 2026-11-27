package com.idupi.app.data.phone.control

import android.util.Log
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * Outcome of one command execution: what the phone posts back to the server.
 * A failure always carries a short, payload-free reason.
 */
data class CommandOutcome(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
) {
    companion object {
        fun success(result: JsonElement? = null) = CommandOutcome(ok = true, result = result)
        fun failure(error: String) = CommandOutcome(ok = false, error = error)
    }
}

/** What the control loop must do after one poll. */
sealed interface PollStep {
    /** Normal poll: no command or a handled command; continue immediately. */
    object Continue : PollStep

    /** Transient failure: wait [delayMillis] before polling again. */
    data class Retry(val delayMillis: Long) : PollStep

    /** Control is off or no PC is configured: leave the loop cleanly. */
    object Stop : PollStep
}

/**
 * Exponential backoff for the long-poll loop: 5 s, 10 s, 20 s, then 30 s
 * forever, reset by any successful poll.
 */
class PollBackoff(
    private val initialMillis: Long = INITIAL_MILLIS,
    private val maxMillis: Long = MAX_MILLIS,
) {
    private var nextMillis: Long = initialMillis

    fun delayMillisForFailure(): Long {
        val current = nextMillis
        nextMillis = (nextMillis * 2).coerceAtMost(maxMillis)
        return current
    }

    fun reset() {
        nextMillis = initialMillis
    }

    companion object {
        const val INITIAL_MILLIS = 5_000L
        const val MAX_MILLIS = 30_000L
    }
}

/**
 * One iteration of the phone control loop (phone-agent T3), extracted from the
 * Android service so the decision logic is unit-tested with virtual time.
 *
 * A poll:
 * 1. stops when the master switch is off or no connection profile exists;
 * 2. long-polls `GET /phone/commands/next` (the server itself holds the request
 *    for [waitSeconds]);
 * 3. validates the command; an invalid one is reported as a failure when its id
 *    was readable instead of being dropped;
 * 4. skips a command whose `expiresAt` already passed and reports the failure
 *    so the PC is not left waiting;
 * 5. executes and posts the result. Typed payloads never reach the log.
 *
 * Any transport failure (poll or result post) throws out of [pollOnce] as a
 * [PollStep.Retry]; the caller delays and polls again. Nothing else escapes.
 */
class PhoneControlPoller(
    private val clientSource: IduPiClientSource,
    private val isEnabled: suspend () -> Boolean,
    private val canPoll: () -> Boolean,
    private val execute: suspend (PhoneCommand) -> CommandOutcome,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    private val backoff: PollBackoff = PollBackoff(),
    private val log: (PhoneCommandLogEntry) -> Unit = PhoneCommandLog::record,
    private val onTransportFailure: (Throwable) -> Unit = {},
    private val onTransportSuccess: () -> Unit = {},
) {

    suspend fun pollOnce(): PollStep {
        if (!isEnabled() || !canPoll()) return PollStep.Stop

        val raw = try {
            clientSource.client.getNextPhoneCommand(waitSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "phone command poll failed: ${error.javaClass.simpleName}")
            onTransportFailure(error)
            return PollStep.Retry(backoff.delayMillisForFailure())
        }

        backoff.reset()
        onTransportSuccess()
        if (raw == null) return PollStep.Continue

        return try {
            handle(raw)
            PollStep.Continue
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "phone command handling failed: ${error.javaClass.simpleName}")
            onTransportFailure(error)
            PollStep.Retry(backoff.delayMillisForFailure())
        }
    }

    private suspend fun handle(raw: String) {
        when (val parsed = CommandParser.parse(raw)) {
            is CommandParseResult.Invalid -> {
                parsed.commandId?.let { id ->
                    clientSource.client.postPhoneCommandResult(
                        commandId = id,
                        ok = false,
                        result = null,
                        error = parsed.error,
                    )
                }
                log(
                    PhoneCommandLogEntry(
                        atMillis = nowMillis(),
                        type = parsed.type ?: "?",
                        target = "comando inválido",
                        ok = false,
                        error = parsed.error,
                    )
                )
            }
            is CommandParseResult.Valid -> {
                val command = parsed.command
                if (command.isExpired(nowMillis())) {
                    clientSource.client.postPhoneCommandResult(
                        commandId = command.id,
                        ok = false,
                        result = null,
                        error = COMMAND_EXPIRED,
                    )
                    log(PhoneCommandSummaries.entry(command, false, COMMAND_EXPIRED, nowMillis()))
                    return
                }

                val outcome = try {
                    execute(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "phone command execution failed: ${error.javaClass.simpleName}")
                    CommandOutcome.failure(COMMAND_INTERNAL_ERROR)
                }

                clientSource.client.postPhoneCommandResult(
                    commandId = command.id,
                    ok = outcome.ok,
                    result = outcome.result,
                    error = outcome.error,
                )
                log(PhoneCommandSummaries.entry(command, outcome.ok, outcome.error, nowMillis()))
            }
        }
    }

    companion object {
        private const val TAG = "PhoneControlPoller"

        const val DEFAULT_WAIT_SECONDS = 25
        const val COMMAND_EXPIRED = "comando expirado"
        const val COMMAND_INTERNAL_ERROR = "error interno"
    }
}
