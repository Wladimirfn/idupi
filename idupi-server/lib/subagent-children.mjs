/**
 * Reads the per-child results out of a workflow completion notice.
 *
 * There is no fixed shape here, and that is the point. `Return:` carries
 * whatever the workflowScript returned, and the model writes that script. Two
 * runs of the same prompt produced:
 *
 *   {"scout": {"key":"scout","ok":true,"agent":"...","runId":"...","output":"..."}}
 *   {"scout": "Total .kt files under app/src/main/: **60** ..."}
 *
 * A reader keyed to one of those breaks on the other -- and it did, leaving the
 * delegation card open because no `output` field was found. So the children are
 * read structurally: a string value IS the child's answer, an object value has
 * it under `output`. Anything else yields a child with no text rather than
 * nothing at all, because knowing a child finished still matters when its text
 * cannot be read.
 *
 * The text arrives truncated more often than not (pi-subagents caps the preview
 * at 1000 chars), so every step tolerates running out mid-value.
 */

/** Reads one JSON string literal starting at its opening quote. */
function readString(text, quoteIndex) {
    let raw = "";
    let end = text.length;
    for (let i = quoteIndex + 1; i < text.length; i++) {
        const ch = text[i];
        if (ch === "\\") {
            if (i + 1 >= text.length) break;
            raw += ch + text[i + 1];
            i++;
            continue;
        }
        if (ch === '"') { end = i + 1; break; }
        raw += ch;
    }
    return { raw, end };
}

function decode(raw) {
    try {
        return JSON.parse(`"${raw}"`);
    } catch {
        return raw.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (whole, esc) => {
            if (esc[0] === "u") return String.fromCharCode(parseInt(esc.slice(1), 16));
            const simple = { n: "\n", r: "\r", t: "\t", b: "\b", f: "\f", '"': '"', "\\": "\\", "/": "/" };
            return esc in simple ? simple[esc] : whole;
        });
    }
}

/** Strips the fixed suffix pi-subagents appends after the preview. */
function stripPreviewTail(text) {
    return text.replace(/ (Emitted: [\s\S]*?)? ?Trace: \d+ event\(s\)\.\s*$/, "");
}

function childFromValue(key, value) {
    if (typeof value === "string") {
        return { key, agent: key, runId: null, output: value, ok: true };
    }
    if (value && typeof value === "object") {
        return {
            key,
            agent: typeof value.agent === "string" ? value.agent : key,
            runId: typeof value.runId === "string" ? value.runId : null,
            output: typeof value.output === "string" ? value.output : null,
            ok: value.ok !== false,
        };
    }
    return { key, agent: key, runId: null, output: null, ok: true };
}

/**
 * Walks the fragment collecting `"key": value` pairs at the top level, which is
 * what survives when JSON.parse cannot: truncation always lands inside the last
 * value, leaving every earlier child intact and readable.
 */
function scanChildren(fragment) {
    const children = [];
    const start = fragment.indexOf("{");
    if (start === -1) return children;

    let i = start + 1;
    while (i < fragment.length) {
        while (i < fragment.length && /[\s,]/.test(fragment[i])) i++;
        if (fragment[i] !== '"') break;

        const keyRead = readString(fragment, i);
        const key = decode(keyRead.raw);
        i = keyRead.end;

        while (i < fragment.length && /\s/.test(fragment[i])) i++;
        if (fragment[i] !== ":") break;
        i++;
        while (i < fragment.length && /\s/.test(fragment[i])) i++;

        if (fragment[i] === '"') {
            const valueRead = readString(fragment, i);
            children.push(childFromValue(key, stripPreviewTail(decode(valueRead.raw))));
            i = valueRead.end;
            continue;
        }
        if (fragment[i] === "{" || fragment[i] === "[") {
            // Take the whole nested value, however it ends, and read the fields
            // that matter out of it.
            const open = fragment[i];
            const close = open === "{" ? "}" : "]";
            let depth = 0;
            let j = i;
            for (; j < fragment.length; j++) {
                const ch = fragment[j];
                if (ch === '"') { j = readString(fragment, j).end - 1; continue; }
                if (ch === open) depth++;
                else if (ch === close) { depth--; if (depth === 0) { j++; break; } }
            }
            const block = fragment.slice(i, j);
            const readField = (label) => {
                const at = block.indexOf(`"${label}"`);
                if (at === -1) return null;
                const q = block.indexOf('"', at + label.length + 2);
                if (q === -1) return null;
                return stripPreviewTail(decode(readString(block, q).raw));
            };
            children.push({
                key,
                agent: readField("agent") || key,
                runId: readField("runId"),
                output: readField("output"),
                ok: !/"ok"\s*:\s*false/.test(block),
            });
            i = j;
            continue;
        }
        // A primitive we do not need (number, bool, null): skip to the next comma.
        const nextComma = fragment.indexOf(",", i);
        if (nextComma === -1) break;
        i = nextComma + 1;
    }
    return children;
}

/**
 * @param {string} fragment everything after the `Return: ` marker
 * @returns {Array<{key: string, agent: string, runId: string|null, output: string|null, ok: boolean}>}
 */
export function extractChildren(fragment) {
    let parsed = null;
    try {
        parsed = JSON.parse(fragment.trim());
    } catch {
        // Expected: the preview cap cuts most real notices mid-value.
    }
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        // A single child returns its own fields rather than being keyed.
        if (typeof parsed.output === "string" || typeof parsed.agent === "string") {
            return [childFromValue(parsed.key || parsed.agent || "main", parsed)];
        }
        return Object.entries(parsed).map(([key, value]) => childFromValue(key, value));
    }
    if (Array.isArray(parsed)) {
        return parsed.map((value, i) => childFromValue(String(i), value));
    }

    const scanned = scanChildren(fragment);
    // A single child describes ITSELF at the top level -- {"key":"main",
    // "agent":"scout","output":"..."} -- so the scan sees its fields as if they
    // were siblings. Without this the card is labelled "key" and shows the
    // value of whichever field came first.
    const byKey = new Map(scanned.map((c) => [c.key, c]));
    if (byKey.has("output") || byKey.has("agent")) {
        const valueOf = (k) => (byKey.has(k) ? byKey.get(k).output : null);
        return [{
            key: valueOf("key") || "main",
            agent: valueOf("agent") || valueOf("key") || "main",
            runId: valueOf("runId"),
            output: valueOf("output"),
            ok: !/"ok"\s*:\s*false/.test(fragment),
        }];
    }
    return scanned;
}
