// ============================================================================
// idupi-server/lib/sessions.mjs
//
// Dependency-free logic for the session-listing rework:
//   - SQL string-construction safety (escaping/validation), since `opencode db`
//     takes a single positional SQL string with no parameter binding.
//   - Opaque cursor encode/decode (per-engine and combined shapes).
//   - The combined-page (`engine=all`) k-way merge and its 4-case
//     next-cursor rule.
//   - Per-engine content-free/bounded-read match+order logic (Claude: zero
//     content; Pi: directory-name pre-filter + bounded 8 KB head read) and
//     the lightweight in-memory index those functions build (PR2).
//   - OpenCode's real executable resolution from its npm-generated shim (PR2).
//
// PR1's cursor/merge/SQL functions above remain pure (no I/O). PR2 adds the
// match/order/index/shim-resolution functions below, which DO perform
// bounded filesystem I/O (statSync, a capped head read, directory listing) —
// never a full-file read for match/order decisions, per the design's
// "Ordering Key Obtainable Without Reading File Bodies" requirement. Zero
// npm dependencies either way; only `node:fs`/`node:path`/`node:os`.
//
// This is the testable core the rest of idupi-server wires against in a
// later PR (PR3). See
// openspec/changes/session-listing-accuracy-perf/design.md for the full
// rationale behind every rule implemented here — nothing in this file should
// diverge from that document.
// ============================================================================

import {
    readFileSync,
    statSync,
    readdirSync,
    existsSync,
    openSync,
    readSync,
    closeSync
} from "node:fs";
import { join, dirname, basename } from "node:path";
import { homedir } from "node:os";

// ----------------------------------------------------------------------------
// SQL Safety Contract
// ----------------------------------------------------------------------------

/**
 * Thrown when a value cannot be safely interpolated into an `opencode db`
 * SQL string. `reason` is a stable machine-readable code a caller (e.g. the
 * HTTP route in a later PR) can map to a 502 response.
 */
export class SqlValueError extends Error {
    constructor(message, reason) {
        super(message);
        this.name = "SqlValueError";
        this.reason = reason;
    }
}

/**
 * Escapes a string value for safe interpolation into an `opencode db` SQL
 * string literal, per the design's SQL Safety Contract:
 *   1. Reject a NUL byte (`\u0000`) — never query.
 *   2. Reject a `;` — defense-in-depth against statement chaining. This is a
 *      deliberate over-rejection (`;` is a legal NTFS filename character),
 *      not a claim that such paths cannot exist.
 *   3. Double every `'` (`'` -> `''`) — standard SQL string-literal escaping,
 *      applied AFTER steps 1-2, immediately before interpolation.
 *
 * The caller is responsible for wrapping the returned string in the SQL
 * literal quotes (`'...'`); this function returns the escaped inner content
 * only, so callers can decide the surrounding literal syntax.
 *
 * @param {string} value
 * @returns {string} the escaped value, safe to wrap in `'...'`
 * @throws {SqlValueError} if the value contains a NUL byte or a `;`
 */
export function escapeSqlValue(value) {
    if (typeof value !== "string") {
        throw new SqlValueError(
            `SQL value must be a string, got ${typeof value}`,
            "not-a-string"
        );
    }
    if (value.includes("\u0000")) {
        throw new SqlValueError(
            "SQL value contains a NUL byte",
            "nul-byte"
        );
    }
    if (value.includes(";")) {
        throw new SqlValueError(
            "SQL value contains a ';' character (statement-chaining guard)",
            "semicolon"
        );
    }
    return value.replace(/'/g, "''");
}

/**
 * Validates a numeric value before it is interpolated unquoted into SQL
 * (e.g. `time_updated` from a decoded cursor, or `limit`). Per the SQL
 * Safety Contract: a non-numeric value is rejected (never coerced or
 * interpolated as a string).
 *
 * @param {unknown} value
 * @param {{min?: number, max?: number, integer?: boolean}} [opts]
 * @returns {number} the validated value, unchanged
 * @throws {SqlValueError} if the value is not a finite number, not an
 *   integer when `integer: true` is requested, or outside `[min, max]`
 */
export function validateNumeric(value, opts = {}) {
    const { min, max, integer = false } = opts;

    if (typeof value !== "number" || !Number.isFinite(value)) {
        throw new SqlValueError(
            `Numeric value must be a finite number, got ${typeof value}: ${String(value)}`,
            "not-a-number"
        );
    }
    if (integer && !Number.isInteger(value)) {
        throw new SqlValueError(
            `Numeric value must be an integer, got ${value}`,
            "not-an-integer"
        );
    }
    if (min !== undefined && value < min) {
        throw new SqlValueError(
            `Numeric value ${value} is below the minimum ${min}`,
            "out-of-range"
        );
    }
    if (max !== undefined && value > max) {
        throw new SqlValueError(
            `Numeric value ${value} is above the maximum ${max}`,
            "out-of-range"
        );
    }
    return value;
}

// ----------------------------------------------------------------------------
// Cursor & Pagination Contract
// ----------------------------------------------------------------------------

/** Frozen wire engine values. Never abbreviate these ("pi"/"oc"/"cl"). */
export const ENGINES = Object.freeze(["pi-cli", "opencode", "claude"]);

/** Sentinel meaning an engine's own listing is fully exhausted. */
export const DONE = "done";

/**
 * Thrown when an opaque cursor string cannot be decoded (not valid
 * base64url, or does not decode to valid JSON).
 */
export class CursorDecodeError extends Error {
    constructor(message) {
        super(message);
        this.name = "CursorDecodeError";
    }
}

// Manual base64url conversion (rather than relying on Buffer's native
// "base64url" encoding) so this module does not depend on a minimum Node
// version beyond plain Buffer support.
function toBase64Url(str) {
    return Buffer.from(str, "utf8")
        .toString("base64")
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
}

function fromBase64Url(str) {
    const normalized = str.replace(/-/g, "+").replace(/_/g, "/");
    const padLength = (4 - (normalized.length % 4)) % 4;
    const padded = normalized + "=".repeat(padLength);
    return Buffer.from(padded, "base64").toString("utf8");
}

/**
 * Encodes a cursor payload (a per-engine `{ts, id}` object, or the combined
 * per-engine object keyed by the frozen wire engine values) as one opaque
 * base64url string: `base64url(JSON.stringify(obj))`.
 *
 * @param {unknown} obj
 * @returns {string} opaque cursor token
 */
export function encodeCursor(obj) {
    return toBase64Url(JSON.stringify(obj));
}

/**
 * Decodes an opaque cursor token back into its JSON payload.
 *
 * An absent/empty cursor (`null`, `undefined`, or `""`) means "first page"
 * and decodes to `null`, per the Cursor & Pagination Contract.
 *
 * @param {string | null | undefined} str
 * @returns {unknown} the decoded payload, or `null` for an absent cursor
 * @throws {CursorDecodeError} if the token is not valid base64url JSON
 */
export function decodeCursor(str) {
    if (str === null || str === undefined || str === "") {
        return null;
    }
    if (typeof str !== "string") {
        throw new CursorDecodeError(`Cursor must be a string, got ${typeof str}`);
    }

    let json;
    try {
        json = fromBase64Url(str);
    } catch (err) {
        throw new CursorDecodeError(`Cursor is not valid base64url: ${err.message}`);
    }

    try {
        return JSON.parse(json);
    } catch (err) {
        throw new CursorDecodeError(`Cursor did not decode to valid JSON: ${err.message}`);
    }
}

// ----------------------------------------------------------------------------
// Combined-page algorithm (k-way merge)
// ----------------------------------------------------------------------------

/**
 * Total order comparator: `(timestamp DESC, id DESC, engine ASC)`.
 * `engine` is the final, fully deterministic tiebreaker for the theoretical
 * case of an identical `(timestamp, id)` pair across two different engines
 * (alphabetical: `claude` < `opencode` < `pi-cli`).
 */
function compareCandidates(a, b) {
    if (b.ts !== a.ts) return b.ts - a.ts;
    if (a.id !== b.id) return a.id < b.id ? 1 : -1; // id DESC
    if (a.engine === b.engine) return 0;
    return a.engine < b.engine ? -1 : 1; // engine ASC
}

/**
 * Computes one combined (`engine=all`) page from already-fetched per-engine
 * candidates, implementing the k-way merge and the priority-ordered 4-case
 * next-cursor rule from design.md's "Combined-page algorithm" (step 5).
 *
 * This function performs no I/O and does no fetching itself — each engine's
 * up-to-`limit` candidates (already produced via that engine's own cursor
 * predicate) are passed in via `engineStates`. This matches the design's
 * step 2 ("for every engine not marked 'done', fetch up to `limit` items ...
 * in parallel") happening in the caller, one layer up.
 *
 * @param {number} limit
 * @param {Record<string, {
 *   cursor: {ts: number, id: string} | "done" | null,
 *   fetchResult?: { failed: true } | { failed: false, items: Array<{ts: number, id: string, [key: string]: unknown}> }
 * }>} engineStates
 *   Keyed by engine name (normally the frozen wire values). An engine whose
 *   `cursor` is already `"done"` MUST NOT have a `fetchResult` — it is
 *   skipped entirely (never re-queried), per the design's `"done"` sentinel
 *   semantics. An engine whose `cursor` is not `"done"` MUST have a
 *   `fetchResult` describing this round's fetch outcome (failed, or the
 *   items it returned, `0 <= items.length <= limit`).
 * @returns {{
 *   items: Array<{ts: number, id: string, engine: string, [key: string]: unknown}>,
 *   nextCursors: Record<string, {ts: number, id: string} | "done" | null>,
 *   done: boolean
 * }}
 *   `items` is the merged top-`limit` page (descending). `nextCursors` is
 *   the next per-engine sub-cursor object (encode with `encodeCursor` for
 *   the wire). `done` is `true` only when every engine in scope is `"done"`
 *   — the caller should emit `nextCursor: null` on the wire in that case.
 */
export function mergePage(limit, engineStates) {
    const names = Object.keys(engineStates);
    const candidates = [];

    for (const name of names) {
        const state = engineStates[name];
        if (state.cursor === DONE) continue; // never re-queried, contributes nothing

        const fetch = state.fetchResult;
        if (!fetch || fetch.failed) continue; // rule 1: failure contributes nothing this round

        for (const item of fetch.items) {
            candidates.push({ ...item, engine: name });
        }
    }

    candidates.sort(compareCandidates);
    const emitted = candidates.slice(0, limit);

    const nextCursors = {};

    for (const name of names) {
        const state = engineStates[name];

        if (state.cursor === DONE) {
            // Already exhausted before this round: stays "done", never
            // re-queried, not part of the 4-case rule (it was never fetched).
            nextCursors[name] = DONE;
            continue;
        }

        const fetch = state.fetchResult;

        // Rule 1: E's fetch failed (engine error, not an empty result).
        // Sub-cursor unchanged, E is NOT marked "done". A failure has no
        // well-defined n/m, so it is checked first, before the other rules
        // would otherwise misread its absent fetch as m == 0.
        if (!fetch || fetch.failed) {
            nextCursors[name] = state.cursor ?? null;
            continue;
        }

        const n = fetch.items.length;
        const emittedForEngine = emitted.filter((it) => it.engine === name);
        const m = emittedForEngine.length;

        if (n < limit && m === n) {
            // Rule 2: fetch came up short AND every item it returned was
            // emitted (nothing left unconsumed) -> genuinely exhausted.
            nextCursors[name] = DONE;
        } else if (m >= 1) {
            // Rule 3: at least one item from E was emitted this page.
            // New sub-cursor = {ts, id} of the LAST (lowest-rank) emitted
            // item from E. Any un-emitted remainder (n - m items) is
            // strictly older than that watermark, so it stays reachable.
            const last = emittedForEngine[emittedForEngine.length - 1];
            nextCursors[name] = { ts: last.ts, id: last.id };
        } else {
            // Rule 4: m == 0 (fetched but fully crowded out this page,
            // including n == 0). Sub-cursor left UNCHANGED from the input —
            // never advanced to "last fetched", so E's un-emitted items
            // stay candidates on the next page.
            nextCursors[name] = state.cursor ?? null;
        }
    }

    const done = names.every((name) => nextCursors[name] === DONE);

    return { items: emitted, nextCursors, done };
}

// ----------------------------------------------------------------------------
// Ordering Key & Content-Free Matching (per engine) — PR2
// ----------------------------------------------------------------------------

/** Bounded head-read window for Pi's match+order key, per the design's
 * measured fact 6 (100% of 1106 real Pi session files expose a parseable
 * `type:"session"` line within the first 8 KB). */
export const PI_MATCH_WINDOW_BYTES = 8 * 1024;

/**
 * Normalizes an absolute path for exact cross-platform comparison: lowercase,
 * forward slashes, no trailing slash.
 *
 * Mirrors `idupi-server/index.mjs`'s existing `normalizePathForCompare`
 * (unchanged there). Duplicated here — rather than imported from index.mjs —
 * so this module stays independently importable/testable without pulling in
 * index.mjs's server bootstrap side effects; the two MUST be kept behaviorally
 * identical.
 *
 * @param {string} p
 * @returns {string}
 */
export function normalizePathForCompare(p) {
    return (p || "").toLowerCase().replace(/\\/g, "/").replace(/\/+$/, "");
}

/**
 * Encodes an already-normalized project path into the dash-only directory
 * name form Claude CLI (and, once its directory-name wrapper is stripped,
 * Pi CLI) uses for its per-project session directory: every non-alphanumeric
 * character becomes `-`.
 *
 * @param {string} normalizedPath - output of `normalizePathForCompare`
 * @returns {string}
 */
export function encodeProjectDirName(normalizedPath) {
    return normalizedPath.replace(/[^a-z0-9]/g, "-");
}

/**
 * Encodes an already-normalized project path into the directory-name form
 * Pi CLI actually uses for its per-project session directory (once the
 * `--wrapper` is stripped). This is DELIBERATELY NOT `encodeProjectDirName`:
 * that function (Claude's real convention) turns every non-alphanumeric
 * character into `-`, but Pi's real convention only replaces path
 * separators and the drive-letter colon — it preserves spaces, underscores,
 * and case verbatim. Confirmed against real directory names on disk, e.g.
 * `--C--Users-dev-OneDrive-Escritorio-Mis proyectos-Sistema_de_mantencion--`
 * (space in "Mis proyectos" and underscore in "Sistema_de_mantencion" both
 * survive unescaped). Using the Claude-style encoder here made
 * `piDirNamePreFilter` reject every real Pi project directory whose path
 * contains a space or underscore, which silently zeroed out Pi's session
 * count for any such project — this function exists to prevent that
 * regression from recurring.
 *
 * @param {string} normalizedPath - output of `normalizePathForCompare`
 * @returns {string}
 */
export function encodePiProjectDirName(normalizedPath) {
    return normalizedPath.replace(/[:/]/g, "-");
}

/**
 * Order key for a Claude session file: `statSync(file).mtimeMs`.
 *
 * Reads ZERO file content — filesystem metadata only — satisfying the
 * spec's "Ordering Key Obtainable Without Reading File Bodies" requirement.
 * Claude's match key (directory-name encoding only) is already zero-content
 * and unchanged from the existing code; this function is only the
 * order/paginate key.
 *
 * @param {string} filePath
 * @returns {number} `mtimeMs`
 */
export function orderClaude(filePath) {
    return statSync(filePath).mtimeMs;
}

/**
 * Repairs the dead `--wrapper`-stripping bug: real Pi CLI session directory
 * names are observed wrapped in a literal leading and trailing `--`
 * (e.g. `--C--Users-dev-AndroidStudioProjects-IDUPI--`), which the
 * original `getProjectSessions`'s encoding-only comparison
 * (`index.mjs:866-867`) never accounted for — its `normSubdir === normFull`
 * check was therefore always false against real directories, silently
 * disabling the directory-name pre-filter (matches fell through to the
 * per-file `cwd` comparison alone). Strips exactly one leading `--` and one
 * trailing `--`, if present.
 *
 * @param {string} dirName
 * @returns {string}
 */
export function stripPiDirWrapper(dirName) {
    let s = dirName;
    if (s.startsWith("--")) s = s.slice(2);
    if (s.endsWith("--")) s = s.slice(0, -2);
    return s;
}

/**
 * Necessary — not sufficient — directory-name pre-filter for Pi: `true`
 * when `dirName`, once its wrapper is stripped and lowercased, matches the
 * project's dash-encoded directory name. A readdir-only, zero-content check
 * used purely to prune which subdirectories' files are even opened; the
 * per-file `cwd` comparison in `matchOrderPi` remains the sufficient
 * authority for actual session membership (design.md decision #3).
 *
 * @param {string} dirName - raw subdirectory name from readdir
 * @param {string} projectDirNameEncoded - output of `encodePiProjectDirName`
 *   (NOT `encodeProjectDirName` — Pi's real directory-naming convention
 *   preserves spaces/underscores/case, it does not dash-collapse them)
 * @returns {boolean}
 */
export function piDirNamePreFilter(dirName, projectDirNameEncoded) {
    return stripPiDirWrapper(dirName).toLowerCase() === projectDirNameEncoded.toLowerCase();
}

/**
 * Reads up to `windowBytes` from the start of `filePath`, without reading
 * the rest of the file. The returned text may end mid-line/mid-character at
 * the window boundary — callers only look for one complete leading JSON
 * line (Pi's `type:"session"` metadata line, always line 1), so a partial
 * trailing line is harmless and simply ignored by the parser below.
 *
 * @param {string} filePath
 * @param {number} [windowBytes]
 * @returns {string}
 */
export function readHeadWindow(filePath, windowBytes = PI_MATCH_WINDOW_BYTES) {
    const fd = openSync(filePath, "r");
    try {
        const buf = Buffer.alloc(windowBytes);
        const bytesRead = readSync(fd, buf, 0, windowBytes, 0);
        return buf.toString("utf8", 0, bytesRead);
    } finally {
        closeSync(fd);
    }
}

/**
 * Extracts `{ id, cwd, timestampMs }` from a Pi session file's
 * `type:"session"` metadata line, given the raw text of a bounded read
 * window. Returns `null` when no parseable `type:"session"` line is found
 * within `windowText` — the caller (`matchOrderPi`) is responsible for the
 * escalation-to-full-read decision on a `null` result, per the design's
 * "Match-key-not-found vs. file-too-large" section.
 *
 * `meta.timestamp` is Pi's on-disk **ISO-8601 string** form (measured in
 * 1106 of 1106 real session files, zero numeric), normalized to epoch
 * milliseconds via `Date.parse`. A `NaN`/unparseable result is surfaced as
 * `timestampMs: null` so the caller applies the `stat.mtime` fallback
 * uniformly whether the field was absent or merely unparseable.
 *
 * @param {string} windowText
 * @returns {{ id: string|null, cwd: string|null, timestampMs: number|null } | null}
 */
export function extractPiSessionMeta(windowText) {
    const lines = windowText.split("\n");
    for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        let meta;
        try {
            meta = JSON.parse(trimmed);
        } catch {
            // Possibly a truncated trailing line at the window boundary, or
            // simply not JSON — keep scanning the remaining lines.
            continue;
        }

        if (meta && meta.type === "session") {
            const parsedTs = typeof meta.timestamp === "string" ? Date.parse(meta.timestamp) : NaN;
            return {
                id: typeof meta.id === "string" ? meta.id : null,
                cwd: typeof meta.cwd === "string" ? meta.cwd : null,
                timestampMs: Number.isNaN(parsedTs) ? null : parsedTs
            };
        }
    }
    return null;
}

/**
 * Determines match + order for one candidate Pi session file.
 *
 * Match authority: per-file `cwd`, exact-path-compared via
 * `normalizePathForCompare` — never the directory-name pre-filter alone
 * (spec's "Session without a usable `cwd`" requirement: a session with no
 * usable `cwd` MUST be excluded, not included via a fallback comparison
 * that can never produce a match).
 *
 * Order key: `meta.timestamp` (already normalized to epoch ms by
 * `extractPiSessionMeta`), falling back to `stat.mtime` when absent/`NaN`.
 *
 * The `type:"session"` metadata line must be found within the bounded head
 * window for the session to match; if it isn't, or if the line carries no
 * usable `cwd`, the session is excluded from the match set (never fabricated)
 * and the existing observable structured diagnostic is emitted via
 * `onEscalation` — per design's "Match-key-not-found vs. file-too-large"
 * section, which mandates bounded-only parsing and no escalation to a
 * full-file read.
 *
 * @param {string} filePath
 * @param {string} normalizedProjectPath - output of `normalizePathForCompare`
 * @param {{
 *   readHead?: (filePath: string) => string,
 *   statFile?: (filePath: string) => { mtimeMs: number, mtime: Date },
 *   onEscalation?: (filePath: string) => void
 * }} [deps] - injectable for testing without depending on the real ~/.pi
 *   session directory, and to exercise the bounded-miss diagnostic path
 *   deterministically.
 * @returns {{ match: false } | { match: true, id: string, timestampMs: number, mtimeMs: number }}
 */
export function matchOrderPi(filePath, normalizedProjectPath, deps = {}) {
    const {
        readHead = readHeadWindow,
        statFile = statSync,
        onEscalation = (fp) => console.warn("[Pi Match Escalation]", fp)
    } = deps;

    // Bounded parsing only: the 8 KiB head window is authoritative for the
    // match+order key (design.md "Match-key-not-found vs. file-too-large" and
    // the ratified spec requirement that every rebuild unit stays bounded and
    // NEVER performs a full-file read). If the usable `cwd` is not
    // determinable within that window — either no parseable `type:"session"`
    // line was found, or the session line carried no `cwd` — the session is
    // excluded from the match set (never fabricated into a match) and the
    // existing observable structured diagnostic is emitted so the omission is
    // diagnosable rather than silent.
    const meta = extractPiSessionMeta(readHead(filePath));

    if (meta === null || meta.cwd === null) {
        onEscalation(filePath);
        return { match: false };
    }

    if (normalizePathForCompare(meta.cwd) !== normalizedProjectPath) {
        return { match: false };
    }

    const mtimeMs = statFile(filePath).mtimeMs;
    const timestampMs = meta.timestampMs !== null ? meta.timestampMs : mtimeMs;
    const id = meta.id !== null ? meta.id : basename(filePath, ".jsonl");

    return { match: true, id, timestampMs, mtimeMs };
}

// ----------------------------------------------------------------------------
// OpenCode Invocation — Verified Resolution — PR2
// ----------------------------------------------------------------------------

/** Module-level cache for the resolved OpenCode executable path — resolved
 * once, re-resolved lazily only if the cached path stops existing. */
let _openCodeExePath = null;

/**
 * Resolves OpenCode's real target executable by parsing its npm-generated
 * Windows batch shim (`opencode.cmd`), per design.md's "OpenCode Invocation
 * — Verified Resolution" section: the npm package (`opencode-ai`) ships a
 * native compiled binary, not a JS entry point, so there is no
 * `process.execPath`-style invocation available — the shim's target `.exe`
 * must be resolved and invoked directly (`execFile`, PR3).
 *
 * Caches the resolved path after the first success. Re-resolves lazily
 * (never throws at import/server-startup time) whenever the cached path no
 * longer exists on disk. On failure — unreadable shim, unrecognized shim
 * format, or a resolved path that doesn't exist — throws a clear `Error`;
 * this function never crashes the whole process itself. The caller
 * (`index.mjs`, PR3) is responsible for surfacing that as a per-engine
 * failure (502 / `failures[]`), never a silent empty result.
 *
 * @param {{
 *   shimPath?: string,
 *   readShim?: (path: string) => string,
 *   pathExists?: (path: string) => boolean
 * }} [deps] - injectable so this is testable against the verbatim captured
 *   shim content without depending on the real npm install path existing on
 *   whatever machine runs the test suite.
 * @returns {string} the resolved absolute path to `opencode.exe`
 * @throws {Error} if the shim cannot be read, parsed, or its target doesn't exist
 */
export function resolveOpenCodeExePath(deps = {}) {
    const {
        shimPath = join(homedir(), "AppData", "Roaming", "npm", "opencode.cmd"),
        readShim = (p) => readFileSync(p, "utf8"),
        pathExists = existsSync
    } = deps;

    if (_openCodeExePath && pathExists(_openCodeExePath)) {
        return _openCodeExePath;
    }

    let shimContent;
    try {
        shimContent = readShim(shimPath);
    } catch (err) {
        throw new Error(`Cannot read OpenCode shim at ${shimPath}: ${err.message}`);
    }

    // Fixed, currently-observed two-token shim shape: `"%dp0%\<relative>" %*`.
    // If a future npm/opencode-ai upgrade changes the generated shim format,
    // this throws (never a silent wrong resolution) — see design.md's
    // "Residual/fragility risk" note.
    const match = shimContent.match(/"%dp0%\\(.+?)"\s+%\*/);
    if (!match) {
        throw new Error(`Cannot parse OpenCode shim at ${shimPath}`);
    }

    const resolved = join(dirname(shimPath), match[1]);
    if (!pathExists(resolved)) {
        throw new Error(`Resolved OpenCode path missing: ${resolved}`);
    }

    _openCodeExePath = resolved;
    return resolved;
}

/**
 * Test-only: resets the module-level resolved-path cache so each test starts
 * from a clean cache state. Not part of the public runtime contract.
 */
export function _resetOpenCodeExePathCacheForTests() {
    _openCodeExePath = null;
}

// ----------------------------------------------------------------------------
// Cache & Lightweight Index Contract — PR2 (Pi/Claude only; OpenCode's
// index-building is SQL-side, wired in PR3)
// ----------------------------------------------------------------------------

/** Total order for lightweight index records: `(timestamp DESC, id DESC)`,
 * matching the Cursor & Pagination Contract's ordering total order. */
function compareIndexRecords(a, b) {
    if (b.timestamp !== a.timestamp) return b.timestamp - a.timestamp;
    if (a.id === b.id) return 0;
    return a.id < b.id ? 1 : -1;
}

/**
 * Builds the lightweight index for a single, already directory-matched
 * Claude project directory: `{id, timestamp, filePath}` records for every
 * `.jsonl` session file, sorted `(timestamp DESC, id DESC)`, plus the
 * freshness token (`max(mtimeMs)` over the matching files, `0` when none).
 *
 * Claude's match key is directory-name encoding only (unchanged, zero
 * content) — the caller is responsible for that directory-name match
 * (`index.mjs`'s existing `normSubdir === normFull` check); every `.jsonl`
 * file inside the already-matched directory is a member by definition, so
 * this function performs no additional per-file match decision.
 *
 * @param {string} claudeProjectDir - the already-matched project
 *   subdirectory under `~/.claude/projects`
 * @returns {{ records: Array<{id: string, timestamp: number, filePath: string}>, freshnessToken: number }}
 */
export function buildClaudeIndex(claudeProjectDir) {
    const records = [];
    let freshnessToken = 0;

    if (!existsSync(claudeProjectDir)) {
        return { records, freshnessToken };
    }

    const files = readdirSync(claudeProjectDir).filter((f) => f.endsWith(".jsonl"));

    for (const file of files) {
        const filePath = join(claudeProjectDir, file);
        const timestamp = orderClaude(filePath); // zero content, mtimeMs
        if (timestamp > freshnessToken) freshnessToken = timestamp;
        records.push({ id: basename(file, ".jsonl"), timestamp, filePath });
    }

    records.sort(compareIndexRecords);
    return { records, freshnessToken };
}

/**
 * Builds the lightweight index for Pi: walks every subdirectory under
 * `piSessionsBaseDir`, pruning by the (repaired) directory-name pre-filter
 * before opening any file (necessary, readdir-only), then applies
 * `matchOrderPi`'s bounded per-file `cwd`/timestamp extraction (sufficient)
 * to every remaining candidate `.jsonl` file.
 *
 * @param {string} piSessionsBaseDir - `~/.pi/agent/sessions`
 * @param {string} normalizedProjectPath - output of `normalizePathForCompare`
 * @param {object} [deps] - forwarded to `matchOrderPi` (see its docs)
 * @returns {{ records: Array<{id: string, timestamp: number, filePath: string}>, freshnessToken: number }}
 */
export function buildPiIndex(piSessionsBaseDir, normalizedProjectPath, deps = {}) {
    const projectDirNameEncoded = encodePiProjectDirName(normalizedProjectPath);
    const records = [];
    let freshnessToken = 0;

    if (!existsSync(piSessionsBaseDir)) {
        return { records, freshnessToken };
    }

    const subdirs = readdirSync(piSessionsBaseDir);
    for (const subdir of subdirs) {
        const fullDir = join(piSessionsBaseDir, subdir);
        if (!statSync(fullDir).isDirectory()) continue;
        // Necessary, readdir-only pre-filter: prune non-matching
        // directories before opening any file inside them.
        if (!piDirNamePreFilter(subdir, projectDirNameEncoded)) continue;

        const files = readdirSync(fullDir).filter((f) => f.endsWith(".jsonl"));
        for (const file of files) {
            const filePath = join(fullDir, file);
            const result = matchOrderPi(filePath, normalizedProjectPath, deps);
            if (!result.match) continue;

            if (result.mtimeMs > freshnessToken) freshnessToken = result.mtimeMs;
            records.push({ id: result.id, timestamp: result.timestampMs, filePath });
        }
    }

    records.sort(compareIndexRecords);
    return { records, freshnessToken };
}

/**
 * Builds the lightweight index for one `(project, engine)` pair, per the
 * "Cache & Lightweight Index Contract". Dispatches to `buildClaudeIndex`/
 * `buildPiIndex`. OpenCode is explicitly out of scope here — its
 * index-building is SQL-side (`MAX(time_updated)` + a `WHERE`-scoped query),
 * wired in PR3, never a filesystem walk this module would perform.
 *
 * @param {"claude"|"pi-cli"} engine
 * @param {{
 *   claudeProjectDir?: string,
 *   piSessionsBaseDir?: string,
 *   normalizedProjectPath?: string,
 *   deps?: object
 * }} options
 * @returns {{ records: Array<{id: string, timestamp: number, filePath: string}>, freshnessToken: number }}
 */
export function buildIndex(engine, options = {}) {
    if (engine === "claude") {
        return buildClaudeIndex(options.claudeProjectDir);
    }
    if (engine === "pi-cli") {
        return buildPiIndex(options.piSessionsBaseDir, options.normalizedProjectPath, options.deps);
    }
    throw new Error(
        `buildIndex: unsupported engine '${engine}' (OpenCode index-building is SQL-side, wired in PR3, not a filesystem-walk index)`
    );
}
