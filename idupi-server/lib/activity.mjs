// ============================================================================
// idupi-server/lib/activity.mjs
//
// Server-side CLI activity: registry, lifecycle encoding, redaction, and
// structural MCP classification for Change A (live-cli-activity-visibility).
//
// Zero dependencies (plain ESM) — the server is intentionally dependency-free.
//
// The ActivityRegistry owns ONE operation-scoped 15s heartbeat per op `id`.
// That heartbeat is STOPPED ONLY by terminalize(id) on a genuine terminal
// cause. It is NOT the SSE keepalive (that lives in chat-events.mjs) and a
// subscriber disconnect must never clear it.
// ============================================================================

export const ACTIVITY_TYPES = Object.freeze({
    START: "activity_start",
    UPDATE: "activity_update",
    HEARTBEAT: "activity_heartbeat",
    END: "activity_end",
    FAILURE: "activity_failure",
    TIMEOUT: "activity_timeout",
});

const HEARTBEAT_INTERVAL_MS = 15000; // <= 20s gap per spec
const TOMBSTONE_MS = 120000;
const TOTAL_CAP_BYTES = 4096;
const FIELD_CAP_BYTES = 1024;

// ---------------------------------------------------------------------------
// redactActivity
//
// Redacts raw stderr / metadata BEFORE it reaches the client. Order is
// significant (per design D3): bearer -> key/token/secret -> paths ->
// URL-query. Malformed/binary input (Unicode replacement char) is dropped
// entirely. Output is bounded by Unicode-aware UTF-8 byte cap, total <= 4096B.
// ids / enums / timestamps are never redacted.
// ---------------------------------------------------------------------------
export function redactActivity(raw, opts = {}) {
    const fieldCap = opts.fieldCap ?? FIELD_CAP_BYTES;
    const totalCap = opts.totalCap ?? TOTAL_CAP_BYTES;

    if (raw == null) return "";
    let s = typeof raw === "string" ? raw : String(raw);
    if (s.indexOf("�") !== -1) return ""; // malformed / binary dropped

    // 1. bearer token
    s = s.replace(/\bBearer\s+[A-Za-z0-9._\-+/=]+/g, "Bearer [REDACTED]");
    // 2. key / token / secret (case-insensitive). Keep the label, drop the value.
    s = s.replace(
        /(\b(?:api[_-]?key|secret|token|password|passwd|pwd|access[_-]?token|auth)\b\s*[:=]\s*)[ \t'"]*[^\s'"]+[ \t'"]*/gi,
        "$1[REDACTED]"
    );
    // 3. filesystem / user / profile paths
    s = s.replace(
        /(?:[A-Za-z]:)?[\\/](?:Users|Home|home|root|data|var|tmp|app|Android|Projects|Documents|Desktop)[\\/][^\s'"]*/g,
        "[PATH]"
    );
    // 4. URL-query secrets
    s = s.replace(
        /([?&](?:token|key|secret|api_key|apikey|access_token|auth|password)=)[^&\s'"]+/gi,
        "$1[REDACTED]"
    );

    // UTF-8 byte cap (per field then total)
    s = truncateUtf8Bytes(s, fieldCap);
    if (Buffer.byteLength(s, "utf8") > totalCap) s = truncateUtf8Bytes(s, totalCap);
    return s;
}

function truncateUtf8Bytes(str, capBytes) {
    let out = "";
    for (const ch of str) {
        const candidate = out + ch;
        if (Buffer.byteLength(candidate, "utf8") > capBytes) break;
        out = candidate;
    }
    return out;
}

// ---------------------------------------------------------------------------
// classifyMcp
//
// Structural, zero-allowlist MCP detection per engine. Returns a normalized
// { engine, isMcp, server, name }. `server` is always additive — never
// replaces the original name/id.
// ---------------------------------------------------------------------------
export function classifyMcp(ev) {
    const engine = ev && ev.engine;
    if (engine === "claude") {
        const name = ev.toolName || ev.name || "";
        const m = /^mcp__([^_]+)__(.+)$/.exec(name);
        if (m) return { engine: "claude", isMcp: true, server: m[1], name };
        return { engine: "claude", isMcp: false, server: undefined, name };
    }
    if (engine === "opencode") {
        const name = ev.toolName || ev.name || "";
        const server = ev.server;
        return { engine: "opencode", isMcp: Boolean(server), name, server };
    }
    if (engine === "pi") {
        const isMcp = ev.statusKey === "mcp";
        return { engine: "pi", isMcp, name: ev.name, server: ev.server };
    }
    return {
        engine: engine || "unknown",
        isMcp: false,
        server: undefined,
        name: ev && (ev.toolName || ev.name),
    };
}

// ---------------------------------------------------------------------------
// encodeActivity
//
// Builds the SSE-ready data object for a lifecycle event. Identity fields
// (id/streamId/engine/project/sessionId) are always present so chat-events can
// filter by context. Redaction is applied to free-text fields (server/detail).
// ---------------------------------------------------------------------------
export function encodeActivity(event) {
    const { type, entry } = event;
    const base = {
        id: entry.id,
        streamId: entry.streamId,
        engine: entry.engine,
        project: entry.project,
        sessionId: entry.sessionId,
    };

    switch (type) {
        case ACTIVITY_TYPES.START:
            return {
                ...base,
                kind: entry.kind,
                name: entry.name,
                server: entry.server != null ? redactActivity(String(entry.server)) : undefined,
                detail: entry.detail != null ? redactActivity(String(entry.detail)) : undefined,
                startedAt: entry.startedAt,
            };
        case ACTIVITY_TYPES.UPDATE:
            return {
                ...base,
                server: entry.server != null ? redactActivity(String(entry.server)) : undefined,
                detail: entry.detail != null ? redactActivity(String(entry.detail)) : undefined,
                lastUpdateAt: entry.lastUpdateAt,
            };
        case ACTIVITY_TYPES.END:
            return {
                ...base,
                ok: !!(event.cause && event.cause.ok),
                server: entry.server != null ? redactActivity(String(entry.server)) : undefined,
                detail: entry.detail != null ? redactActivity(String(entry.detail)) : undefined,
            };
        case ACTIVITY_TYPES.FAILURE:
            return {
                ...base,
                errorClass: event.cause && event.cause.errorClass,
                server: entry.server != null ? redactActivity(String(entry.server)) : undefined,
                detail: entry.detail != null ? redactActivity(String(entry.detail)) : undefined,
            };
        case ACTIVITY_TYPES.TIMEOUT:
            return {
                ...base,
                server: entry.server != null ? redactActivity(String(entry.server)) : undefined,
                detail: entry.detail != null ? redactActivity(String(entry.detail)) : undefined,
            };
        default:
            return { ...base };
    }
}

// ---------------------------------------------------------------------------
// ActivityRegistry
//
// Per op `id` (per invocation `streamId`). Owns the operation-scoped 15s
// heartbeat and a single idempotent terminalize(). The publish callback is
// injected so the registry stays decoupled from the SSE transport (wired in
// index.mjs during engine wiring).
// ---------------------------------------------------------------------------
export class ActivityRegistry {
    constructor({ publish, clock } = {}) {
        this.publish = publish || (() => {});
        this.clock = clock || {
            setInterval,
            clearInterval,
            now: Date.now,
            setTimeout,
        };
        this.entries = new Map();
    }

    start(id, opts = {}) {
        if (this.entries.has(id)) return; // duplicate id ignored
        const now = this.clock.now();
        const entry = {
            id,
            streamId: opts.streamId,
            engine: opts.engine,
            project: opts.project,
            sessionId: opts.sessionId,
            kind: opts.kind,
            name: opts.name,
            server: opts.server,
            detail: opts.detail,
            startedAt: now,
            lastUpdateAt: now,
            terminal: false,
            heartbeatTimer: null,
            tombstoneTimer: null,
        };
        this.entries.set(id, entry);
        this.publish(ACTIVITY_TYPES.START, encodeActivity({ type: ACTIVITY_TYPES.START, entry }));
        this._startHeartbeat(entry);
    }

    _startHeartbeat(entry) {
        entry.heartbeatTimer = this.clock.setInterval(() => {
            if (entry.terminal) return; // safety; terminalize clears the interval
            const now = this.clock.now();
            this.publish(ACTIVITY_TYPES.HEARTBEAT, {
                id: entry.id,
                streamId: entry.streamId,
                engine: entry.engine,
                project: entry.project,
                sessionId: entry.sessionId,
                elapsedMs: now - entry.startedAt,
                sinceLastUpdateMs: now - entry.lastUpdateAt,
                inflight: true, // heartbeat proves only open+inflight
            });
        }, HEARTBEAT_INTERVAL_MS);
    }

    update(id, opts = {}) {
        const entry = this.entries.get(id);
        if (!entry || entry.terminal) return;
        if (opts.server !== undefined) entry.server = opts.server;
        if (opts.detail !== undefined) entry.detail = opts.detail;
        if (opts.name !== undefined) entry.name = opts.name;
        entry.lastUpdateAt = opts.lastUpdateAt != null ? opts.lastUpdateAt : this.clock.now();
        this.publish(ACTIVITY_TYPES.UPDATE, encodeActivity({ type: ACTIVITY_TYPES.UPDATE, entry }));
    }

    // Single idempotent cleanup: terminal=true, clearInterval ONCE, ONE frame,
    // tombstone + delete. Never leaks on any terminal cause.
    terminalize(id, cause = {}) {
        const entry = this.entries.get(id);
        if (!entry || entry.terminal) return; // idempotent
        entry.terminal = true;

        if (entry.heartbeatTimer) {
            this.clock.clearInterval(entry.heartbeatTimer); // clear exactly once
            entry.heartbeatTimer = null;
        }

        let type = ACTIVITY_TYPES.END;
        if (cause.timeout) type = ACTIVITY_TYPES.TIMEOUT;
        else if (cause.errorClass) type = ACTIVITY_TYPES.FAILURE;

        this.publish(type, encodeActivity({ type, entry, cause }));

        entry.tombstoneTimer = this.clock.setTimeout(() => {
            this.entries.delete(id);
        }, TOMBSTONE_MS);
    }
}
