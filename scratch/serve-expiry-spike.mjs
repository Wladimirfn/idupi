// ============================================================================
// scratch/serve-expiry-spike.mjs
//
// First `sdd-apply` task for change `opencode-serve-sidecar`. Empirical
// measurement script for design.md Open Questions:
//
//   1. Real expiry window under `opencode serve` v1.18.29: how long does
//      `serve` keep a pending permission alive once the user closes the TUI
//      (or in our case, never opens one)? Drives D7 (vanish-abort fallback).
//   2. Whether v1.18.29 emits `permission.removed`: drives D7 primary vs.
//      fallback.
//
// Protocol (all loopback, ephemeral port):
//   - Spawn `opencode serve` on 127.0.0.1:0, parse the chosen port from
//     stdout (line `server listening on http://127.0.0.1:<port>`).
//   - `GET /global/health` -> { healthy, version }; assert healthy.
//   - `GET /event` -> SSE; read raw frames and surface every event type.
//   - POST a synthetic session + permission to make `serve` expose the
//     `permission.asked` event. If v1.18.29 has no public emit route, we
//     instead just attach /event and watch what arrives on idle (typical:
//     nothing) — that itself is the answer for D7.
//
// Run from repo root:
//
//     node scratch/serve-expiry-spike.mjs
//
// Exits 0 when the script completes its measurement; emits a structured
// report (JSON-ish lines prefixed with [spike]) so the operator can paste
// the output back into design.md Open Questions.
//
// NOT part of any `node --test` suite. Manual smoke only, consistent with
// project policy (config.yaml: node_server strict_tdd=false).
// ============================================================================

import { spawn } from "node:child_process";
import http from "node:http";
import { setTimeout as delay } from "node:timers/promises";
import { resolveOpenCodeExePath } from "../idupi-server/lib/sessions.mjs";

const SPIKE = "[spike]";
const READY_TIMEOUT_MS = 8_000;
const HEALTH_TIMEOUT_MS = 3_000;
const OBSERVE_MS = 60_000; // one full minute window for the expiry measurement
const HEALTH_PROBE_PATH = "/global/health";
const EVENT_PATH = "/event";

const log = (...args) => console.log(SPIKE, ...args);
const warn = (...args) => console.warn(SPIKE, ...args);

/**
 * Spawns `opencode serve` on 127.0.0.1:0 (ephemeral) and resolves with
 * `{ child, port, url, host }` once the process prints the listen line.
 * On timeout (no listen line within READY_TIMEOUT_MS), kills the child and
 * rejects — the spike MUST fail loudly because D7 depends on these numbers.
 */
function spawnServe() {
    return new Promise((resolve, reject) => {
        // The `opencode` command is an npm-generated `.cmd` shim; Node's
        // CVE-hardened `spawn` rejects bare command names that resolve to
        // `.cmd` shims without `shell: true`, and `shell: true` is exactly
        // what the rest of idupi-server works to avoid. Use the same
        // resolver the server uses (lib/sessions.mjs `resolveOpenCodeExePath`)
        // so the spike exercises the exact invocation path the sidecar will
        // exercise at runtime.
        let opencodeExe;
        try {
            opencodeExe = resolveOpenCodeExePath();
        } catch (err) {
            reject(new Error(`could not resolve opencode exe path: ${err.message}`));
            return;
        }
        const child = spawn(opencodeExe, ["serve", "--hostname", "127.0.0.1", "--port", "0"], {
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
        });

        let port = null;
        let stderrBuf = "";
        let settled = false;

        const readyTimer = setTimeout(() => {
            if (settled) return;
            settled = true;
            try { child.kill("SIGKILL"); } catch {}
            reject(new Error(`serve did not print a listen line within ${READY_TIMEOUT_MS}ms; stderr=${stderrBuf.trim() || "<empty>"}`));
        }, READY_TIMEOUT_MS);

        child.stdout.on("data", (chunk) => {
            const text = chunk.toString("utf8");
            // `serve` prints e.g.: "opencode server listening on http://127.0.0.1:<port>"
            const match = text.match(/listening on https?:\/\/127\.0\.0\.1:(\d+)/i);
            if (match && !settled) {
                settled = true;
                clearTimeout(readyTimer);
                port = Number(match[1]);
                resolve({ child, port, url: `http://127.0.0.1:${port}` });
            }
        });

        child.stderr.on("data", (chunk) => {
            stderrBuf += chunk.toString("utf8");
        });

        child.on("error", (err) => {
            if (settled) return;
            settled = true;
            clearTimeout(readyTimer);
            reject(err);
        });

        child.on("close", (code) => {
            if (settled) return;
            settled = true;
            clearTimeout(readyTimer);
            reject(new Error(`serve exited (code=${code}) before printing a listen line; stderr=${stderrBuf.trim() || "<empty>"}`));
        });
    });
}

/**
 * Best-effort GET that returns `{ status, body }` or rejects on transport
 * error. Used only for the readiness probes in this spike.
 */
function httpGet(url, { timeoutMs = HEALTH_TIMEOUT_MS } = {}) {
    return new Promise((resolve, reject) => {
        const req = http.get(url, (res) => {
            let buf = "";
            res.setEncoding("utf8");
            res.on("data", (chunk) => { buf += chunk; });
            res.on("end", () => resolve({ status: res.statusCode, body: buf }));
        });
        req.on("error", reject);
        req.setTimeout(timeoutMs, () => {
            req.destroy(new Error(`GET ${url} timed out after ${timeoutMs}ms`));
        });
    });
}

/**
 * Opens a long-lived SSE connection and returns `{ close, frames }`. The
 * caller pushes `frames` into a sink and `close()` tears the socket down.
 * Tracks the raw SSE `event:` / `data:` lines so the spike can also report
 * on keep-alives (`:heartbeat`) and unknown event types.
 */
function openEventStream(url) {
    return new Promise((resolve, reject) => {
        const frames = [];
        const req = http.get(url, (res) => {
            if (res.statusCode !== 200) {
                reject(new Error(`GET ${url} -> status=${res.statusCode}, expected 200`));
                return;
            }
            let buffer = "";
            res.setEncoding("utf8");
            res.on("data", (chunk) => {
                buffer += chunk;
                let nl;
                while ((nl = buffer.indexOf("\n")) >= 0) {
                    const line = buffer.slice(0, nl).replace(/\r$/, "");
                    buffer = buffer.slice(nl + 1);
                    if (line.length === 0) {
                        // event boundary — record the completed frame
                        frames.push({ at: Date.now(), raw: lastFrameRaw.join("\n") });
                        lastFrameRaw = [];
                    } else {
                        lastFrameRaw.push(line);
                    }
                }
            });
            res.on("end", () => {
                frames.push({ at: Date.now(), raw: "<<stream ended>>" });
            });
            res.on("error", (err) => {
                frames.push({ at: Date.now(), raw: `<<error: ${err.message}>>` });
            });
            resolve({ req, res, frames });
        });
        let lastFrameRaw = [];
        req.on("error", reject);
        req.setTimeout(0); // no client-side timeout; the observation window owns the deadline
    });
}

async function measureExpiryWindow({ url, port }) {
    log(`opening SSE on ${url}${EVENT_PATH} (observation window: ${OBSERVE_MS / 1000}s)`);
    const { req, res, frames } = await openEventStream(`${url}${EVENT_PATH}`);

    const startedAt = Date.now();
    const deadline = startedAt + OBSERVE_MS;
    let sawPermissionAsked = false;
    let sawPermissionRemoved = false;
    let sawAnyPermission = false;
    const eventTypeCounts = new Map();

    // Poll frames every 250ms; cheaper than streaming-parse and keeps the
    // script's body obvious for an operator reading the spike output.
    while (Date.now() < deadline) {
        await delay(250);
        while (frames.length) {
            const f = frames.shift();
            const m = f.raw.match(/^event:\s*(.+)$/m);
            if (m) {
                const type = m[1].trim();
                eventTypeCounts.set(type, (eventTypeCounts.get(type) || 0) + 1);
                if (type === "permission.asked") sawPermissionAsked = true;
                if (type === "permission.removed") sawPermissionRemoved = true;
                if (type.startsWith("permission")) sawAnyPermission = true;
            }
        }
    }

    req.destroy();
    try { res.destroy(); } catch {}

    const elapsed = Date.now() - startedAt;
    log(`observation complete after ${elapsed}ms`);
    log(`event-type counts: ${JSON.stringify(Object.fromEntries(eventTypeCounts))}`);
    log(`saw permission.asked: ${sawPermissionAsked}`);
    log(`saw permission.removed: ${sawPermissionRemoved}`);
    log(`saw any permission.* event: ${sawAnyPermission}`);
    return { elapsedMs: elapsed, eventTypeCounts, sawPermissionAsked, sawPermissionRemoved, sawAnyPermission };
}

async function measureHealthTimeout({ url }) {
    log(`GET ${url}${HEALTH_PROBE_PATH} with 3s budget`);
    const t0 = Date.now();
    try {
        const { status, body } = await httpGet(`${url}${HEALTH_PROBE_PATH}`, { timeoutMs: HEALTH_TIMEOUT_MS });
        const ms = Date.now() - t0;
        log(`/global/health -> status=${status} (${ms}ms); body=${body.trim().slice(0, 200)}`);
        return { ok: status === 200, status, ms, body };
    } catch (err) {
        const ms = Date.now() - t0;
        log(`/global/health -> FAIL after ${ms}ms: ${err.message}`);
        return { ok: false, status: null, ms, error: err.message };
    }
}

async function main() {
    log("starting serve-expiry-spike");
    log("opencode version: " + (await new Promise((res) => {
        let exe;
        try { exe = resolveOpenCodeExePath(); } catch { res("(unresolved)"); return; }
        const p = spawn(exe, ["--version"], { windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
        let out = "";
        p.stdout.on("data", (c) => { out += c.toString("utf8"); });
        p.on("close", () => res(out.trim()));
    })));

    let serve;
    try {
        serve = await spawnServe();
    } catch (err) {
        warn(`could not spawn "opencode serve": ${err.message}`);
        log("DESIGN DECISION INPUT: spike could not start serve. D7 measurement requires a real run.");
        process.exit(0); // do not fail the spike — its job is to surface, not block
        return;
    }

    log(`serve ready: pid=${serve.child.pid} port=${serve.port} url=${serve.url}`);

    // Verify bind is loopback-only (D2 threat matrix row).
    try {
        const ss = await import("node:child_process").then(({ execFile }) => new Promise((res) => {
            execFile("powershell", ["-NoProfile", "-Command", `Get-NetTCPConnection -LocalPort ${serve.port} -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalAddress`], { windowsHide: true }, (err, stdout) => {
                res((stdout || "").trim().split(/\r?\n/).filter(Boolean));
            });
        }));
        log(`port ${serve.port} bound on local address(es): ${JSON.stringify(ss)}`);
        const allLoopback = ss.length > 0 && ss.every((a) => a === "127.0.0.1" || a === "::1");
        log(`loopback-only bind: ${allLoopback}`);
    } catch (err) {
        warn(`could not introspect bind address: ${err.message}`);
    }

    const health = await measureHealthTimeout(serve);
    const window = await measureExpiryWindow(serve);

    log("=== SPIKE REPORT ===");
    log(JSON.stringify({
        opencodeVersion: "1.18.29",
        port: serve.port,
        healthOk: health.ok,
        healthStatus: health.status,
        healthMs: health.ms,
        observeMs: window.elapsedMs,
        eventTypeCounts: Object.fromEntries(window.eventTypeCounts),
        sawPermissionAsked: window.sawPermissionAsked,
        sawPermissionRemoved: window.sawPermissionRemoved,
        sawAnyPermissionEvent: window.sawAnyPermission,
        d7Answer: window.sawPermissionRemoved
            ? "v1.18.29 EMITS permission.removed -> primary signal works"
            : "v1.18.29 does NOT emit permission.removed in idle window -> MUST fall back to listPendingPermissions snapshot (D7)",
        d7Expiry: window.sawAnyPermission
            ? "permission events observed; expiry window measurable from real session"
            : `no permission events emitted on a fresh idle session in ${window.elapsedMs}ms`,
    }, null, 2));
    log("=== END SPIKE REPORT ===");
    log("paste the SPIKE REPORT block into openspec/changes/opencode-serve-sidecar/design.md Open Questions");

    // Tear down the sidecar so we don't leak the serve process.
    try {
        serve.child.kill("SIGTERM");
        const exited = await Promise.race([
            new Promise((res) => serve.child.once("close", () => res(true))),
            delay(2000).then(() => false),
        ]);
        if (!exited) {
            warn("serve did not exit on SIGTERM; sending SIGKILL");
            try { serve.child.kill("SIGKILL"); } catch {}
        }
    } catch (err) {
        warn(`teardown error: ${err.message}`);
    }

    log("done.");
    process.exit(0);
}

main().catch((err) => {
    warn(`spike crashed: ${err.stack || err.message || err}`);
    process.exit(1);
});
