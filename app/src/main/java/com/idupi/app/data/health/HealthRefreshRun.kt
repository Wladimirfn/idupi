package com.idupi.app.data.health

import com.idupi.app.domain.model.HealthRefreshJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Result of one "Actualizar ahora" / "Leer Huawei Health ahora" run.
 *
 * [failed] means a start or poll request threw (PC unreachable); [timedOut]
 * means the job never reached `done`/`failed` inside the poll budget. Both are
 * recoverable states the UI offers to retry, never an invented success.
 */
data class HealthRefreshOutcome(
    val job: HealthRefreshJob? = null,
    val failed: Boolean = false,
    val timedOut: Boolean = false,
) {
    val status: String? get() = job?.status
    val succeeded: Boolean get() = job?.status == "done"
}

/**
 * Shared refresh loop of the Salud screen and Ajustes > SALUD (phone-health
 * T3): ask the PC for a hidden-display read, then poll the job until it settles
 * or the budget runs out. Pure except for the injected seams, so both view
 * models and the JVM tests use the same behavior.
 */
suspend fun runHealthRefresh(
    start: suspend () -> HealthRefreshJob,
    poll: suspend (String) -> HealthRefreshJob,
    pollDelayMillis: Long = 1_000,
    maxPollAttempts: Int = 60,
    delayFn: suspend (Long) -> Unit = { delay(it) },
): HealthRefreshOutcome {
    val started = try {
        start()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return HealthRefreshOutcome(failed = true)
    }

    if (started.id.isBlank()) {
        // No id means no way to follow the job; treat it as a failed call.
        return HealthRefreshOutcome(job = started, failed = true)
    }
    if (started.status == "done" || started.status == "failed") {
        return HealthRefreshOutcome(job = started)
    }

    var last = started
    repeat(maxPollAttempts.coerceAtLeast(0)) {
        delayFn(pollDelayMillis)
        last = try {
            poll(started.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HealthRefreshOutcome(job = last, failed = true)
        }
        if (last.status == "done" || last.status == "failed") {
            return HealthRefreshOutcome(job = last)
        }
    }
    return HealthRefreshOutcome(job = last, timedOut = true)
}
