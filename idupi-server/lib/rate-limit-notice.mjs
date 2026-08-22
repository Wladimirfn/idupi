/**
 * Turns Claude's `rate_limit_event` into a notice -- or into nothing.
 *
 * The handler used to announce "Límite de sesión de Claude alcanzado" for EVERY
 * one of these events, and worse, it assigned that sentence over `fullOutput`:
 * the model's actual answer was replaced by a warning about a limit that had not
 * been reached. Claude emits this event as ordinary status while a turn is
 * running fine, so the app was told the quota was gone in the middle of a
 * working conversation.
 *
 * The payload shape is not documented here from observation -- no captured
 * event was available -- so it is read defensively rather than guessed at. A
 * status that is present and says anything other than "allowed" is treated as a
 * real limit; a payload with no status at all is NOT turned into a warning,
 * because inventing certainty about a shape never seen is what produced the
 * false alarm in the first place. When the limit is genuinely exhausted Claude
 * also fails the turn, and that surfaces on its own.
 */

/**
 * @param {object|null|undefined} info the event's `rate_limit_info`
 * @returns {string|null} the notice to show the user, or null to stay quiet
 */
export function describeRateLimit(info) {
    const status = info && typeof info.status === "string" ? info.status : null;
    if (status === null) return null;          // unknown shape: do not cry wolf
    if (status === "allowed") return null;     // the normal, running-fine case

    const resetsAt = info.resetsAt;
    const resetLabel = typeof resetsAt === "number" && Number.isFinite(resetsAt)
        ? new Date(resetsAt * 1000).toLocaleTimeString()
        : "próximamente";
    return `⚠️ Límite de sesión de Claude alcanzado (${status}). Se restablece a las ${resetLabel}.`;
}
