// scratch/reply-path-probe.mjs
//
// One-off probe to resolve the open reply-path question for v1.18.29.
// Spawns `opencode serve`, then POSTs to several candidate paths and
// records status + body for each. The path that returns a 4xx shape
// consistent with "permission unknown to this engine" (rather than
// "no such route") is the working one.
//
// Run from repo root:
//   node scratch/reply-path-probe.mjs

import { spawn } from "node:child_process";
import http from "node:http";
import { setTimeout as delay } from "node:timers/promises";
import { resolveOpenCodeExePath } from "../idupi-server/lib/sessions.mjs";

const READY_TIMEOUT_MS = 8_000;
const log = (...args) => console.log("[reply-probe]", ...args);

function httpPostJson(url, payload) {
    return new Promise((resolve, reject) => {
        const body = JSON.stringify(payload);
        const u = new URL(url);
        const req = http.request(
            {
                method: "POST",
                hostname: u.hostname,
                port: u.port,
                path: u.pathname + u.search,
                headers: {
                    "content-type": "application/json",
                    "content-length": Buffer.byteLength(body),
                },
            },
            (res) => {
                let buf = "";
                res.setEncoding("utf8");
                res.on("data", (c) => { buf += c; });
                res.on("end", () => resolve({ status: res.statusCode, body: buf }));
            }
        );
        req.on("error", reject);
        req.write(body);
        req.end();
    });
}

function spawnServe() {
    return new Promise((resolve, reject) => {
        let exe;
        try { exe = resolveOpenCodeExePath(); } catch (e) { reject(e); return; }
        const child = spawn(exe, ["serve", "--hostname", "127.0.0.1", "--port", "0"], {
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
        });
        let port = null;
        let stderrBuf = "";
        let settled = false;
        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            try { child.kill("SIGKILL"); } catch {}
            reject(new Error(`no listen line within ${READY_TIMEOUT_MS}ms; stderr=${stderrBuf.trim() || "<empty>"}`));
        }, READY_TIMEOUT_MS);
        child.stdout.on("data", (c) => {
            const text = c.toString("utf8");
            const m = text.match(/listening on https?:\/\/127\.0\.0\.1:(\d+)/i);
            if (m && !settled) {
                settled = true;
                clearTimeout(timer);
                port = Number(m[1]);
                resolve({ child, port, url: `http://127.0.0.1:${port}` });
            }
        });
        child.stderr.on("data", (c) => { stderrBuf += c.toString("utf8"); });
        child.on("error", (e) => { if (!settled) { settled = true; clearTimeout(timer); reject(e); } });
        child.on("close", (code) => {
            if (!settled) { settled = true; clearTimeout(timer); reject(new Error(`serve exited (${code}) before listen`)); }
        });
    });
}

async function main() {
    log("starting reply-path probe (opencode v1.18.29)");
    let serve;
    try {
        serve = await spawnServe();
    } catch (err) {
        log("FATAL: cannot spawn serve:", err.message);
        process.exit(1);
    }
    log(`serve ready: port=${serve.port}`);

    // Synthesise non-existent ids. We expect 404 (route exists, no permission)
    // OR 404 (no such route) OR 405 (method not allowed). We try multiple
    // paths so we can compare.
    const paths = [
        // Design-intent shape from proposal/design.md
        { name: "design: /api/session/{sid}/permission/{rid}/reply",
          path: `/api/session/syn-sid/permission/syn-rid/reply` },
        // Plausible SDK shape (singular)
        { name: "sdk singular: /api/session/{sid}/permission/{rid}",
          path: `/api/session/syn-sid/permission/syn-rid` },
        // Upstream SDK plural
        { name: "sdk plural: /session/{sid}/permissions/{rid}",
          path: `/session/syn-sid/permissions/syn-rid` },
        // With /api prefix + plural
        { name: "sdk plural api: /api/session/{sid}/permissions/{rid}",
          path: `/api/session/syn-sid/permissions/syn-rid` },
    ];

    const results = [];
    for (const p of paths) {
        const url = `${serve.url}${p.path}`;
        try {
            const r = await httpPostJson(url, { reply: "once" });
            results.push({ name: p.name, path: p.path, status: r.status, body: r.body.slice(0, 240) });
            log(`POST ${url} -> ${r.status} | body=${r.body.slice(0, 240)}`);
        } catch (err) {
            results.push({ name: p.name, path: p.path, status: null, error: err.message });
            log(`POST ${url} -> ERROR ${err.message}`);
        }
    }

    log("=== REPLY-PATH PROBE VERDICT ===");
    // Heuristic: the working route returns a non-404 status for a non-existent
    // permission id (typically 400 because the body shape is wrong, or 405).
    // A route that returns 404 to ALL probes (existent or not) is likely a
    // catch-all 404 — i.e. nothing matched.
    //
    // We pick the path with the lowest status code (most specific to the
    // engine, not a generic 404 catch-all).
    const ranked = [...results].sort((a, b) => {
        const A = a.status ?? 999;
        const B = b.status ?? 999;
        return A - B;
    });
    log("ranked by lowest status:");
    for (const r of ranked) {
        log(`  status=${r.status} | ${r.name}`);
    }
    const verdict = ranked[0];
    log(`VERDICT: lowest status was ${verdict.status} on "${verdict.name}". ` +
        `Sidecar REPLY_PATH will${verdict.status === 404 && ranked.length > 1 && ranked.filter(r => r.status === 404).length === results.length ? " NOT" : ""} follow this shape.`);
    log("If all paths return 404, the engine has no public reply endpoint without a real pending permission — re-probe after issuing a real permission (out of scope for this probe).");

    try { serve.child.kill("SIGTERM"); } catch {}
    await delay(500);
    try { serve.child.kill("SIGKILL"); } catch {}
    process.exit(0);
}

main().catch((err) => {
    log("probe crashed:", err.stack || err.message || err);
    process.exit(1);
});
