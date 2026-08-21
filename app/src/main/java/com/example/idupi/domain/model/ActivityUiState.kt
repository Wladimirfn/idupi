package com.example.idupi.domain.model

/**
 * A live CLI/MCP operation is either still open or has reached exactly one
 * terminal state. `FAILED` and `TIMED_OUT` are kept apart on purpose: a tool
 * that reported an error and a tool the server had to kill are different
 * events, and collapsing them hides which one happened.
 */
enum class ActivityStatus { RUNNING, OK, FAILED, TIMED_OUT }

/**
 * Gap after which an open operation is shown as stale rather than recent.
 * Mirrors the server's 15s heartbeat interval: at 20s at least one heartbeat
 * has been missed or the provider has simply gone quiet.
 */
const val ACTIVITY_STALE_THRESHOLD_MS: Long = 20_000

/**
 * One live operation as the chat renders it, correlated by the server's stable
 * [id] across start, update, heartbeat and its single terminal frame.
 *
 * [isStale] describes only how long the provider has been silent. Neither it
 * nor a heartbeat says anything about whether the CLI subprocess is healthy --
 * the server can only observe that the operation has not closed yet.
 */
data class ActivityUiState(
    val id: String,
    val streamId: String,
    val kind: String,
    val name: String,
    val server: String? = null,
    val detail: String? = null,
    val elapsedMs: Long = 0L,
    val sinceLastUpdateMs: Long = 0L,
    val status: ActivityStatus = ActivityStatus.RUNNING,
) {
    val isRunning: Boolean get() = status == ActivityStatus.RUNNING

    val isStale: Boolean
        get() = isRunning && sinceLastUpdateMs >= ACTIVITY_STALE_THRESHOLD_MS

    /** "playwright · browser_navigate" when the server is known, else just the name. */
    val label: String get() = server?.let { "$it · $name" } ?: name
}

/**
 * What the live bar shows: operations still open, in the order they started.
 *
 * Finished operations are dropped on purpose. Every tool start and end already
 * lands in the chat log as its own message with its outcome, so keeping a
 * terminal row here duplicates that record -- and a fan-out of a dozen children
 * turned it into a wall of identical "listo" lines that hid the one operation
 * still running, which is the only thing this bar exists to show.
 */
fun liveActivities(activities: List<ActivityUiState>): List<ActivityUiState> =
    activities.filter { it.isRunning }

/**
 * Subagents for the session strip: still-running first, most recent first
 * inside each group.
 *
 * Arrival order buried the newest delegation at the far end of a horizontal
 * strip, which is exactly the one the reader wants. [sortedWith] is stable, so
 * subagents sharing a start timestamp keep their relative order instead of
 * shuffling between recompositions.
 */
fun orderedSubagents(subagents: List<SubagentLiveState>): List<SubagentLiveState> =
    subagents.sortedWith(
        compareByDescending<SubagentLiveState> { it.isRunning }
            .thenByDescending { it.startTime },
    )
