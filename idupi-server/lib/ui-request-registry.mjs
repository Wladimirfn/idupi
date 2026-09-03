// ============================================================================
// PendingUiRequestRegistry — engine-agnostic, session-bound registry for the
// `select` / `confirm` / `input` requests emitted by every engine CLI
// (Pi, OpenCode, Claude).
//
// Why this exists
// ---------------
// Until now there was no server-side owner for "the engine asked the user a
// question." Every CLI spawned with stdin ignored, so any prompt from the
// engine just hung until the 300s `AGENT_CLI_TIMEOUT_MS` taskkill ran. The
// fix in two layers:
//
//   1. A registry that gives each request a 120s deadline. If no answer
//      arrives in time, the registry resolves the request itself — blanket
//      `Todo` for `select` (the spec's blanket auto-approve), `cancelled:
//      true` for `confirm` and `input` where "approve" has no meaning. That
//      is Phase A: old APKs and quiet clients never trigger the 300s kill.
//
//   2. A later transport layer (Phase B) wires `POST /api/v1/chat/ui-response`
//      to `resolve()`, validates the body, and writes one JSON line to the
//      engine's stdin. This module does NOT write to stdin — the Phase 2
//      route owns that. We only own the lifecycle (register → resolve | expire)
//      and the exact-value validation.
//
// Token binding (spec §"Authenticated Answer Transport")
// ------------------------------------------------------
// Every entry stores a per-session monotonic `token`. `resolve()` rejects any
// body whose `token` does not equal BOTH the entry's stored token AND the
// session's current token. The second check is what catches a request that
// has been superseded by a newer one for the same session: the old entry's
// token is still there, but `currentTokenFor(sessionId)` has moved on.
//
// Exact-value validation (spec §"Invalid answer re-prompts")
// ---------------------------------------------------------
//   select  → value must be one of `options` (strict string equality)
//   confirm → value must be boolean (true = accept, false = reject)
//   input   → value must be a non-empty string
//
// Validation happens BEFORE any downstream write so a 400 can never leak a
// bad value into the engine. The Phase 2 route will use the same predicate.
//
// Threading model
// ---------------
// Single-process, single-threaded Node. Every mutation goes through the
// public methods and is therefore serialised by the event loop; no locks are
// needed. `setTimeout` handles fire in registration order.
// ============================================================================

import {
    UI_REQUEST_DEADLINE_MS,
    TASKKILL_BACKSTOP_MS,
    isWithinTaskkillBackstop,
} from "./cli-constants.mjs";

export const UI_REQUEST_METHODS = Object.freeze({
    SELECT: "select",
    CONFIRM: "confirm",
    INPUT: "input",
});

const BLANKET_AUTO_APPROVE = "Todo";
const TERMINAL_CANCEL = Object.freeze({ cancelled: true });

/**
 * Validates an incoming answer for `method`. Returns `{ ok: true }` or
 * `{ ok: false, reason }` with a short, human-readable reason suitable for a
 * 400 response. Pure function: no I/O, no side effects, easy to test.
 */
export function validateUiAnswer(method, value, options) {
    switch (method) {
        case UI_REQUEST_METHODS.SELECT: {
            if (typeof value !== "string") {
                return { ok: false, reason: "select requires a string option" };
            }
            if (!Array.isArray(options) || !options.includes(value)) {
                return { ok: false, reason: "value is not one of the offered options" };
            }
            return { ok: true };
        }
        case UI_REQUEST_METHODS.CONFIRM: {
            if (typeof value !== "boolean") {
                return { ok: false, reason: "confirm requires a boolean" };
            }
            return { ok: true };
        }
        case UI_REQUEST_METHODS.INPUT: {
            if (typeof value !== "string" || value.trim().length === 0) {
                return { ok: false, reason: "input requires a non-empty string" };
            }
            return { ok: true };
        }
        default:
            return { ok: false, reason: `unknown method '${method}'` };
    }
}

/**
 * Builds the terminal decision the registry returns when its 120s timer
 * expires. Blanket auto-approve for `select` (spec: "Fallback MUST be blanket
 * auto-approve ('Todo': approve any request)"), and a `cancelled: true`
 * payload for the methods where "approve" is meaningless.
 *
 * Pure function so a test can assert on the decision shape without spinning
 * up the registry.
 */
export function buildAutoApproveDecision(method) {
    if (method === UI_REQUEST_METHODS.SELECT) {
        return { value: BLANKET_AUTO_APPROVE, source: "auto_approve" };
    }
    return { value: TERMINAL_CANCEL, source: "auto_approve" };
}

function randomRequestId() {
    // Prefer the runtime crypto API; fall back to Math.random for environments
    // where it is unavailable. The id only needs to be unique within a single
    // registry instance, so collision-resistance is enough — no PII concerns.
    try {
        // node 18+: globalThis.crypto.randomUUID exists
        return `uir_${globalThis.crypto.randomUUID()}`;
    } catch {
        return `uir_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
    }
}

/**
 * Registry of pending UI requests keyed by `requestId`. One per process.
 *
 * Listeners are notified for every state transition:
 *   - `"register"` → { entry } when a new request is added
 *   - `"resolve"`  → { entry, value, source: "client" } on a successful client answer
 *   - `"expire"`   → { entry, decision } when the 120s timer auto-resolves
 *
 * Phase A wires a single logger to `"expire"`. Phase 2 will additionally
 * subscribe to `"register"` to emit `ui_request` SSE frames and to `"expire"`
 * to emit `ui_request_resolved` so the app can drop the dialog.
 */
export class PendingUiRequestRegistry {
    /**
     * @param {object} [opts]
     * @param {number} [opts.deadlineMs] override the default grace window
     *   (used by tests to fire the timer synchronously without flakiness).
     * @param {number} [opts.backstopMs] override the 300s taskkill backstop
     *   reference (same — test only).
     */
    constructor({ deadlineMs = UI_REQUEST_DEADLINE_MS, backstopMs = TASKKILL_BACKSTOP_MS } = {}) {
        if (!isWithinTaskkillBackstop(deadlineMs, backstopMs)) {
            throw new Error(
                `[ui-request-registry] deadlineMs=${deadlineMs} must be strictly less than backstopMs=${backstopMs}.`,
            );
        }
        this.deadlineMs = deadlineMs;
        this.backstopMs = backstopMs;

        /** @type {Map<string, object>} requestId → entry */
        this._entries = new Map();

        /** Per-session monotonic counter. Bumping on every register() is what
         * makes a superseded request's token "stale" the moment a new one
         * lands — even though the old entry is still in `_entries`. */
        this._sessionTokens = new Map();
    }

    // -- Listeners ----------------------------------------------------------

    /** @type {Map<string, Set<Function>>} */
    _listeners = new Map();

    on(event, fn) {
        if (typeof fn !== "function") return () => {};
        let set = this._listeners.get(event);
        if (!set) {
            set = new Set();
            this._listeners.set(event, set);
        }
        set.add(fn);
        return () => set.delete(fn);
    }

    _emit(event, payload) {
        const set = this._listeners.get(event);
        if (!set) return;
        for (const fn of set) {
            try {
                fn(payload);
            } catch (err) {
                console.warn(`[ui-request-registry] listener for '${event}' threw: ${err?.message || err}`);
            }
        }
    }

    // -- Lifecycle ----------------------------------------------------------

    /**
     * Register a new pending request. Allocates a per-session monotonic token,
     * arms a 120s expiry timer, and returns `{ requestId, token, deadlineMs }`
     * for the caller to ship to the app via SSE. The token is what the app
     * echoes back on `POST /api/v1/chat/ui-response/:requestId`.
     *
     * Older unresolved entries for the same session are NOT cleared — they
     * stay alive (their own timer keeps running) but their token is now stale
     * by construction: `currentTokenFor(sessionId)` has moved on, so
     * `resolve()` will reject any late answer for them.
     *
     * @param {object} req
     * @param {string} req.sessionId  bound to a chat session; never the raw HTTP request
     * @param {string} [req.engine]   pi | opencode | claude (only used for logging)
     * @param {string} req.method     "select" | "confirm" | "input"
     * @param {string[]} [req.options]  offered options for `select`; ignored otherwise
     * @param {string} [req.title]    title for the app dialog
     * @param {string} [req.message]  body for the app dialog
     * @returns {{requestId: string, token: number, deadlineMs: number, expiresAt: number}}
     */
    register(req) {
        if (!req || typeof req !== "object") {
            throw new TypeError("register() requires a request object");
        }
        const { sessionId, method, options = [], engine = "unknown", title = "", message = "" } = req;
        if (typeof sessionId !== "string" || sessionId.length === 0) {
            throw new TypeError("register() requires a non-empty sessionId");
        }
        if (!Object.values(UI_REQUEST_METHODS).includes(method)) {
            throw new TypeError(`register() requires method ∈ ${JSON.stringify(Object.values(UI_REQUEST_METHODS))}`);
        }
        if (method === UI_REQUEST_METHODS.SELECT && (!Array.isArray(options) || options.length === 0)) {
            throw new TypeError("register() for select requires a non-empty options array");
        }

        const nextToken = (this._sessionTokens.get(sessionId) || 0) + 1;
        this._sessionTokens.set(sessionId, nextToken);

        const requestId = randomRequestId();
        const expiresAt = Date.now() + this.deadlineMs;

        const entry = {
            requestId,
            sessionId,
            engine,
            method,
            options: method === UI_REQUEST_METHODS.SELECT ? [...options] : [],
            title,
            message,
            token: nextToken,
            registeredAt: Date.now(),
            expiresAt,
            // resolvedAt populated only when the entry transitions to terminal
            resolved: false,
            resolution: null, // "client" | "auto_approve" | "cancelled"
        };

        this._entries.set(requestId, entry);
        entry._timer = setTimeout(() => this._onTimerFire(requestId), this.deadlineMs);
        // The process can be asked to exit while a request is still pending;
        // we do NOT want the timer to keep the loop alive in that case. The
        // registry is a long-lived singleton, but a single timer must not pin
        // the event loop.
        if (typeof entry._timer.unref === "function") entry._timer.unref();

        this._emit("register", { entry });
        return {
            requestId,
            token: nextToken,
            deadlineMs: this.deadlineMs,
            expiresAt,
        };
    }

    /**
     * Resolve a pending request with a client-supplied answer.
     *
     * The contract is deliberately tight: any mismatch returns `{ ok: false,
     * status }` and the entry is left intact so the 120s timer can still
     * auto-resolve it. We never throw on a bad body — the Phase 2 route maps
     * `status` to the HTTP code (400 / 404 / 409).
     *
     * Status codes:
     *   400 → malformed answer (validation failed)
     *   404 → no entry for `requestId`
     *   409 → token is stale (entry superseded OR body.token mismatch)
     *
     * @param {object} args
     * @param {string} args.requestId
     * @param {number|string} args.token       echoed from `body.token` by the route
     * @param {string} args.sessionId         echoed from `body.sessionId` by the route
     * @param {*}      args.value             the answer itself
     * @returns {{ok: true, entry: object, value: *}
     *          | {ok: false, status: 400|404|409, reason: string}}
     */
    resolve({ requestId, token, sessionId, value } = {}) {
        const entry = this._entries.get(requestId);
        if (!entry) {
            return { ok: false, status: 404, reason: "unknown requestId" };
        }
        if (typeof token === "undefined" || token === null || Number(entry.token) !== Number(token)) {
            return { ok: false, status: 409, reason: "token mismatch" };
        }
        if (Number(this.currentTokenFor(sessionId)) !== Number(entry.token)) {
            // A newer register() for the same session bumped the monotonic
            // counter. The old entry is still in the map (so it can keep
            // holding its own timer), but its token is no longer "current" —
            // any answer for it is by definition stale.
            return { ok: false, status: 409, reason: "request superseded by a newer one in this session" };
        }
        if (typeof sessionId !== "string" || sessionId !== entry.sessionId) {
            return { ok: false, status: 409, reason: "sessionId does not match the entry's session" };
        }

        const validation = validateUiAnswer(entry.method, value, entry.options);
        if (!validation.ok) {
            return { ok: false, status: 400, reason: validation.reason };
        }

        // Terminal transition. Clear the timer first so a late fire cannot
        // race the resolve and emit a second `expire` event.
        this._terminate(entry, { source: "client", value });
        return { ok: true, entry, value };
    }

    /**
     * Force expiry of a request. Mostly for tests; the timer calls this on
     * its own. Returns the decision that was applied, or `null` if the entry
     * no longer exists (e.g. already resolved by a client).
     */
    expire(requestId) {
        return this._onTimerFire(requestId);
    }

    /**
     * Latest registered token for a session. 0 if the session has never
     * registered. Read-only; never mutates state.
     */
    currentTokenFor(sessionId) {
        if (typeof sessionId !== "string") return 0;
        return this._sessionTokens.get(sessionId) || 0;
    }

    /**
     * Snapshot of pending entries for a session (read-only copy). Used by
     * Phase 2 to know what to attach to outgoing SSE frames.
     */
    pendingFor(sessionId) {
        const out = [];
        for (const entry of this._entries.values()) {
            if (!entry.resolved && entry.sessionId === sessionId) out.push(entry);
        }
        return out;
    }

    count() {
        let n = 0;
        for (const entry of this._entries.values()) if (!entry.resolved) n++;
        return n;
    }

    /**
     * Cancel every pending entry, emitting `expire` for each so listeners see
     * a consistent lifecycle even on shutdown. Safe to call repeatedly.
     */
    cancelAll() {
        const ids = Array.from(this._entries.keys());
        for (const id of ids) this.expire(id);
    }

    // -- Internals ----------------------------------------------------------

    _terminate(entry, resolution) {
        entry.resolved = true;
        entry.resolvedAt = Date.now();
        entry.resolution = resolution.source;
        if (entry._timer) {
            clearTimeout(entry._timer);
            entry._timer = null;
        }
        // Drop from the live map so `count()` and `pendingFor()` stop returning
        // it. We keep the entry reachable from the listeners' payload until
        // the next tick — listeners that want to read `entry` after resolve
        // are fine because we passed a reference, not a clone.
        this._entries.delete(entry.requestId);
    }

    _onTimerFire(requestId) {
        const entry = this._entries.get(requestId);
        if (!entry || entry.resolved) return null;
        const decision = buildAutoApproveDecision(entry.method);
        this._terminate(entry, { source: decision.source, value: decision.value });
        const payload = { entry, decision };
        this._emit("expire", payload);
        return decision;
    }
}
