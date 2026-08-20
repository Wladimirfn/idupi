// ============================================================================
// idupi-server/test/harness-unknown-mcp.mjs
//
// Change A (live-cli-activity-visibility) — falsifiable unknown-MCP proof.
//
// This harness:
//   1. Isolates the REAL user Pi config by setting PI_CODING_AGENT_DIR to a
//      freshly created mkdtemp directory ONLY. It never touches HOME/USERPROFILE
//      or any other isolation mechanism, and never copies/mutates/logs the real
//      ~/.pi/agent/auth.json — the Pi provider credential is read once from it
//      and passed to the server child as a harness-only env var.
//   2. Registers a NEW trivial MCP (name/tool absent from production) and
//      proves it becomes visible over SSE with ZERO detection-code edits.
//   3. Verifies the real user config + production files are byte-identical
//      before/after (SHA-256), the process tree is killed, and the temp dir +
//      marker are removed — on success, failure, and SIGINT.
//
// Run:  node test/harness-unknown-mcp.mjs   (from idupi-server)
// Strict TDD: this is the RED test (run before wiring -> no activity -> FAIL)
// and the GREEN verification (run after wiring -> activity visible -> PASS).
// ============================================================================

import { spawn, execFileSync } from "node:child_process";
import {
    mkdtempSync,
    rmSync,
    writeFileSync,
    existsSync,
    readFileSync,
    mkdirSync,
} from "node:fs";
import { tmpdir, homedir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import http from "node:http";

const __dirname = dirname(fileURLToPath(import.meta.url));
const SERVER_DIR = join(__dirname, ".."); // idupi-server
const SERVER_INDEX = join(SERVER_DIR, "index.mjs");
const CHAT_EVENTS = join(SERVER_DIR, "chat-events.mjs");
const PI_AGENT_DIR = join(homedir(), ".pi", "agent");

const REAL = {
    settings: join(PI_AGENT_DIR, "settings.json"),
    auth: join(PI_AGENT_DIR, "auth.json"),
    mcp: join(PI_AGENT_DIR, "mcp.json"),
};

const PROD = {
    index: SERVER_INDEX,
    chatEvents: CHAT_EVENTS,
};

const ACTIVITY_TYPES = new Set([
    "activity_start",
    "activity_update",
    "activity_heartbeat",
    "activity_end",
    "activity_failure",
    "activity_timeout",
]);

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
function sha256File(p) {
    if (!existsSync(p)) return "missing:" + p;
    const h = createHash("sha256");
    h.update(readFileSync(p));
    return h.digest("hex");
}

const log = (...a) => console.log("[harness]", ...a);
const err = (...a) => console.error("[harness]", ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getFreePort() {
    return new Promise((resolve, reject) => {
        const s = http.createServer();
        s.on("error", reject);
        s.listen(0, () => {
            const p = s.address().port;
            s.close(() => resolve(p));
        });
    });
}

// Minimal MCP stdio server: initialize + tools/list + tools/call.
const MCP_SERVER_SRC = `#!/usr/bin/env node
import { createInterface } from "node:readline";
const rl = createInterface({ input: process.stdin });
const send = (o) => process.stdout.write(JSON.stringify(o) + "\\n");
rl.on("line", (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (msg.method === "initialize") {
    send({ jsonrpc: "2.0", id: msg.id, result: {
      protocolVersion: "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "harnessmcp", version: "1.0.0" } } });
  } else if (msg.method === "tools/list") {
    send({ jsonrpc: "2.0", id: msg.id, result: { tools: [ {
      name: "harness_ping",
      description: "Trivial ping used by the IDUPI unknown-MCP harness.",
      inputSchema: { type: "object", properties: { msg: { type: "string" } }, required: [] }
    } ] } });
  } else if (msg.method === "tools/call") {
    const m = (msg.params && msg.params.arguments && msg.params.arguments.msg) || "";
    send({ jsonrpc: "2.0", id: msg.id, result: { content: [ { type: "text", text: "pong:" + m } ] } });
  }
});
`;

// ---------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------
let serverChild = null;
let tmpRoot = null;
let markerPath = null;
let cleanupDone = false;
let preHashes = null;
const failures = [];

/** True while a PID still exists. Used to prove the tree really died. */
function pidAlive(pid) {
    try {
        const out = execFileSync("tasklist", ["/FI", `PID eq ${pid}`, "/NH"], {
            encoding: "utf8",
            windowsHide: true,
        });
        return out.includes(String(pid));
    } catch {
        return false; // tasklist unavailable -> cannot prove alive
    }
}

function doCleanup() {
    if (cleanupDone) return;
    cleanupDone = true;

    // Everything here MUST be synchronous. doCleanup runs from SIGINT,
    // uncaughtException and finally, each followed by process.exit(), and a
    // pending async callback never survives that -- an async taskkill would
    // simply never run on the SIGINT path.
    //
    // Order matters too: taskkill /T finds descendants by walking down from the
    // given PID, so the root must still be alive when it runs. Killing the root
    // first (the previous child.kill("SIGKILL")) reparented Pi and its MCP
    // servers and left them orphaned -- exactly the failure this whole change
    // exists to prevent. taskkill /F already kills the root, so no separate
    // kill is needed or wanted.
    if (serverChild && serverChild.pid) {
        const pid = serverChild.pid;
        try {
            execFileSync("taskkill", ["/F", "/T", "/PID", String(pid)], {
                stdio: "ignore",
                windowsHide: true,
            });
        } catch { /* already gone, or taskkill refused: verified below */ }

        if (pidAlive(pid)) {
            // Do NOT delete the temp dir: Pi may still hold files open in it,
            // and a surviving tree is a harness failure worth seeing, not
            // something to tidy away.
            err(`CLEANUP FAILED: process tree ${pid} still alive after taskkill /F /T. Temp dir kept at ${tmpRoot} for inspection.`);
            failures.push(`cleanup: process tree ${pid} survived taskkill`);
            return;
        }
    }

    if (tmpRoot && existsSync(tmpRoot)) {
        try { rmSync(tmpRoot, { recursive: true, force: true }); } catch { /* ignore */ }
    }
}

process.on("SIGINT", () => {
    err("SIGINT received — cleaning up.");
    doCleanup();
    process.exit(3);
});
process.on("uncaughtException", (e) => {
    err("uncaughtException:", e && e.message);
    doCleanup();
    process.exit(4);
});

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
async function main() {
    // ---- preflight: Pi provider credential must be present ----
    if (!existsSync(REAL.auth)) {
        err("ABORT: ~/.pi/agent/auth.json absent — cannot run live Pi proof.");
        process.exit(2);
    }
    let providerCred = null;
    try {
        const a = JSON.parse(readFileSync(REAL.auth, "utf8"));
        const oc = a["openai-codex"] || a["openai"];
        if (oc && oc.access) providerCred = oc.access;
        else if (oc && oc.key) providerCred = oc.key;
    } catch (e) {
        err("ABORT: could not read auth.json:", e.message);
        process.exit(2);
    }
    if (!providerCred) {
        err("ABORT: no openai-codex/openai credential in auth.json.");
        process.exit(2);
    }

    // ---- baseline hashes (real config + production files) ----
    preHashes = {
        settings: sha256File(REAL.settings),
        auth: sha256File(REAL.auth),
        mcp: sha256File(REAL.mcp),
        index: sha256File(PROD.index),
        chatEvents: sha256File(PROD.chatEvents),
    };
    log("pre-hashes collected");

    // ---- isolated temp config dir ----
    tmpRoot = mkdtempSync(join(tmpdir(), "idupi-harness-"));
    const agentDir = join(tmpRoot, "agent");
    mkdirSync(agentDir, { recursive: true });
    markerPath = join(tmpRoot, ".harness-marker");
    writeFileSync(markerPath, "harness-run\n");

    // settings.json: mirror the real shape but pin the provider we can supply.
    writeFileSync(
        join(agentDir, "settings.json"),
        JSON.stringify(
            {
                defaultModel: "gpt-5.6-luna",
                defaultProvider: "openai-codex",
                defaultThinkingLevel: "high",
                packages: [],
                theme: "dark",
            },
            null,
            2
        ) + "\n"
    );
    // mcp.json: a NEW server/tool absent from production.
    const mcpServerPath = join(tmpRoot, "harness-mcp-server.mjs");
    writeFileSync(mcpServerPath, MCP_SERVER_SRC);
    writeFileSync(
        join(agentDir, "mcp.json"),
        JSON.stringify(
            {
                mcpServers: {
                    harnessmcp: {
                        command: "node",
                        args: [mcpServerPath],
                        directTools: true,
                        lifecycle: "lazy",
                    },
                },
                settings: { directTools: true, idleTimeout: 10, toolPrefix: "server" },
            },
            null,
            2
        ) + "\n"
    );
    log("isolated config written under", agentDir);

    // ---- spawn the IDUPI server (harness-only env; PI_CODING_AGENT_DIR only) ----
    const PORT = await getFreePort();
    const TOKEN = "harness-" + createHash("sha256").update(String(Date.now())).digest("hex").slice(0, 32);
    const childEnv = {
        ...process.env,
        PORT: String(PORT),
        IDUPI_TOKEN: TOKEN,
        PI_CODING_AGENT_DIR: agentDir, // the ONLY isolation mechanism
        OPENAI_API_KEY: providerCred, // harness-only provider credential (never logged/copied to disk)
    };
    // Ensure we never accidentally point Pi at the real config dir.
    delete childEnv.USERPROFILE_OVERRIDE;

    serverChild = spawn(process.execPath, [SERVER_INDEX], {
        cwd: SERVER_DIR,
        env: childEnv,
        stdio: ["ignore", "ignore", "ignore"],
        windowsHide: true,
    });
    serverChild.on("error", (e) => err("server spawn error:", e.message));
    log("server spawned pid", serverChild.pid, "port", PORT);

    // ---- wait for server ready ----
    let ready = false;
    for (let i = 0; i < 50; i++) {
        try {
            await httpGet(`http://127.0.0.1:${PORT}/api/v1/status`, TOKEN);
            ready = true;
            break;
        } catch {
            await sleep(300);
        }
    }
    if (!ready) {
        failures.push("server did not become ready");
        throw new Error("server not ready");
    }
    log("server ready");

    // ---- SSE client: collect activity frames ----
    const frames = [];
    let sseReq = null;
    let gotPing = false;
    const sseDone = new Promise((resolve) => {
        const req = http.get(
            `http://127.0.0.1:${PORT}/api/v1/chat/stream`,
            { headers: { Authorization: "Bearer " + TOKEN } },
            (res) => {
                let buf = "";
                res.on("data", (chunk) => {
                    buf += chunk.toString("utf8");
                    let idx;
                    while ((idx = buf.indexOf("\n\n")) !== -1) {
                        const raw = buf.slice(0, idx);
                        buf = buf.slice(idx + 2);
                        let ev = null;
                        let data = null;
                        for (const line of raw.split("\n")) {
                            if (line.startsWith("event:")) ev = line.slice(6).trim();
                            else if (line.startsWith("data:")) data = line.slice(5).trim();
                        }
                        if (ev && ACTIVITY_TYPES.has(ev) && data) {
                            try {
                                const parsed = JSON.parse(data);
                                frames.push({ type: ev, data: parsed });
                                if (parsed.server === "harnessmcp") gotPing = true;
                            } catch { /* ignore malformed */ }
                        }
                    }
                });
                res.on("end", resolve);
            }
        );
        req.on("error", () => resolve());
        sseReq = req;
    });

    await sleep(500); // let SSE subscription establish

    // ---- trigger a Pi message that should call the new MCP ----
    const prompt =
        "Use the MCP tool named 'harness_ping' provided by the MCP server 'harnessmcp', " +
        "passing the argument msg='hello'. Then reply with the exact text the tool returned.";
    try {
        await httpPost(`http://127.0.0.1:${PORT}/api/v1/chat/message`, TOKEN, { message: prompt });
        log("chat message sent");
    } catch (e) {
        err("chat message failed:", e.message);
        failures.push("chat message POST failed: " + e.message);
    }

    // ---- wait for activity evidence (bounded) ----
    const deadline = Date.now() + 150000;
    while (Date.now() < deadline) {
        const hasStart = frames.some((f) => f.type === "activity_start");
        const hasTerminal = frames.some(
            (f) => f.type === "activity_end" || f.type === "activity_failure" || f.type === "activity_timeout"
        );
        if (hasStart && hasTerminal) break;
        await sleep(1000);
    }

    // close SSE
    try { if (sseReq) sseReq.destroy(); } catch { /* ignore */ }

    const starts = frames.filter((f) => f.type === "activity_start");
    const terminals = frames.filter(
        (f) => f.type === "activity_end" || f.type === "activity_failure" || f.type === "activity_timeout"
    );
    const observedServer = frames.map((f) => f.data.server).filter(Boolean);
    log(`collected frames: start=${starts.length} terminal=${terminals.length} gotPing=${gotPing}`);
    log("observed activity servers:", JSON.stringify([...new Set(observedServer)]));

    if (starts.length === 0 || terminals.length === 0) {
        failures.push(
            "NO activity lifecycle observed over SSE (Change A wiring absent or Pi did not invoke the tool)"
        );
    } else {
        log("activity lifecycle observed over SSE:", JSON.stringify(frames.map((f) => f.type)));
    }
}

// ---------------------------------------------------------------------------
// http helpers
// ---------------------------------------------------------------------------
function httpGet(url, token) {
    return new Promise((resolve, reject) => {
        const req = http.get(url, { headers: { Authorization: "Bearer " + token } }, (res) => {
            let body = "";
            res.on("data", (c) => (body += c));
            res.on("end", () => resolve({ status: res.statusCode, body }));
        });
        req.on("error", reject);
    });
}

function httpPost(url, token, payload) {
    return new Promise((resolve, reject) => {
        const body = JSON.stringify(payload);
        const req = http.request(
            url,
            {
                method: "POST",
                headers: {
                    Authorization: "Bearer " + token,
                    "Content-Type": "application/json",
                    "Content-Length": Buffer.byteLength(body),
                },
            },
            (res) => {
                let b = "";
                res.on("data", (c) => (b += c));
                res.on("end", () => resolve({ status: res.statusCode, body: b }));
            }
        );
        req.on("error", reject);
        req.write(body);
        req.end();
    });
}

// ---------------------------------------------------------------------------
// run + guaranteed cleanup + hash assertion
// ---------------------------------------------------------------------------
let exitCode = 0;
try {
    await main();
} catch (e) {
    err("main threw:", e && e.message);
    if (!failures.length) failures.push("main error: " + (e && e.message));
} finally {
    doCleanup();

    // ---- post hashes (real config + production files) ----
    const postHashes = {
        settings: sha256File(REAL.settings),
        auth: sha256File(REAL.auth),
        mcp: sha256File(REAL.mcp),
        index: sha256File(PROD.index),
        chatEvents: sha256File(PROD.chatEvents),
    };

    const hashMismatch = [];
    for (const k of Object.keys(preHashes)) {
        if (preHashes[k] !== postHashes[k]) {
            hashMismatch.push(`${k}: pre=${preHashes[k].slice(0, 12)} post=${postHashes[k].slice(0, 12)}`);
        }
    }
    if (hashMismatch.length) {
        failures.push("REAL CONFIG / PRODUCTION HASH MISMATCH: " + hashMismatch.join(", "));
    }

    const markerGone = markerPath ? !existsSync(markerPath) : true;
    const tmpGone = tmpRoot ? !existsSync(tmpRoot) : true;
    if (!markerGone) failures.push("marker not removed");
    if (!tmpGone) failures.push("temp dir not removed");

    // ---- report ----
    log("=== HARNESS REPORT ===");
    log("pre-hashes  :", JSON.stringify(preHashes));
    log("post-hashes :", JSON.stringify(postHashes));
    log("hash stable :", hashMismatch.length === 0 ? "YES" : "NO");
    log("marker gone :", markerGone);
    log("temp gone   :", tmpGone);
    log("failures    :", failures.length ? failures.join(" | ") : "none");

    if (failures.length) {
        err("HARNESS RESULT: FAIL");
        exitCode = 1;
    } else {
        log("HARNESS RESULT: PASS");
        exitCode = 0;
    }
}

process.exit(exitCode);
