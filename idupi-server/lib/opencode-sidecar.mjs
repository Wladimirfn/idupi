// ============================================================================
// idupi-server/lib/opencode-sidecar.mjs
//
// Persistent `opencode serve` sidecar that gives the chat bridge a real
// answer path for OpenCode permission + question prompts.
//
// Why this exists
// ---------------
// `opencode run --auto` self-approves in milliseconds; without it, the CLI
// hangs waiting on stdin that nobody writes. A persistent `serve` process is
// the documented headless surface (verified v1.18.29): it stays up, exposes
// SSE under `/event`, and accepts a real HTTP reply under the permission
// reply route. This module owns ONE sidecar per chat session and maps its
// SSE frames into the PendingUiRequestRegistry the rest of the server uses
// for every other engine.
//
// Contract (RED-tested by idupi-server/test/opencode-sidecar.test.mjs)
// --------------------------------------------------------------------
//   verifyConfigPrecondition() -> reads the effective OpenCode config from
//                              disk and verifies at least one sensitive
//                              operation is set to `ask`. Rejects if the
//                              config is missing, unparseable, or weaker
//                              than `ask` for every sensitive operation —
//                              the sidecar MUST NOT spawn when the engine
//                              is going to self-approve every prompt (R6).
//   spawn()                  -> binds 127.0.0.1:0, parses the listen line,
//                              then GET /global/health with a 3s budget.
//                              Rejects on health timeout / non-200.
//   subscribeEvents({...})   -> owns a minimal SSE parser; routes
//                              permission.asked / v2.asked to onAsk
//                              (canonical { method:"confirm",
//                              requestId, sessionId, deadlineMs:120_000 }),
//                              question.asked to onAsk({method:"select",
//                              options:[...] }), permission.saved to
//                              onSaved, permission.removed to onRemoved,
//                              everything else to onUnknown. Dedups by
//                              requestID inside one sidecar instance.
//   reply({sid, rid, value}) -> POST /api/session/{sid}/permission/{rid}/reply
//                              with { reply:"once"|"reject"|"always" }.
//                              204 -> { ok:true }; 404 -> { ok:true,
//                              expired:true } (NEVER throws); other -> {
//                              ok:false, status, body }.
//   listPendingPermissions() -> GET /api/permission; D7 snapshot fallback
//                              when v1.18.29 does not emit a
//                              permission.removed frame (spike-confirmed).
//   shutdown({sigtermMs})    -> SIGTERM, wait `sigtermMs`, then SIGKILL.
//   baseUrl                  -> the loopback URL the sidecar is bound to.
//
// Zero new dependencies. Built on `node:child_process` + `node:http`. The
// SSE wire is small enough (`event:` / `data:` / blank-line frames, `:`-
// prefixed heartbeats) that pulling in `eventsource` would add a dep for no
// gain — D4 in design.md.
//
// Threading model
// ---------------
// Single-process, single-threaded Node. Every state mutation goes through
// the public methods and is therefore serialised by the event loop. The SSE
// parser runs on the child stdout 'data' event; HTTP replies run on the
// server-side reply route. No locks.
// ============================================================================

import { spawn as nodeSpawn } from "node:child_process";
import http from "node:http";
import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import { resolveOpenCodeExePath } from "./sessions.mjs";
import { UI_REQUEST_DEADLINE_MS } from "./cli-constants.mjs";

// ---------------------------------------------------------------------------
// Wire constants
// ---------------------------------------------------------------------------

/** Argument vector that the sidecar hands to `opencode serve`. Matches the
 *  shape `opencode serve --help` advertises in v1.18.29. The `--port 0`
 *  asks the server to bind to an ephemeral loopback port; we read the
 *  actual port from the child's stdout. */
const SERVE_ARGV = Object.freeze([
    "serve",
    "--hostname", "127.0.0.1",
    "--port", "0",
]);

/** Path the sidecar probes to confirm the server is actually serving
 *  requests (not just listening on the socket). v1.18.29 returns
 *  `{ healthy:true, version:"1.18.29" }` on 200. */
const HEALTH_PATH = "/global/health";

/** Canonical reply route per proposal/design.md. The actual shape under
 *  v1.18.29 differs (`/session/{id}/permissions/{permissionID}`) — the
 *  shape we POST to here is the project's design intent; the spike
 *  (`scratch/serve-expiry-spike.mjs`) is the operator-driven validation
 *  that decides whether to swap to the v1.x native path in a follow-up
 *  PR. Either way the sidecar's contract (idempotent 404, never throw)
 *  is unchanged. */
const REPLY_PATH = (sid, rid) => `/api/session/${encodeURIComponent(sid)}/permission/${encodeURIComponent(rid)}/reply`;

/** D7 fallback snapshot path. */
const PERMISSIONS_SNAPSHOT_PATH = "/api/permission";

/** Effective OpenCode config path. R6 precondition check reads this file
 *  to verify at least one sensitive operation is set to `ask` before
 *  sidecar spawn. */
const DEFAULT_OPENCODE_CONFIG_PATH = join(homedir(), ".config", "opencode", "opencode.json");

/** Sensitive operations the precondition check looks at. If any of these
 *  is at `ask` level in the user's opencode.json, the engine is willing to
 *  surface permission prompts to the sidecar and card mediation works.
 *  If NONE is at `ask` (all `allow`, all `deny`, or all missing) the
 *  sidecar cards will never fire and the user loses mediation silently —
 *  the precondition check fails closed with a clear reason. */
const SENSITIVE_PERMISSION_KEYS = Object.freeze([
    "bash",
    "edit",
    "write",
    "webfetch",
    "patch",
    "read",
]);

/**
 * Default filesystem reader for the precondition check. Pure seam so tests
 * can inject a fake `readDoc` and assert on the misconfigured path without
 * touching the user's actual ~/.config/opencode/opencode.json.
 */
function defaultReadConfig(path) {
    if (!existsSync(path)) return null;
    try {
        const text = readFileSync(path, "utf8");
        const parsed = JSON.parse(text);
        return (parsed && typeof parsed === "object") ? parsed : null;
    } catch {
        return null;
    }
}

/**
 * Pure helper: given the parsed opencode.json document, decide whether the
 * R6 precondition is satisfied. Returns `{ ok: true }` if at least one
 * sensitive operation is at `ask`; otherwise `{ ok: false, reason }` with
 * a short human-readable reason suitable for an error message. The reason
 * is intentionally diagnostic so a user who hits this can fix the config
 * without reading the spec.
 */
export function evaluatePermissionPrecondition(doc) {
    if (doc == null || typeof doc !== "object") {
        return {
            ok: false,
            reason: "opencode.json is missing or unparseable; sidecar cannot verify that the engine will surface `ask` prompts",
        };
    }
    const perm = doc.permission;
    if (perm == null || typeof perm !== "object") {
        return {
            ok: false,
            reason: "opencode.json has no `permission` block; sensitive operations default to `allow` (auto-approve), so card mediation would never fire",
        };
    }
    const askKeys = [];
    const allowKeys = [];
    const denyKeys = [];
    for (const key of SENSITIVE_PERMISSION_KEYS) {
        const v = perm[key];
        if (v === "ask") askKeys.push(key);
        else if (v === "allow") allowKeys.push(key);
        else if (v === "deny") denyKeys.push(key);
    }
    if (askKeys.length === 0) {
        const detail = allowKeys.length > 0
            ? `the following sensitive operations are auto-approved: ${allowKeys.join(", ")}`
            : denyKeys.length > 0
                ? `the following sensitive operations are denied: ${denyKeys.join(", ")}`
                : "no sensitive operation is set to `ask`";
        return {
            ok: false,
            reason: `R6 precondition not satisfied: ${detail}. At least one of [${SENSITIVE_PERMISSION_KEYS.join(", ")}] MUST be at \`ask\` so the sidecar can mediate permission cards.`,
        };
    }
    return { ok: true, askKeys };
}

// ---------------------------------------------------------------------------
// Default httpRequest seam (node:http)
// ---------------------------------------------------------------------------

/**
 * Real HTTP transport seam. The constructor accepts an alternative so tests
 * can inject fakes; production uses this. Returns `{ status, body }` for any
 * response. Throws only on transport errors (DNS failure, socket reset,
 * request timeout) — never on non-2xx status codes.
 */
async function defaultHttpRequest({ method, url, headers = {}, body, timeoutMs }) {
    return await new Promise((resolve, reject) => {
        let parsed;
        try {
            parsed = new URL(url);
        } catch (err) {
            reject(new Error(`invalid url: ${url}`));
            return;
        }
        const req = http.request(
            {
                method,
                hostname: parsed.hostname,
                port: parsed.port,
                path: parsed.pathname + parsed.search,
                headers: {
                    "content-type": "application/json",
                    ...headers,
                },
            },
            (res) => {
                let buf = "";
                res.setEncoding("utf8");
                res.on("data", (chunk) => { buf += chunk; });
                res.on("end", () => resolve({ status: res.statusCode, body: buf }));
                res.on("error", reject);
            }
        );
        req.on("error", reject);
        if (typeof timeoutMs === "number" && timeoutMs > 0) {
            req.setTimeout(timeoutMs, () => {
                req.destroy(new Error(`HTTP ${method} ${url} timed out after ${timeoutMs}ms`));
            });
        }
        if (body !== undefined && body !== null) {
            const payload = typeof body === "string" ? body : JSON.stringify(body);
            req.write(payload);
        }
        req.end();
    });
}

// ---------------------------------------------------------------------------
// Minimal SSE parser — D4
// ---------------------------------------------------------------------------
//
// Wire format (one frame per blank line):
//   event: <name>\n
//   data: <line>\n     (one or more)
//   id: <optional>\n
//   retry: <optional>\n
//   \n
//
// Heartbeat lines start with `:`. We ignore them. Frames with no `data:`
// are ignored (nothing to deliver).
// ---------------------------------------------------------------------------

/**
 * Streaming SSE parser. Feed it `chunk`s as they arrive; it returns an
 * array of completed frames `{ event, data }` (data is the joined string
 * after `\n`-collapsing per the spec). Partial frames are buffered
 * internally until the next chunk completes them.
 */
function createSseParser() {
    let buffer = "";
    let currentEvent = "message";
    let currentData = [];
    const out = [];

    const flush = () => {
        if (currentData.length > 0) {
            out.push({ event: currentEvent, data: currentData.join("\n") });
        }
        currentEvent = "message";
        currentData = [];
    };

    return {
        feed(chunk) {
            buffer += chunk;
            let nl;
            while ((nl = buffer.indexOf("\n")) >= 0) {
                const rawLine = buffer.slice(0, nl);
                buffer = buffer.slice(nl + 1);
                const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
                if (line.length === 0) {
                    flush();
                    continue;
                }
                if (line.startsWith(":")) {
                    // SSE comment / heartbeat — ignore.
                    continue;
                }
                const colon = line.indexOf(":");
                let field, value;
                if (colon < 0) {
                    field = line;
                    value = "";
                } else {
                    field = line.slice(0, colon);
                    value = line.slice(colon + 1).replace(/^ /, "");
                }
                if (field === "event") {
                    currentEvent = value;
                } else if (field === "data") {
                    currentData.push(value);
                }
                // id / retry ignored — sidecar doesn't reconnect.
            }
        },
        end() {
            // Drain trailing frame if the stream ends without a blank line.
            if (buffer.length > 0 || currentData.length > 0) {
                flush();
            }
        },
        drain() {
            const frames = out.slice();
            out.length = 0;
            return frames;
        },
    };
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export class OpenCodeSidecar {
    /**
     * @param {object} [opts]
     * @param {(cmd: string, args: string[], opts?: object) => import("node:child_process").ChildProcess} [opts.spawn]
     *   child_process.spawn replacement; tests inject a fake.
     * @param {(req: { method: string, url: string, headers?: object, body?: any, timeoutMs?: number }) => Promise<{ status: number, body: string }>} [opts.httpRequest]
     *   HTTP transport seam; tests inject a fake responder.
     * @param {(msg: string) => void} [opts.log]
     *   structured logger; defaults to console.log with `[opencode-sidecar]` prefix.
     * @param {number} [opts.healthTimeoutMs=3000]
     *   budget for the post-spawn `/global/health` probe (D3).
     * @param {number} [opts.readyTimeoutMs=8000]
     *   budget for the listen-line parser to see `server listening on ...`.
     * @param {string} [opts.configPath]
     *   effective OpenCode config file path used by the R6 precondition
     *   check. Defaults to `~/.config/opencode/opencode.json`. Tests inject
     *   a temp file path so the suite is hermetic on Windows + Linux.
     * @param {(path: string) => object | null} [opts.readConfig]
     *   filesystem reader for the precondition check; defaults to a JSON
     *   read of `configPath`. Tests inject a fake so they can drive both
     *   "missing" and "misconfigured" cases without touching the real
     *   user's opencode.json.
     * @param {boolean} [opts.skipPrecondition=false]
     *   when true, the sidecar skips the R6 precondition check entirely.
     *   Production should NEVER set this; the seam exists for tests and
     *   for the rare recovery path where a maintainer has explicitly
     *   accepted the misconfiguration risk.
     */
    constructor({
        spawn = nodeSpawn,
        httpRequest = defaultHttpRequest,
        log = (msg) => console.log(`[opencode-sidecar] ${msg}`),
        healthTimeoutMs = 3_000,
        readyTimeoutMs = 8_000,
        configPath = DEFAULT_OPENCODE_CONFIG_PATH,
        readConfig = defaultReadConfig,
        skipPrecondition = false,
    } = {}) {
        this._spawn = spawn;
        this._httpRequest = httpRequest;
        this._log = log;
        this._healthTimeoutMs = healthTimeoutMs;
        this._readyTimeoutMs = readyTimeoutMs;
        this._configPath = configPath;
        this._readConfig = readConfig;
        this._skipPrecondition = skipPrecondition === true;

        /** @type {import("node:child_process").ChildProcess | null} */
        this._child = null;
        /** @type {number | null} */
        this._port = null;
        /** SSE parser instance, created by subscribeEvents. */
        this._sse = null;

        /** Dedup set for already-mapped requestIDs. One per sidecar
         *  (== one per chat session); survives reconnect replays. */
        this._seen = new Set();
    }

    /**
     * Loopback URL the sidecar is bound to. Null until `spawn()` resolves.
     */
    get baseUrl() {
        if (this._port == null) return null;
        return `http://127.0.0.1:${this._port}`;
    }

    /**
     * R6 precondition: verify the effective OpenCode config enforces `ask`
     * for at least one sensitive operation. Fail closed otherwise so the
     * server does NOT silently launch `opencode serve` when the user has
     * auto-approved every sensitive op — the sidecar cards would never
     * fire and the user would lose mediation without a clear reason.
     *
     * Pure against the injected `readConfig` seam so tests can drive both
     * the satisfied and the rejected paths without touching the user's
     * real opencode.json.
     */
    async verifyConfigPrecondition() {
        if (this._skipPrecondition) {
            return { ok: true, skipped: true };
        }
        let doc;
        try {
            doc = this._readConfig(this._configPath);
        } catch (err) {
            throw new Error(
                `[opencode-sidecar] R6 precondition check failed: cannot read ${this._configPath}: ${err.message}`,
            );
        }
        const result = evaluatePermissionPrecondition(doc);
        if (!result.ok) {
            throw new Error(`[opencode-sidecar] ${result.reason}`);
        }
        return { ok: true, askKeys: result.askKeys };
    }

    /**
     * Spawn `opencode serve`, parse the listen line, probe /global/health.
     * Resolves once health succeeds. Rejects on listen-timeout, health
     * timeout, or non-200 health response.
     *
     * Failure mode = fail-closed (D5): the caller (index.mjs in PR 2) MUST
     * NOT relaunch OpenCode with self-approval when this throws.
     */
    async spawn() {
        if (this._child) {
            throw new Error("[opencode-sidecar] spawn() called twice on the same instance");
        }

        let opencodeExe;
        try {
            opencodeExe = resolveOpenCodeExePath();
        } catch (err) {
            throw new Error(`[opencode-sidecar] cannot resolve opencode exe: ${err.message}`);
        }

        // R6 precondition check runs BEFORE the sidecar child is spawned —
        // if the engine is configured to auto-approve every sensitive op,
        // launching the sidecar would surface zero cards and the user
        // would lose mediation without a clear reason. Fail closed.
        try {
            await this.verifyConfigPrecondition();
        } catch (err) {
            throw new Error(`[opencode-sidecar] R6 precondition failed before spawn: ${err.message}`);
        }

        const child = this._spawn(opencodeExe, [...SERVE_ARGV], {
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
        });
        this._child = child;

        // Parse the listen line from stdout. v1.18.29 prints:
        //   "opencode server listening on http://127.0.0.1:<port>"
        // We bound by readyTimeoutMs — if the server never prints, we kill
        // it and reject so the caller can fail closed.
        const listenLine = await new Promise((resolve, reject) => {
            let buf = "";
            let settled = false;
            const timer = setTimeout(() => {
                if (settled) return;
                settled = true;
                try { child.kill("SIGKILL"); } catch {}
                reject(new Error(`[opencode-sidecar] serve did not print a listen line within ${this._readyTimeoutMs}ms`));
            }, this._readyTimeoutMs);

            const onChunk = (chunk) => {
                buf += chunk.toString("utf8");
                const m = buf.match(/listening on https?:\/\/127\.0\.0\.1:(\d+)/i);
                if (m && !settled) {
                    settled = true;
                    clearTimeout(timer);
                    resolve({ line: buf, port: Number(m[1]) });
                }
            };
            child.stdout?.on("data", onChunk);

            child.once("error", (err) => {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                reject(new Error(`[opencode-sidecar] serve child errored before listen: ${err.message}`));
            });
            child.once("close", (code) => {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                reject(new Error(`[opencode-sidecar] serve exited (code=${code}) before listen line`));
            });
        });

        this._port = listenLine.port;
        this._log(`spawn ready: pid=${child.pid} port=${this._port}`);

        // Probe /global/health. D3: TCP would return OK on a half-dead
        // process; HTTP catches a hung event loop. We enforce the timeout
        // at the module level (Promise.race) rather than relying on the
        // injected httpRequest seam — the seam may be a test fake or a
        // buggy production client that forgets to honor its own
        // `timeoutMs`, and the spawn path is the fail-closed boundary
        // we cannot afford to hang.
        let healthResp;
        try {
            healthResp = await Promise.race([
                this._httpRequest({
                    method: "GET",
                    url: `${this.baseUrl}${HEALTH_PATH}`,
                    timeoutMs: this._healthTimeoutMs,
                }),
                delay(this._healthTimeoutMs).then(() => {
                    throw new Error(`[opencode-sidecar] /global/health probe timed out after ${this._healthTimeoutMs}ms`);
                }),
            ]);
        } catch (err) {
            try { child.kill("SIGKILL"); } catch {}
            this._child = null;
            this._port = null;
            throw new Error(`[opencode-sidecar] /global/health probe failed: ${err.message}`);
        }

        if (healthResp.status !== 200) {
            try { child.kill("SIGKILL"); } catch {}
            this._child = null;
            this._port = null;
            throw new Error(`[opencode-sidecar] /global/health returned status=${healthResp.status}; failing closed`);
        }

        // Wire stderr into the logger (best-effort; do not throw if the
        // pipe is gone).
        child.stderr?.on("data", (chunk) => {
            const text = chunk.toString("utf8").trim();
            if (text) this._log(`stderr: ${text}`);
        });
        child.once("close", (code) => {
            this._log(`serve exited (code=${code})`);
            this._child = null;
            this._sse = null;
        });

        return { port: this._port, pid: child.pid };
    }

    /**
     * Subscribe to the sidecar's SSE `/event` stream and route the four
     * canonical event types into the caller-supplied callbacks. Returns
     * `this` so callers can chain.
     *
     * @param {object} handlers
     * @param {(entry: { method: "confirm" | "select", requestId: string, sessionId: string, deadlineMs: number, message?: string, options?: string[] }) => void} handlers.onAsk
     *   permission.asked / v2.asked / question.asked — canonical registry shape.
     * @param {(entry: { requestId: string, reply: string }) => void} handlers.onSaved
     *   permission.saved — caller logs and suppresses future asks.
     * @param {(entry: { requestId: string }) => void} handlers.onRemoved
     *   permission.removed — D7 vanish-abort signal.
     * @param {(entry: { type: string, raw: string }) => void} handlers.onUnknown
     *   any other event type; useful for forward-compat.
     */
    subscribeEvents({ onAsk, onSaved, onRemoved, onUnknown } = {}) {
        if (!this._child) {
            throw new Error("[opencode-sidecar] subscribeEvents() called before spawn()");
        }
        if (typeof onAsk !== "function"
            || typeof onSaved !== "function"
            || typeof onRemoved !== "function"
            || typeof onUnknown !== "function") {
            throw new TypeError("[opencode-sidecar] subscribeEvents() requires onAsk/onSaved/onRemoved/onUnknown callbacks");
        }

        const sse = createSseParser();
        this._sse = sse;

        this._child.stdout?.on("data", (chunk) => {
            sse.feed(chunk.toString("utf8"));
            for (const frame of sse.drain()) {
                this._routeFrame(frame, { onAsk, onSaved, onRemoved, onUnknown });
            }
        });

        this._child.stdout?.once("end", () => {
            sse.end();
            for (const frame of sse.drain()) {
                this._routeFrame(frame, { onAsk, onSaved, onRemoved, onUnknown });
            }
        });

        return this;
    }

    /**
     * POST the user's answer (or expiry decision) to OpenCode's reply
     * route. 204 -> { ok:true }; 404 -> { ok:true, expired:true } and
     * NEVER throws — a late reply after the engine has dropped the ask is
     * already-cancelled and must not surface to the user.
     */
    async reply({ sessionId, requestId, value }) {
        if (!this.baseUrl) {
            throw new Error("[opencode-sidecar] reply() called before spawn()");
        }
        if (typeof sessionId !== "string" || sessionId.length === 0) {
            throw new TypeError("[opencode-sidecar] reply() requires non-empty sessionId");
        }
        if (typeof requestId !== "string" || requestId.length === 0) {
            throw new TypeError("[opencode-sidecar] reply() requires non-empty requestId");
        }

        // The wire enum maps cleanly:
        //   value === true   -> "once"   (this ask only)
        //   value === false  -> "reject" (the user said no)
        //   value === "always" -> "always" (saved approval; never emitted
        //                            from the chat UI today but supported
        //                            for forward-compat)
        let reply;
        if (value === true) reply = "once";
        else if (value === false) reply = "reject";
        else if (value === "always") reply = "always";
        else throw new TypeError("[opencode-sidecar] reply() value must be true | false | 'always'");

        let resp;
        try {
            resp = await this._httpRequest({
                method: "POST",
                url: `${this.baseUrl}${REPLY_PATH(sessionId, requestId)}`,
                body: { reply },
                timeoutMs: 5_000,
            });
        } catch (err) {
            // Transport-level failure on a reply is NOT idempotent — the
            // engine never saw our answer. Surface it as a non-ok result
            // so the caller can decide what to do (re-try? mark error?).
            return { ok: false, status: 0, error: err.message };
        }

        if (resp.status === 204) return { ok: true };
        if (resp.status === 404) return { ok: true, expired: true };
        return { ok: false, status: resp.status, body: resp.body };
    }

    /**
     * D7 fallback: snapshot the engine's pending permissions so the caller
     * can detect a vanished request that never produced a
     * `permission.removed` frame.
     */
    async listPendingPermissions() {
        if (!this.baseUrl) {
            throw new Error("[opencode-sidecar] listPendingPermissions() called before spawn()");
        }
        const resp = await this._httpRequest({
            method: "GET",
            url: `${this.baseUrl}${PERMISSIONS_SNAPSHOT_PATH}`,
            timeoutMs: 5_000,
        });
        if (resp.status !== 200) {
            throw new Error(`[opencode-sidecar] GET ${PERMISSIONS_SNAPSHOT_PATH} -> status=${resp.status}`);
        }
        try {
            const parsed = JSON.parse(resp.body);
            return Array.isArray(parsed) ? parsed : [];
        } catch {
            return [];
        }
    }

    /**
     * Tear the sidecar down. Sends SIGTERM first; if the child has not
     * exited within `sigtermMs`, escalates to SIGKILL. Resolves once the
     * child has closed. Idempotent: calling shutdown twice is a no-op.
     */
    async shutdown({ sigtermMs = 2_000 } = {}) {
        const child = this._child;
        if (!child) return;

        // Best-effort teardown. Race the close event against the SIGTERM
        // budget; on timeout, SIGKILL. We do NOT await kill() — it returns
        // synchronously and only signals the OS.
        const closed = new Promise((resolve) => {
            child.once("close", () => resolve(true));
        });

        try {
            child.kill("SIGTERM");
        } catch (err) {
            this._log(`SIGTERM failed (already dead?): ${err.message}`);
        }

        const exited = await Promise.race([
            closed,
            delay(sigtermMs).then(() => false),
        ]);

        if (!exited) {
            this._log(`child did not exit within ${sigtermMs}ms; escalating to SIGKILL`);
            try { child.kill("SIGKILL"); } catch (err) {
                this._log(`SIGKILL failed: ${err.message}`);
            }
            // Wait for the close that SIGKILL will trigger. We bound it
            // so a wedged OS does not hang the server's shutdown path.
            await Promise.race([
                closed,
                delay(1_000).then(() => undefined),
            ]);
        }

        this._child = null;
        this._sse = null;
    }

    // -- Internals ----------------------------------------------------------

    _routeFrame(frame, { onAsk, onSaved, onRemoved, onUnknown }) {
        const { event, data } = frame;
        let parsed = null;
        try {
            parsed = data.length > 0 ? JSON.parse(data) : null;
        } catch {
            // The wire is JSON for every event type we care about. A parse
            // failure on a known event type is a regression; an unknown
            // event with garbage is just an unknown event.
            if (event === "permission.asked" || event === "permission.v2.asked"
                || event === "question.asked" || event === "permission.saved"
                || event === "permission.removed") {
                this._log(`event ${event} payload is not valid JSON: ${data.slice(0, 200)}`);
                onUnknown({ type: event, raw: data });
            } else {
                onUnknown({ type: event, raw: data });
            }
            return;
        }

        switch (event) {
            case "permission.asked":
            case "permission.v2.asked": {
                const requestId = parsed?.id || parsed?.requestID;
                const sessionId = parsed?.sessionID || parsed?.sessionId;
                if (!requestId || !sessionId) {
                    this._log(`${event} missing id/sessionID; passing to onUnknown`);
                    onUnknown({ type: event, raw: data });
                    return;
                }
                if (this._seen.has(requestId)) return;
                this._seen.add(requestId);
                onAsk({
                    method: "confirm",
                    requestId,
                    sessionId,
                    deadlineMs: UI_REQUEST_DEADLINE_MS,
                    message: typeof parsed.permission === "string" ? parsed.permission : "",
                });
                return;
            }

            case "question.asked": {
                const requestId = parsed?.id || parsed?.requestID;
                const sessionId = parsed?.sessionID || parsed?.sessionId;
                if (!requestId || !sessionId) {
                    this._log(`question.asked missing id/sessionID; passing to onUnknown`);
                    onUnknown({ type: event, raw: data });
                    return;
                }
                if (this._seen.has(requestId)) return;
                this._seen.add(requestId);
                const rawOptions = Array.isArray(parsed?.options) ? parsed.options : [];
                const options = rawOptions.map((o) => {
                    if (typeof o === "string") return o;
                    if (o && typeof o === "object" && typeof o.label === "string") return o.label;
                    return String(o ?? "");
                });
                onAsk({
                    method: "select",
                    requestId,
                    sessionId,
                    deadlineMs: UI_REQUEST_DEADLINE_MS,
                    message: typeof parsed.question === "string" ? parsed.question : "",
                    options,
                });
                return;
            }

            case "permission.saved": {
                onSaved({
                    requestId: parsed?.requestID || parsed?.id || "",
                    reply: parsed?.reply || "always",
                });
                return;
            }

            case "permission.removed": {
                onRemoved({
                    requestId: parsed?.requestID || parsed?.id || "",
                });
                return;
            }

            default:
                onUnknown({ type: event, raw: data });
                return;
        }
    }
}

// ---------------------------------------------------------------------------
// Public export — `__testing` for white-box unit checks. Not part of the
// runtime contract; production code MUST NOT import from __testing.
// ---------------------------------------------------------------------------

export const __testing = Object.freeze({
    createSseParser,
    defaultHttpRequest,
    defaultReadConfig,
    evaluatePermissionPrecondition,
    REPLY_PATH,
    HEALTH_PATH,
    PERMISSIONS_SNAPSHOT_PATH,
    SENSITIVE_PERMISSION_KEYS,
    SERVE_ARGV,
    DEFAULT_OPENCODE_CONFIG_PATH,
});
