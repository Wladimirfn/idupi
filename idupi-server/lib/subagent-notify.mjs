/**
 * Reads the completion notice pi-subagents posts when an async subagent run
 * finishes.
 *
 * An async delegation answers the tool call immediately with a dispatch
 * receipt -- "The async run is detached and running in the background" -- so
 * the real answer cannot come from `tool_execution_end`. Pi delivers it later
 * as a CUSTOM message: sendCustomMessage() appends the entry and emits
 * message_start/message_end with role "custom" and customType
 * "subagent-notify" (dist/core/agent-session.js:1096). It is NOT an
 * `entry_appended` event -- that one is emitted only by appendEntry(), a
 * different API -- and keying on it left the delegation card open forever.
 *
 *   Background task completed: **workflow**
 *
 *   Workflow completed with 2 child run(s). Return: {
 *     "scout": "Total .kt files ...",
 *     "researcher": { "agent": "...", "runId": "...", "output": "..." }
 *   } Trace: 4 event(s).
 *
 * Two things about that block make a naive reader fail, and both were observed
 * in real runs rather than assumed:
 *
 *  - It is usually NOT valid JSON. pi-subagents builds it with
 *    `formatWorkflowValue(workflow.value).slice(0, 1_000)`, so it arrives cut
 *    mid-value far more often than not.
 *  - Its shape is not fixed. `Return:` is whatever the workflowScript returned,
 *    and the model writes that script: one run gave objects with `output`, the
 *    next gave plain strings under the same prompt.
 *
 * So the children are read structurally (see subagent-children.mjs), and a
 * notice always parses into something actionable: a caller must be able to
 * close a card on "this finished" even when the text cannot be read.
 */

import { extractChildren } from "./subagent-children.mjs";

const RETURN_MARKER = "Return: ";

/**
 * @returns {{agent: string|null, runId: string|null, output: string|null,
 *            ok: boolean, childCount: number, children: Array}|null}
 *   null only when the text is not a completion notice at all.
 */
export function parseSubagentNotify(content) {
    if (typeof content !== "string" || !content) return null;

    const headline = content.match(/Background task (completed|failed):\s*\*\*(.+?)\*\*/);
    const markerAt = content.indexOf(RETURN_MARKER);
    // A notice with no Return block still reports that work finished, which is
    // enough to close a card. Only text that is neither is rejected.
    if (markerAt === -1 && !headline) return null;

    const fragment = markerAt === -1 ? "" : content.slice(markerAt + RETURN_MARKER.length);

    // The headline names the run that finished. For a fan-out it literally says
    // "workflow", which is not a role a reader recognises, so a child's own
    // name wins whenever there is one.
    const headlineAgent = headline && headline[2] !== "workflow" ? headline[2] : null;
    const headlineFailed = headline ? headline[1] === "failed" : false;

    const declared = content.match(/completed with (\d+) child run\(s\)/);
    const children = extractChildren(fragment);
    const first = children.find((c) => c.output) || children[0] || null;
    const okMatch = fragment.match(/"ok"\s*:\s*(true|false)/);

    return {
        agent: (first && first.agent) || headlineAgent,
        runId: (first && first.runId) || null,
        output: (first && first.output) || null,
        ok: headlineFailed ? false : (okMatch ? okMatch[1] === "true" : true),
        // The declared count is authoritative: the preview can cut before the
        // later children appear, so what was read is a floor, not the total.
        childCount: Math.max(declared ? Number(declared[1]) : 1, children.length),
        children,
    };
}

/**
 * Picks which open delegation card a completion notice closes.
 *
 * pi-subagents reports the child's own run id while the dispatch receipt
 * carried the parent workflow's id, so the two never match directly. The role
 * name is the field both sides share; when it identifies no card (a fan-out of
 * same-role children) the longest-waiting card is the only defensible choice.
 * With no open card at all -- a resumed session, a restarted server -- the
 * caller is told to open a fresh one so the answer is shown rather than lost.
 *
 * @param {Map<string,string>} pending insertion-ordered card id -> agent name
 * @returns {{id: string, name: string, isNew: boolean}}
 */
export function resolveNoticeCard(pending, notice) {
    if (notice.agent) {
        for (const [cardId, name] of pending) {
            if (name === notice.agent) return { id: cardId, name, isNew: false };
        }
    }
    if (pending.size) {
        const cardId = pending.keys().next().value;
        return { id: cardId, name: pending.get(cardId), isNew: false };
    }
    return {
        id: notice.runId || `subagent-${Date.now()}`,
        name: notice.agent || "subagent",
        isNew: true,
    };
}

/**
 * What the card shows for a finished delegation: every child's answer that
 * could be read, labelled, plus an honest line about the ones that could not.
 */
export function describeNoticeResult(notice) {
    const withText = notice.children.filter((c) => c.output);
    const missing = notice.childCount - withText.length;

    if (!withText.length) {
        return notice.childCount > 1
            ? `${notice.childCount} subagentes terminaron. Pi no incluyó sus respuestas en el aviso.`
            : "El subagente terminó, pero Pi no incluyó su respuesta en el aviso.";
    }

    const parts = withText.map((c) => (
        notice.childCount > 1 ? `**${c.agent}**: ${c.output.trim()}` : c.output.trim()
    ));
    if (missing > 0) {
        parts.push(`(${missing} más terminaron; Pi cortó el aviso antes de incluirlas.)`);
    }
    return parts.join("\n\n");
}
