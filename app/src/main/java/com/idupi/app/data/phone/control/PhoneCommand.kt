package com.idupi.app.data.phone.control

import java.time.Instant

/**
 * One validated command delivered by `GET /api/v1/phone/commands/next`
 * (phone-agent T3). Shapes mirror `idupi-server/docs/phone.md` and the server's
 * `normalizeCommandArgs`; [CommandParser] is the only place that creates these.
 *
 * @property id `phc-…` identity used to post the result.
 * @property expiresAt ISO-8601 deadline from the server, or null when the wire
 *   omitted it.
 */
sealed interface PhoneCommand {
    val id: String
    val expiresAt: String?

    data class Screen(override val id: String, override val expiresAt: String?) : PhoneCommand

    data class Tap(
        override val id: String,
        override val expiresAt: String?,
        val target: NodeTarget,
        val approval: CommandApproval? = null,
    ) : PhoneCommand

    data class LongPress(
        override val id: String,
        override val expiresAt: String?,
        val target: NodeTarget,
        val approval: CommandApproval? = null,
    ) : PhoneCommand

    data class TypeText(
        override val id: String,
        override val expiresAt: String?,
        val text: String,
        val submit: Boolean,
        val approval: CommandApproval? = null,
    ) : PhoneCommand

    data class Scroll(
        override val id: String,
        override val expiresAt: String?,
        val direction: ScrollDirection,
    ) : PhoneCommand

    data class Global(
        override val id: String,
        override val expiresAt: String?,
        val action: GlobalAction,
    ) : PhoneCommand

    data class OpenApp(
        override val id: String,
        override val expiresAt: String?,
        val app: String,
    ) : PhoneCommand

    data class Wait(
        override val id: String,
        override val expiresAt: String?,
        val millis: Int,
    ) : PhoneCommand

    data class ReportStatus(
        override val id: String,
        override val expiresAt: String?,
    ) : PhoneCommand
}

/**
 * Approval carried by a command the server requeued after the owner approved
 * it. The fields are hints only: the executor trusts the LOCAL approval ledger
 * for [approvalId], never this wire data (a PC process with the token could
 * forge it).
 *
 * @property approvedTarget node text the approved action is bound to; null for
 *   `type_submit` and for sensitive-app taps.
 */
data class CommandApproval(
    val approvalId: String,
    val approvedTarget: String? = null,
    val reason: String = "",
)

/** Target of tap / long_press: the server already enforced exactly-one-of. */
sealed interface NodeTarget {
    data class Text(val text: String) : NodeTarget
    data class Id(val id: String) : NodeTarget
    data class Point(val x: Int, val y: Int) : NodeTarget
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

/**
 * True when the server deadline already passed, so the command must not run.
 *
 * An absent or unparseable deadline is not treated as expired: the server is
 * the queue and a malformed optional field must not silently drop a command.
 * The poller reports an expired command as a failure instead of executing it,
 * because the PC is waiting for a terminal state.
 */
fun PhoneCommand.isExpired(nowMillis: Long): Boolean {
    val raw = expiresAt ?: return false
    val expiry = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?: return false
    return expiry <= nowMillis
}
