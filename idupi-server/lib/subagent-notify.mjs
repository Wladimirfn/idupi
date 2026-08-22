/**
 * Reads the completion notice pi-subagents posts when an async subagent run
 * finishes.
 *
 * An async delegation answers the tool call immediately with a dispatch
 * receipt -- "The async run is detached and running in the background" -- so
 * the real answer cannot come from `tool_execution_end`. Pi delivers it later
 * as an `entry_appended` event carrying a `custom_message` entry whose
 * `customType` is `subagent-notify`:
 *
 *   Background task completed: **workflow**
 *
 *   Workflow completed with 1 child run(s). Return: {
 *     "key": "main", "ok": true, "agent": "gentle-ai-explore",
 *     "runId": "...", "output": "...", "artifactPaths": [...]
 *   } Trace: 2 event(s).
 *
 * That block is NOT reliably valid JSON. pi-subagents builds it with
 * `formatWorkflowValue(workflow.value).slice(0, 1_000)`, so any run whose
 * output or artifact list runs long arrives cut mid-string -- both notices
 * captured from a real session are truncated, so treating truncation as the
 * exception would fail on the common case. Parsing therefore tries JSON first
 * and falls back to scanning the fields out of the fragment.
 */

const RETURN_MARKER = "Return: ";

/** Reads one JSON string literal starting at the opening quote. */
function readJsonStringAt(text, quoteIndex) {
    let raw = "";
    let terminated = false;
    for (let i = quoteIndex + 1; i < text.length; i++) {
        const ch = text[i];
        if (ch === "\\") {
            // A lone trailing backslash is what truncation mid-escape leaves
            // behind; dropping it keeps the rest of the string usable.
            if (i + 1 >= text.length) break;
            raw += ch + text[i + 1];
            i++;
            continue;
        }
        if (ch === '"') {
            terminated = true;
            break;
        }
        raw += ch;
    }
    return { raw, terminated };
}

/** Turns a raw JSON string body back into text, without throwing on a fragment. */
function decodeJsonStringBody(raw) {
    try {
        return JSON.parse(`"${raw}"`);
    } catch {
        // Truncation can leave an escape the strict parser rejects. Decode the
        // escapes we know rather than surfacing nothing at all.
        return raw.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (whole, esc) => {
            if (esc[0] === "u") return String.fromCharCode(parseInt(esc.slice(1), 16));
            switch (esc) {
                case "n": return "\n";
                case "r": return "\r";
                case "t": return "\t";
                case "b": return "\b";
                case "f": return "\f";
                case '"': return '"';
                case "\\": return "\\";
                case "/": return "/";
                default: return whole;
            }
        });
    }
}

function scanStringField(fragment, field) {
    const key = `"${field}"`;
    let from = 0;
    while (true) {
        const at = fragment.indexOf(key, from);
        if (at === -1) return null;
        const rest = fragment.slice(at + key.length);
        const m = rest.match(/^\s*:\s*"/);
        if (!m) {
            from = at + key.length;
            continue;
        }
        const quoteIndex = at + key.length + m[0].length - 1;
        const { raw, terminated } = readJsonStringAt(fragment, quoteIndex);
        const decoded = decodeJsonStringBody(raw);
        // An unterminated string swallowed whatever pi-subagents appended after
        // the preview. That suffix is fixed and machine-written, so removing it
        // cannot eat a real answer.
        return terminated ? decoded : decoded.replace(/ Trace: \d+ event\(s\)\.\s*$/, "");
    }
}

/**
 * @returns {{agent: string|null, runId: string|null, output: string|null, ok: boolean}|null}
 *   null when the text is not a subagent completion notice.
 */
export function parseSubagentNotify(content) {
    if (typeof content !== "string" || !content) return null;
    const markerAt = content.indexOf(RETURN_MARKER);
    if (markerAt === -1) return null;
    const fragment = content.slice(markerAt + RETURN_MARKER.length);

    // The headline names the run that finished. For a workflow it literally
    // says "workflow", which is not a role a reader recognises, so the child's
    // own agent name (read below) wins whenever there is one.
    const headline = content.match(/Background task (completed|failed):\s*\*\*(.+?)\*\*/);
    const headlineAgent = headline && headline[2] !== "workflow" ? headline[2] : null;
    const headlineFailed = headline ? headline[1] === "failed" : false;

    // A fan-out returns one entry per child, keyed by role. The preview cap
    // almost always cuts inside the first child's output, so the rest are not
    // in this text at all -- the count is, and saying so beats presenting one
    // child's answer as if it were the whole result.
    const childMatch = content.match(/completed with (\d+) child run\(s\)/);
    const childCount = childMatch ? Number(childMatch[1]) : 1;

    let parsed = null;
    try {
        parsed = JSON.parse(fragment.trim());
    } catch {
        // Expected for any run long enough to hit the 1000-char preview cap.
    }
    if (parsed && typeof parsed === "object") {
        let first = Array.isArray(parsed) ? parsed[0] : parsed;
        if (first && typeof first === "object" && typeof first.agent !== "string") {
            // A fan-out is keyed by role -- {scout: {...}, researcher: {...}} --
            // so the object itself carries no agent: the children are its values.
            const nested = Object.values(first).find(
                (v) => v && typeof v === "object" && typeof v.agent === "string",
            );
            if (nested) first = nested;
        }
        if (first && typeof first === "object") {
            return {
                agent: typeof first.agent === "string" ? first.agent : headlineAgent,
                runId: typeof first.runId === "string" ? first.runId : null,
                output: typeof first.output === "string" ? first.output : null,
                ok: first.ok === false ? false : !headlineFailed,
                childCount,
            };
        }
    }

    const okMatch = fragment.match(/"ok"\s*:\s*(true|false)/);
    return {
        agent: scanStringField(fragment, "agent") || headlineAgent,
        runId: scanStringField(fragment, "runId"),
        output: scanStringField(fragment, "output"),
        ok: okMatch ? okMatch[1] === "true" : !headlineFailed,
        childCount,
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
