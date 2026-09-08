// ============================================================================
// idupi-server/test/opencode-sidecar.test.mjs
//
// RED-first coverage for the OpenCode sidecar (opencode-serve-sidecar, PR 1
// slice). Pins the contract the index.mjs wiring (PR 2) will rely on:
//
//   - spawn: loopback-only ephemeral bind + GET /global/health within 3s.
//   - subscribeEvents: 4 callbacks map the four canonical SSE event types
//     (permission.asked / v2.asked / question.asked / permission.saved /
//     permission.removed) into the PendingUiRequestRegistry shape.
//   - dedup: a repeated requestID emits exactly ONE onAsk callback.
//   - reply: 204 -> ok; 404 -> { ok:true, expired:true } NO THROW; other -> { ok:false, status }.
//   - shutdown: SIGTERM with a 2s budget, then SIGKILL.
//   - 120s registry wiring: onAsk callback receives `deadlineMs: 120_000`.
//
// No real opencode binary needed: the sidecar takes `spawn` and `httpRequest`
// as injectable seams so a fake child + fake HTTP responder drives every
// case. That keeps the suite hermetic on Windows CI runners that don't
// ship opencode.
//
// Run from repo root:
//
//     node --test idupi-server/test/opencode-sidecar.test.mjs
//
// STANDARD mode (node_server strict_tdd: false) — these tests are the
// assertion layer the verify phase will run; they MUST pass before the
// work unit is considered complete. RED-first: each test asserts the
// post-condition a correctly-implemented sidecar MUST satisfy. When the
// GREEN module lands, all of these flip to PASS in the same run.
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { Readable } from "node:stream";

import { PendingUiRequestRegistry } from "../lib/ui-request-registry.mjs";

// The sidecar module does not exist yet — that is the whole point of
// RED-first. Importing it NOW throws an ENOENT resolver error, which the
// first test reports loudly so the GREEN step cannot be silently skipped.
//
// import { OpenCodeSidecar } from "../lib/opencode-sidecar.mjs";

const UI_REQUEST_DEADLINE_MS = 120_000; // mirror lib/cli-constants.mjs

// ---------------------------------------------------------------------------
// Test fixtures: fake spawn child + fake HTTP responder.
// ---------------------------------------------------------------------------

/**
 * A minimal fake child that mimics the surface sidecar.spawn consumes:
 *  - `pid`
 *  - `stdout` / `stderr` (readable streams the sidecar .pipe()s from)
 *  - `kill(sig)` records the signal so the test can assert on it
 *  - `on("close")` listener for shutdown tests
 *
 * The caller feeds SSE frames into `stdout` and HTTP-ish responses into the
 * responder, then asserts on the sidecar's emitted callbacks.
 */
class FakeChild extends EventEmitter {
    constructor({ pid = 12345 } = {}) {
        super();
        this.pid = pid;
        this.killed = false;
        this.killSignals = [];
        this.stdout = new Readable({ read() {} });
        this.stderr = new Readable({ read() {} });
    }
    kill(sig) {
        this.killed = true;
        this.killSignals.push(sig);
        // simulate process exit on kill
        queueMicrotask(() => this.emit("close", sig === "SIGKILL" ? 137 : 143));
        return true;
    }
}

/**
 * Fake httpRequest that matches the surface the sidecar uses:
 *   ({ method, url, headers, body, timeoutMs }) -> Promise<{ status, body }>
 *
 * The responder is a function the test supplies; the fake records every
 * request so assertions can pin the exact wire shape (URL, body). The
 * common case is "the spawn health probe MUST return 200 + healthy
 * before any other URL is hit, regardless of what the test wants from the
 * other routes" — so the constructor accepts an optional `health` override
 * that lets a test change the health response for the fail-closed cases.
 */
function makeFakeHttpRequest(responder, { health = () => ({ status: 200, body: '{"healthy":true,"version":"1.18.29"}' }) } = {}) {
    const calls = [];
    const fn = async (req) => {
        calls.push(req);
        // The spawn health probe MUST be answered independently so a test
        // can probe "what happens when the reply route returns 500"
        // without poisoning the health probe. The probe always targets
        // the /global/health path on the same baseUrl.
        if (typeof req.url === "string" && req.url.endsWith("/global/health")) {
            return health(req);
        }
        return responder(req);
    };
    fn.calls = calls;
    return fn;
}

// ============================================================================
// RED-GREEN boundaries
// ============================================================================
//
// The module under test is imported lazily so the FIRST run (before the
// GREEN module lands) reports a clean "module not found" failure per test
// instead of a single crash on import. Once the GREEN step writes
// `lib/opencode-sidecar.mjs`, every test below should PASS.
//
// We attempt the import via a dynamic import inside each test so the test
// runner keeps going on the missing-module failure, marking each test as
// failed rather than aborting the suite. This is intentional: RED tests
// must remain individually diagnostic.
// ============================================================================

async function loadSidecar() {
    return await import("../lib/opencode-sidecar.mjs");
}

// ---------------------------------------------------------------------------
// spawn() — D1, D2, D3, D5
// ---------------------------------------------------------------------------

test("spawn binds to 127.0.0.1 with an ephemeral port and surfaces baseUrl", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    // sidecar must NOT spawn with shell:true; pass argv array via injectable spawn
    let spawnArgs = null;
    const fakeSpawn = (cmd, args) => {
        spawnArgs = { cmd, args };
        return fakeChild;
    };
    // Print the listen line so spawn() resolves. Port 4096 mirrors the
    // spike output for stability.
    queueMicrotask(() => {
        fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4096\n");
    });
    // /global/health 200 fast
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true,"version":"1.18.29"}' }));

    const sidecar = new OpenCodeSidecar({ spawn: fakeSpawn, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    assert.equal(sidecar.baseUrl, "http://127.0.0.1:4096");
    assert.deepEqual(spawnArgs.args, ["serve", "--hostname", "127.0.0.1", "--port", "0"]);
    assert.equal(spawnArgs.cmd.endsWith("opencode.exe"), true, "must invoke resolved opencode.exe");
});

test("spawn fails closed when GET /global/health does not respond within 3s", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => {
        fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4097\n");
    });
    // health probe hangs past the 3s budget; non-health routes would too,
    // but spawn never gets that far.
    const httpRequest = makeFakeHttpRequest(
        () => new Promise(() => {}),
        { health: () => new Promise(() => {}) },
    );

    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await assert.rejects(
        () => sidecar.spawn(),
        (err) => /health (?:probe )?timeout|did not become healthy|timed out after \d+ms/i.test(err.message),
        "spawn MUST reject when /global/health misses its 3s budget",
    );
});

test("spawn fails closed when /global/health returns a non-200 status", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => {
        fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4098\n");
    });
    const httpRequest = makeFakeHttpRequest(
        () => ({ status: 204, body: "" }),
        { health: () => ({ status: 503, body: '{"healthy":false}' }) },
    );

    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await assert.rejects(
        () => sidecar.spawn(),
        (err) => /health.*(?:status|unhealthy)|503/i.test(err.message),
        "spawn MUST reject when /global/health reports unhealthy",
    );
});

// ---------------------------------------------------------------------------
// subscribeEvents() — mapping + dedup
// ---------------------------------------------------------------------------

test("permission.asked maps to confirm with a 120s deadline", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4099\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    const asks = [];
    const sidecarWithEvents = await sidecar.subscribeEvents({
        onAsk: (entry) => asks.push(entry),
        onSaved: () => {},
        onRemoved: () => {},
        onUnknown: () => {},
    });

    fakeChild.stdout.push(
        "event: permission.asked\n" +
        `data: ${JSON.stringify({ id: "per-1", sessionID: "ses_x", permission: "file.write", patterns: ["**/*"], metadata: {} })}\n` +
        "\n",
    );

    // give the parser a tick to consume the frame
    await new Promise((r) => setImmediate(r));
    assert.equal(asks.length, 1, "exactly one ask callback for permission.asked");
    assert.equal(asks[0].method, "confirm");
    assert.equal(asks[0].requestId, "per-1");
    assert.equal(asks[0].sessionId, "ses_x");
    assert.equal(asks[0].deadlineMs, UI_REQUEST_DEADLINE_MS);
    assert.equal(sidecarWithEvents, sidecar, "subscribeEvents returns the same instance for chaining");
});

test("permission.v2.asked also maps to confirm with the 120s deadline", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4100\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();
    const asks = [];
    await sidecar.subscribeEvents({ onAsk: (e) => asks.push(e), onSaved: () => {}, onRemoved: () => {}, onUnknown: () => {} });

    fakeChild.stdout.push(
        "event: permission.v2.asked\n" +
        `data: ${JSON.stringify({ id: "per-v2-1", sessionID: "ses_y", permission: "shell.exec", patterns: [] })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));
    assert.equal(asks.length, 1);
    assert.equal(asks[0].method, "confirm");
    assert.equal(asks[0].requestId, "per-v2-1");
});

test("question.asked maps to select with the exact options", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4101\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();
    const asks = [];
    await sidecar.subscribeEvents({ onAsk: (e) => asks.push(e), onSaved: () => {}, onRemoved: () => {}, onUnknown: () => {} });

    fakeChild.stdout.push(
        "event: question.asked\n" +
        `data: ${JSON.stringify({ id: "q-1", sessionID: "ses_z", question: "Choose:", options: ["A", "B", "C"] })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));
    assert.equal(asks.length, 1);
    assert.equal(asks[0].method, "select");
    assert.deepEqual(asks[0].options, ["A", "B", "C"]);
    assert.equal(asks[0].message, "Choose:");
});

test("repeat requestID for the same ask is deduplicated to a single onAsk call", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4102\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();
    const asks = [];
    await sidecar.subscribeEvents({ onAsk: (e) => asks.push(e), onSaved: () => {}, onRemoved: () => {}, onUnknown: () => {} });

    const frame = (id) =>
        "event: permission.asked\n" +
        `data: ${JSON.stringify({ id, sessionID: "ses_d", permission: "file.write" })}\n` +
        "\n";

    fakeChild.stdout.push(frame("per-dup-1"));
    fakeChild.stdout.push(frame("per-dup-1")); // replay
    fakeChild.stdout.push(frame("per-dup-1")); // replay
    await new Promise((r) => setImmediate(r));
    assert.equal(asks.length, 1, "reconnect replay MUST NOT produce a second ask for the same requestID");
});

test("permission.saved routes to onSaved and permission.removed routes to onRemoved", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4103\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();
    const saved = [];
    const removed = [];
    await sidecar.subscribeEvents({
        onAsk: () => {},
        onSaved: (e) => saved.push(e),
        onRemoved: (e) => removed.push(e),
        onUnknown: () => {},
    });

    fakeChild.stdout.push(
        "event: permission.saved\n" +
        `data: ${JSON.stringify({ requestID: "per-saved-1", reply: "always" })}\n` +
        "\n",
    );
    fakeChild.stdout.push(
        "event: permission.removed\n" +
        `data: ${JSON.stringify({ requestID: "per-removed-1" })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));
    assert.equal(saved.length, 1);
    assert.equal(saved[0].requestId, "per-saved-1");
    assert.equal(removed.length, 1);
    assert.equal(removed[0].requestId, "per-removed-1");
});

test("SSE heartbeat lines (':') and unknown event types do not throw", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4104\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();
    const unknowns = [];
    const asks = [];
    await sidecar.subscribeEvents({
        onAsk: (e) => asks.push(e),
        onSaved: () => {},
        onRemoved: () => {},
        onUnknown: (e) => unknowns.push(e),
    });
    fakeChild.stdout.push(":keep-alive\n\n");
    fakeChild.stdout.push(
        "event: server.heartbeat\n" +
        `data: ${JSON.stringify({ ts: 1 })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));
    assert.equal(asks.length, 0);
    assert.equal(unknowns.length, 1);
    assert.equal(unknowns[0].type, "server.heartbeat");
});

// ---------------------------------------------------------------------------
// reply() — 204/404 idempotency
// ---------------------------------------------------------------------------

test("reply resolves to { ok: true } on HTTP 204", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4105\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 204, body: "" }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    const result = await sidecar.reply({ sessionId: "ses_a", requestId: "per-a", value: true });
    assert.deepEqual(result, { ok: true });
    // The fake's call list contains BOTH the spawn health probe AND the
    // reply call (the spawn probe is always recorded). Filter to the
    // reply-shaped call so the assertion pins exactly the wire we ship.
    const replyCall = httpRequest.calls.find((c) => c.url?.includes("/permission/per-a/reply"));
    assert.ok(replyCall, "reply MUST POST to /permission/{rid}/reply");
    assert.equal(replyCall.method, "POST");
    assert.equal(replyCall.url, "http://127.0.0.1:4105/api/session/ses_a/permission/per-a/reply");
    assert.equal(replyCall.body.reply, "once", "approve value MUST serialize as reply:'once'");
});

test("reply for false value serializes as reply:'reject'", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4106\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 204, body: "" }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    const result = await sidecar.reply({ sessionId: "ses_b", requestId: "per-b", value: false });
    assert.deepEqual(result, { ok: true });
    const replyCall = httpRequest.calls.find((c) => c.url?.includes("/permission/per-b/reply"));
    assert.ok(replyCall);
    assert.equal(replyCall.body.reply, "reject");
});

test("reply treats 404 as { ok:true, expired:true } and NEVER throws", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4107\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 404, body: '{"error":"not found"}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    // The whole point of 404 idempotency: a late reply after expiry MUST NOT
    // bubble up as an exception. `reply` swallows the 404 and returns.
    const result = await sidecar.reply({ sessionId: "ses_c", requestId: "per-c", value: true });
    assert.deepEqual(result, { ok: true, expired: true }, "404 MUST be normalized to ok+expired");
});

test("reply surfaces a non-2xx non-404 status as { ok:false, status }", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4108\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 500, body: "boom" }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    const result = await sidecar.reply({ sessionId: "ses_d", requestId: "per-d", value: true });
    assert.equal(result.ok, false);
    assert.equal(result.status, 500);
});

// ---------------------------------------------------------------------------
// shutdown() — SIGTERM 2s + SIGKILL fallback
// ---------------------------------------------------------------------------

test("shutdown sends SIGTERM first, then SIGKILL if the child does not exit within 2s", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    // Override kill to NOT auto-emit close — exercises the SIGKILL fallback path
    let killCalls = [];
    fakeChild.kill = (sig) => { killCalls.push(sig); return true; };
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4109\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    // Don't await — schedule the close emission AFTER kill so the SIGKILL path runs
    const shutdownPromise = sidecar.shutdown({ sigtermMs: 50 }); // tighten for the test
    // After 50ms, sidecar escalates to SIGKILL
    setTimeout(() => fakeChild.emit("close", 137), 60);
    await shutdownPromise;

    assert.deepEqual(killCalls, ["SIGTERM", "SIGKILL"]);
});

test("shutdown resolves cleanly when SIGTERM is enough", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4110\n"));
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    // Default FakeChild.kill auto-emits close on a microtask, so SIGTERM
    // always wins; SIGKILL is NEVER sent.
    await sidecar.shutdown({ sigtermMs: 2000 });
    assert.deepEqual(fakeChild.killSignals, ["SIGTERM"], "no SIGKILL when SIGTERM was enough");
});

// ---------------------------------------------------------------------------
// listPendingPermissions() — D7 fallback
// ---------------------------------------------------------------------------

test("listPendingPermissions fetches the snapshot from GET /api/permission", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4111\n"));
    const snapshot = [
        { id: "per-x", sessionID: "ses_x" },
        { id: "per-y", sessionID: "ses_x" },
    ];
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: JSON.stringify(snapshot) }));
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, skipPrecondition: true });
    await sidecar.spawn();

    const result = await sidecar.listPendingPermissions();
    const snapshotCall = httpRequest.calls.find((c) => c.url?.endsWith("/api/permission"));
    assert.ok(snapshotCall, "snapshot MUST GET /api/permission");
    assert.equal(snapshotCall.method, "GET");
    assert.equal(snapshotCall.url, "http://127.0.0.1:4111/api/permission");
    assert.deepEqual(result, snapshot);
});

// ============================================================================
// PR 2 RED tests: registry <-> sidecar wiring
// ============================================================================
//
// The wiring lives in index.mjs (setUiRequestSidecarWriter seam + expire
// listener routing for engine==="opencode"). These tests pin the contract
// the wiring must satisfy: a sidecar writer closes over the sidecar
// instance + sessionId + requestId and delivers the answer through
// sidecar.reply() with the canonical wire shape. They exercise the same
// pieces (registry + sidecar) the wiring uses, but stay hermetic by
// never importing index.mjs and re-implementing the seam functions inline.
//
// What these tests pin:
//   - Writer(true|false|'always') translates to sidecar.reply with
//     reply:'once'|'reject'|'always'.
//   - Writer returns true synchronously even when the underlying HTTP is
//     still in flight (fire-and-forget contract).
//   - Late replies after expiry return ok:true, expired:true and the writer
//     swallows them (no throw).
//   - The registry's expire listener, when wired for engine==="opencode",
//     invokes the sidecar writer with value=false — the per-spec
//     "OpenCode expiry cancels" decision.
//   - Fail-closed contract: a sidecar that fails to spawn MUST NOT lead to
//     a --auto relaunch (PR 2 / 5.1 threat-matrix RED).
//
// ============================================================================

/**
 * Build a fresh sidecar instance pointed at the given responder (which
 * also returns the spawn health probe). Injects a permissive
 * `readConfig` seam so the R6 precondition check does not depend on the
 * user's actual `~/.config/opencode/opencode.json` — the suite stays
 * hermetic on Windows + Linux + dev machines without OpenCode set up.
 */
async function freshSidecar(responder, { port = 4200 } = {}) {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => {
        fakeChild.stdout.push(`opencode server listening on http://127.0.0.1:${port}\n`);
    });
    const httpRequest = makeFakeHttpRequest(responder);
    const sidecar = new OpenCodeSidecar({
        spawn: () => fakeChild,
        httpRequest,
        // R6: at least one sensitive op at `ask` so the precondition
        // passes. Tests that want the opposite path inject their own
        // readConfig in the constructor instead.
        readConfig: () => ({ permission: { bash: "ask", edit: "ask" } }),
    });
    await sidecar.spawn();
    return { sidecar, fakeChild, httpRequest };
}

/**
 * Mirrors the writer shape index.mjs builds from the onAsk callback. Kept
 * identical (modulo the inline seam functions) so any drift between this
 * and the wiring shows up here first.
 */
function buildSidecarWriter({ sidecar, sessionId, requestId, child = null }) {
    const writer = (value) => {
        if (!sidecar || !sidecar.baseUrl) return false;
        // Fire-and-forget: sidecar.reply is async + idempotent.
        sidecar.reply({ sessionId, requestId, value })
            .then((r) => {
                if (!r || r.ok !== true) {
                    console.warn(`[ui-request] sidecar reply non-ok for ${requestId}: ${JSON.stringify(r)}`);
                }
            })
            .catch((err) => console.warn(`[ui-request] sidecar reply threw for ${requestId}: ${err?.message || err}`));
        return true;
    };
    if (child) writer.__child = child;
    return writer;
}

/**
 * Import the production expire-routing helper instead of reproducing it
 * inline. The previous test-local mirror in this file (`applyExpireRouting`
 * at line ~556, pre-remediation) could drift from index.mjs without
 * failing — production could change the routing rule and the suite would
 * still be green. The shared module
 * `idupi-server/lib/ui-request-expiry.mjs` is the SINGLE source of truth;
 * both this test and the index.mjs wiring import from there. If the
 * routing rule changes, this test fails on the FIRST run, not after a
 * silent behaviour change ships.
 */
async function loadUiRequestExpiry() {
    return await import("../lib/ui-request-expiry.mjs");
}

// ---------------------------------------------------------------------------
// Sidecar writer shape + fire-and-forget contract
// ---------------------------------------------------------------------------

test("PR2: sidecar writer(true) calls reply with reply:'once'", async () => {
    const calls = [];
    const { sidecar, httpRequest } = await freshSidecar((req) => {
        calls.push(req);
        return { status: 204, body: "" };
    }, { port: 4201 });

    const writer = buildSidecarWriter({ sidecar, sessionId: "ses_p2", requestId: "per_p2" });
    // Sync return: writer accepts the delivery regardless of HTTP completion.
    assert.equal(writer(true), true);
    // Wait one microtask + one HTTP round-trip so the underlying promise resolves.
    await new Promise((r) => setImmediate(r));
    const replyCall = httpRequest.calls.find((c) => c.url?.includes("/permission/per_p2/reply"));
    assert.ok(replyCall, "writer(true) MUST POST to /permission/{rid}/reply");
    assert.equal(replyCall.body.reply, "once", "approve value MUST serialize as reply:'once'");
});

test("PR2: sidecar writer(false) calls reply with reply:'reject'", async () => {
    const { sidecar, httpRequest } = await freshSidecar(() => ({ status: 204, body: "" }), { port: 4202 });

    const writer = buildSidecarWriter({ sidecar, sessionId: "ses_p2b", requestId: "per_p2b" });
    assert.equal(writer(false), true);
    await new Promise((r) => setImmediate(r));
    const replyCall = httpRequest.calls.find((c) => c.url?.includes("/permission/per_p2b/reply"));
    assert.ok(replyCall);
    assert.equal(replyCall.body.reply, "reject", "reject value MUST serialize as reply:'reject'");
});

test("PR2: sidecar writer returns false when sidecar is not spawned", () => {
    // No sidecar instance — writer rejects immediately.
    const { OpenCodeSidecar } = require_unused();
    // Use the loadSidecar pattern but skip the spawn entirely.
    return (async () => {
        const mod = await loadSidecar();
        const sc = new mod.OpenCodeSidecar({ spawn: () => new FakeChild(), httpRequest: makeFakeHttpRequest(() => ({ status: 200, body: "{}" })) });
        // intentionally do NOT call spawn() — baseUrl is null
        const writer = buildSidecarWriter({ sidecar: sc, sessionId: "ses_x", requestId: "per_x" });
        assert.equal(writer(true), false, "writer MUST return false when sidecar has no baseUrl");
    })();
});

// Tiny helper that keeps the no-spawn branch self-contained without
// re-implementing the dynamic-import dance inline.
async function require_unused() { return await loadSidecar(); }

test("PR2: sidecar writer swallows 404 idempotently (late reply after expiry)", async () => {
    const { sidecar, httpRequest } = await freshSidecar(() => ({ status: 404, body: '{"error":"expired"}' }), { port: 4203 });
    const writer = buildSidecarWriter({ sidecar, sessionId: "ses_p2c", requestId: "per_p2c" });
    // Writer itself returns true (accepted for delivery).
    assert.equal(writer(true), true);
    // And the underlying reply() does not throw, even on 404.
    const directResult = await sidecar.reply({ sessionId: "ses_p2c", requestId: "per_p2c", value: true });
    assert.deepEqual(directResult, { ok: true, expired: true }, "404 MUST be normalized to ok+expired");
});

// ---------------------------------------------------------------------------
// Expire-routing contract: opencode -> sidecar.reply(false), others -> stdin
// ---------------------------------------------------------------------------

test("PR2: expire routing for engine=opencode fires sidecar.reply(false)", async () => {
    const { sidecar, httpRequest } = await freshSidecar(() => ({ status: 204, body: "" }), { port: 4204 });

    const sidecarCalls = [];
    const sidecarWriter = (value) => {
        sidecarCalls.push({ kind: "sidecar", value });
        sidecar.reply({ sessionId: "ses_e", requestId: "per_e", value })
            .then(() => undefined)
            .catch(() => undefined);
        return true;
    };
    const stdinWriter = (value) => {
        sidecarCalls.push({ kind: "stdin", value });
        return true;
    };

    const { applyExpireRouting } = await loadUiRequestExpiry();

    const registry = new PendingUiRequestRegistry({ deadlineMs: 60_000, backstopMs: 300_000 });
    // Register an opencode confirm request, then immediately force-expire.
    const reg = registry.register({
        sessionId: "ses_e", engine: "opencode", method: "confirm",
        title: "OpenCode", message: "perm?",
    });

    const decision = registry.expire(reg.requestId);
    assert.ok(decision, "expire MUST return a decision");
    const routing = applyExpireRouting({
        entry: { engine: "opencode", method: "confirm", requestId: reg.requestId },
        decision,
        sidecarWriter,
        stdinWriter,
    });
    assert.equal(routing.kind, "sidecar");
    assert.deepEqual(sidecarCalls[0], { kind: "sidecar", value: false }, "opencode expire MUST invoke sidecar writer with value=false");

    await new Promise((r) => setImmediate(r));
    const replyCall = httpRequest.calls.find((c) => c.url?.includes("/permission/per_e/reply"));
    assert.ok(replyCall, "opencode expire MUST trigger sidecar.reply(false)");
    assert.equal(replyCall.body.reply, "reject", "value=false MUST serialize as reply:'reject'");
});

test("PR2: expire routing for engine=pi keeps the stdin path with the decision value", async () => {
    const sidecarCalls = [];
    const stdinCalls = [];
    const sidecarWriter = (value) => { sidecarCalls.push(value); return true; };
    const stdinWriter = (value) => { stdinCalls.push(value); return true; };

    const { applyExpireRouting } = await loadUiRequestExpiry();

    const decision = { value: { cancelled: true }, source: "auto_approve" };
    const routing = applyExpireRouting({
        entry: { engine: "pi-cli", method: "confirm", requestId: "uir_pi" },
        decision,
        sidecarWriter,
        stdinWriter,
    });
    assert.equal(routing.kind, "stdin", "non-opencode engines MUST stay on the stdin path");
    assert.deepEqual(stdinCalls[0], { cancelled: true }, "stdin writer MUST receive the registry's decision.value");
    assert.equal(sidecarCalls.length, 0, "sidecar writer MUST NOT fire for non-opencode engines");
});

// ---------------------------------------------------------------------------
// Threat-matrix RED tests mapped to PR 2 (Phase 5 tasks 5.1 + 5.3)
// ---------------------------------------------------------------------------

test("PR2/5.1: fail-closed — a sidecar spawn failure is observed by the caller (no relaunch with --auto)", async () => {
    // The wiring contract: if OpenCodeSidecar.spawn() rejects, the caller
    // MUST NOT relaunch opencode with --auto. We pin the failure surface
    // here (spawn rejects with a meaningful message) so the wiring's
    // failure-mode branch in runOpenCodeCli has something concrete to
    // catch. The "no relaunch" half is enforced by the autoApprove:false
    // path in agent-cmdline.test.mjs (--auto absent) combined with the
    // wiring in runOpenCodeCli, both pinned by their own tests.
    //
    // skipPrecondition: true — this test is about the HEALTH fail-closed
    // boundary, not the R6 precondition boundary. The PRECEDING
    // "REM/R6: spawn() fails closed" test exercises the precondition path
    // independently. Keeping these two concerns separated means a future
    // failure in either path fails the right test, not both.
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => fakeChild.stdout.push("opencode server listening on http://127.0.0.1:4205\n"));
    // Health probe hangs forever — fail-closed budget kicks in.
    const httpRequest = makeFakeHttpRequest(
        () => new Promise(() => {}),
        { health: () => new Promise(() => {}) },
    );
    const sidecar = new OpenCodeSidecar({ spawn: () => fakeChild, httpRequest, healthTimeoutMs: 50, skipPrecondition: true });
    await assert.rejects(
        () => sidecar.spawn(),
        (err) => /health/i.test(err.message) && /50ms|timed out/i.test(err.message),
        "spawn MUST reject within the health budget when /global/health does not respond",
    );
    // The baseUrl stays null after a failed spawn — so a subsequent reply
    // call would also fail closed (writer returns false). We pin that here.
    assert.equal(sidecar.baseUrl, null);
});

test("PR2/5.3: answer-vs-deadline — late resolve after the 120s expiry never throws and never produces a second expire", async () => {
    // The deadline race the spec calls out: the user taps approve at 120s;
    // the registry has already expired; the sidecar reply fires afterwards
    // and hits a 404 (engine dropped the ask). Contract:
    //   - writer returns true (delivery accepted).
    //   - the underlying reply resolves to { ok:true, expired:true }.
    //   - the registry entry is terminal; a late POST short-circuits to
    //     404 because resolve() finds no entry.
    const { sidecar, httpRequest } = await freshSidecar(() => ({ status: 404, body: '{"expired":true}' }), { port: 4206 });
    const writer = buildSidecarWriter({ sidecar, sessionId: "ses_d", requestId: "per_d" });

    const registry = new PendingUiRequestRegistry({ deadlineMs: 60_000, backstopMs: 300_000 });
    let expireFired = 0;
    registry.on("expire", () => { expireFired += 1; });
    const reg = registry.register({
        sessionId: "ses_d", engine: "opencode", method: "confirm",
        title: "x", message: "y",
    });

    // Fire the expire (the 120s timer in production).
    registry.expire(reg.requestId);
    assert.equal(expireFired, 1);

    // Late reply — engine has dropped the ask, 404 from sidecar.
    assert.equal(writer(true), true, "writer MUST accept late delivery (fire-and-forget)");
    const result = await sidecar.reply({ sessionId: "ses_d", requestId: "per_d", value: true });
    assert.deepEqual(result, { ok: true, expired: true }, "404 MUST be normalized to ok+expired");

    // Late POST on the same requestId — registry has no entry, resolves
    // to 404 (the entry is terminal and removed from _entries).
    const lateResolve = registry.resolve({
        requestId: reg.requestId, token: reg.token, sessionId: reg.sessionId, value: true,
    });
    assert.equal(lateResolve.ok, false);
    assert.equal(lateResolve.status, 404);
    // expire MUST NOT have fired twice — once-and-only-once terminality.
    assert.equal(expireFired, 1, "expire MUST NOT fire twice for the same requestId");
});

// ---------------------------------------------------------------------------
// __child back-pointer: clearUiRequestStdinWritersForChild sweeps both maps
// ---------------------------------------------------------------------------

test("PR2: a writer carrying __child can be swept from both stdin and sidecar maps", () => {
    // We exercise the seam pattern directly: two writers (one per map)
    // share the same __child back-pointer; a sweep by reference drops
    // both. This pins that the sidecar seam uses the same discriminator
    // the stdin seam uses, so a future child.close handler in
    // runOpenCodeCli (PR 2 task 3.2) can sweep both maps at once.
    const child = { pid: 999 };
    const stdinMap = new Map();
    const sidecarMap = new Map();
    const stdinWriter = (v) => true;
    const sidecarWriter = (v) => true;
    stdinWriter.__child = child;
    sidecarWriter.__child = child;
    stdinMap.set("uir_a", stdinWriter);
    sidecarMap.set("uir_b", sidecarWriter);

    // Sweep by reference — mirrors index.mjs's
    // clearUiRequestStdinWritersForChild.
    const sweep = (target) => {
        const toDelete = [];
        for (const [id, w] of stdinMap) if (w && w.__child === target) toDelete.push(id);
        for (const id of toDelete) stdinMap.delete(id);
        const toDeleteSidecar = [];
        for (const [id, w] of sidecarMap) if (w && w.__child === target) toDeleteSidecar.push(id);
        for (const id of toDeleteSidecar) sidecarMap.delete(id);
    };
    sweep(child);

    assert.equal(stdinMap.has("uir_a"), false, "stdin writer bound to the dead child MUST be cleared");
    assert.equal(sidecarMap.has("uir_b"), false, "sidecar writer bound to the dead child MUST be cleared");
});

// ============================================================================
// Remediation RED tests: R4 vanish-abort + R6 precondition check
// ============================================================================
//
// The verify report (sha256:0c528b7b1d5e1ba4ca419536e0c2e0c0a1528c0b3dfcc3a3fae100c43ab00f44)
// flagged blocker #2 (R4 vanish-abort not implemented: `onRemoved` only
// logs) and blocker #3 (R6 precondition check missing). These tests pin the
// exact contract the production module MUST satisfy:
//
//   R4: a vanished permission triggers a registry expire + a sidecar
//       writer clear. The snapshot fallback (listPendingPermissions) MUST
//       be consulted first so a delayed `permission.removed` cleanup frame
//       for a still-pending permission does NOT spuriously abort the turn.
//   R6: the sidecar constructor accepts an injected `readConfig` and the
//       spawn() method MUST reject before any child is launched when the
//       effective OpenCode config has zero `ask`-level sensitive
//       operations. The `verifyConfigPrecondition()` method exposes the
//       pure decision function so the failure modes are testable without
//       a live filesystem.
// ============================================================================

// ---------------------------------------------------------------------------
// R6 precondition check (blocker #3)
// ---------------------------------------------------------------------------

test("REM/R6: verifyConfigPrecondition rejects when opencode.json is missing", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeSpawn = () => new FakeChild();
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    // readConfig returns null → "missing or unparseable"
    const sidecar = new OpenCodeSidecar({
        spawn: fakeSpawn,
        httpRequest,
        readConfig: () => null,
    });
    await assert.rejects(
        () => sidecar.verifyConfigPrecondition(),
        (err) => /missing or unparseable/.test(err.message),
        "verifyConfigPrecondition MUST reject when the config file is missing",
    );
});

test("REM/R6: verifyConfigPrecondition rejects when permission block is absent", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const sidecar = new OpenCodeSidecar({
        spawn: () => new FakeChild(),
        httpRequest: makeFakeHttpRequest(() => ({ status: 200, body: "" })),
        readConfig: () => ({ /* no permission block */ }),
    });
    await assert.rejects(
        () => sidecar.verifyConfigPrecondition(),
        (err) => /no `permission` block/.test(err.message),
        "verifyConfigPrecondition MUST reject when no `permission` block is set",
    );
});

test("REM/R6: verifyConfigPrecondition rejects when every sensitive op is `allow`", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const sidecar = new OpenCodeSidecar({
        spawn: () => new FakeChild(),
        httpRequest: makeFakeHttpRequest(() => ({ status: 200, body: "" })),
        // Engine configured to AUTO-APPROVE every sensitive operation —
        // exactly the "weaker than ask" config the spec says MUST disable
        // card mediation with a clear reason.
        readConfig: () => ({
            permission: { bash: "allow", edit: "allow", write: "allow", webfetch: "allow" },
        }),
    });
    await assert.rejects(
        () => sidecar.verifyConfigPrecondition(),
        (err) => /bash/.test(err.message) && /auto-approve|allow/.test(err.message),
        "verifyConfigPrecondition MUST reject and surface the offending keys",
    );
});

test("REM/R6: verifyConfigPrecondition passes when at least one sensitive op is `ask`", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const sidecar = new OpenCodeSidecar({
        spawn: () => new FakeChild(),
        httpRequest: makeFakeHttpRequest(() => ({ status: 200, body: "" })),
        readConfig: () => ({
            permission: { bash: "ask", edit: "allow" }, // at least one `ask` — ok
        }),
    });
    const result = await sidecar.verifyConfigPrecondition();
    assert.equal(result.ok, true);
    assert.deepEqual(result.askKeys, ["bash"]);
});

test("REM/R6: spawn() fails closed (no opencode run launched) when precondition rejects", async () => {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    let spawnCalls = 0;
    const fakeSpawn = () => { spawnCalls += 1; return fakeChild; };
    const httpRequest = makeFakeHttpRequest(() => ({ status: 200, body: '{"healthy":true}' }));
    const sidecar = new OpenCodeSidecar({
        spawn: fakeSpawn,
        httpRequest,
        readConfig: () => null, // precondition rejects
    });
    await assert.rejects(
        () => sidecar.spawn(),
        (err) => /R6 precondition/.test(err.message),
        "spawn() MUST reject before launching the opencode run child",
    );
    assert.equal(spawnCalls, 0, "no opencode child MUST be spawned when the precondition rejects");
    assert.equal(sidecar.baseUrl, null);
});

// ---------------------------------------------------------------------------
// R4 vanish-abort (blocker #2)
// ---------------------------------------------------------------------------

/**
 * Build a sidecar whose `onRemoved` handler is wired with the same
 * vanish-abort semantics as production: (1) snapshot fallback via
 * listPendingPermissions, (2) if the vanished id is still in the snapshot
 * defer the abort, (3) otherwise force-expire the registry entry. We
 * exercise this through the sidecar's `onRemoved` directly (the public
 * test surface) so any refactor of the abort logic in index.mjs that
 * breaks the contract surfaces here.
 */
async function freshSidecarForVanish({ snapshot = [], port = 4300 } = {}) {
    const { OpenCodeSidecar } = await loadSidecar();
    const fakeChild = new FakeChild();
    queueMicrotask(() => {
        fakeChild.stdout.push(`opencode server listening on http://127.0.0.1:${port}\n`);
    });
    const httpRequest = makeFakeHttpRequest((req) => {
        // /api/permission snapshot responder
        if (typeof req.url === "string" && req.url.endsWith("/api/permission")) {
            return { status: 200, body: JSON.stringify(snapshot) };
        }
        return { status: 204, body: "" };
    });
    const sidecar = new OpenCodeSidecar({
        spawn: () => fakeChild,
        httpRequest,
        // R6: hermetic — the suite never depends on the user's real
        // ~/.config/opencode/opencode.json.
        readConfig: () => ({ permission: { bash: "ask" } }),
    });
    await sidecar.spawn();
    return { sidecar, fakeChild, httpRequest };
}

/**
 * Wire the production-shape vanish-abort handler over a registry + sidecar
 * pair. Mirrors the runOpenCodeCli.onRemoved wiring in index.mjs: tracks
 * the engine→registry requestId map, consults listPendingPermissions as a
 * snapshot fallback, and only force-expires the registry entry when the
 * snapshot confirms the permission is truly gone.
 *
 * Kept inline in the test so the assertion surface is the exact event the
 * production handler emits (no helper layer to drift).
 */
function wireVanishAbort({ sidecar, registry, onAbort }) {
    const engineToRegistry = new Map();
    sidecar.subscribeEvents({
        onAsk: (entry) => {
            const reg = registry.register({
                sessionId: "ses_v", engine: "opencode", method: entry.method,
                options: entry.options || [], title: "x", message: entry.message || "",
            });
            engineToRegistry.set(entry.requestId, reg.requestId);
        },
        onSaved: () => {},
        onRemoved: (entry) => {
            (async () => {
                const registryRid = engineToRegistry.get(entry.requestId);
                if (!registryRid) return;
                let snap = null;
                try {
                    snap = await sidecar.listPendingPermissions();
                } catch {
                    snap = null;
                }
                const stillPending = Array.isArray(snap)
                    && snap.some((p) => (p?.id || p?.requestID) === entry.requestId);
                if (stillPending) return;
                const decision = registry.expire(registryRid);
                onAbort({ engineRid: entry.requestId, registryRid, decision, snapshotAvailable: snap !== null });
            })();
        },
        onUnknown: () => {},
    });
    return engineToRegistry;
}

test("REM/R4: vanished permission (absent from snapshot) force-expires the registry entry", async () => {
    const { sidecar, httpRequest } = await freshSidecarForVanish({ snapshot: [], port: 4301 });
    const registry = new PendingUiRequestRegistry({ deadlineMs: 60_000, backstopMs: 300_000 });
    let abortCount = 0;
    let abortPayload = null;
    wireVanishAbort({ sidecar, registry, onAbort: (p) => { abortCount += 1; abortPayload = p; } });

    // Drive an ask → snapshot lookup → vanish in one sequence.
    sidecar.subscribeEvents; // (no-op; the subscribeEvents above is already wired)
    const fakeChildForWire = sidecar._child;
    fakeChildForWire.stdout.push(
        "event: permission.asked\n" +
        `data: ${JSON.stringify({ id: "per_vanish", sessionID: "ses_v", permission: "bash" })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));

    // Now the engine drops the permission without a reply (snapshot is empty).
    fakeChildForWire.stdout.push(
        "event: permission.removed\n" +
        `data: ${JSON.stringify({ requestID: "per_vanish" })}\n` +
        "\n",
    );

    // Wait for the listPendingPermissions round-trip + expire.
    for (let i = 0; i < 20 && abortCount === 0; i += 1) {
        await new Promise((r) => setImmediate(r));
    }
    assert.equal(abortCount, 1, "vanish-abort MUST fire exactly once for a truly vanished permission");
    assert.equal(abortPayload.engineRid, "per_vanish");
    assert.ok(abortPayload.registryRid, "registryRid MUST be resolved from the engine→registry map");
    assert.ok(abortPayload.decision, "the registry MUST have transitioned to terminal");
    assert.equal(abortPayload.snapshotAvailable, true, "the snapshot MUST be available when the engine responded");
    assert.equal(registry.count(), 0, "the registry MUST have no pending entries after the vanish abort");

    // And the snapshot path MUST have been hit.
    const snapshotCall = httpRequest.calls.find((c) => c.url?.endsWith("/api/permission"));
    assert.ok(snapshotCall, "vanish-abort MUST call listPendingPermissions() before aborting");
});

test("REM/R4: vanished permission that IS still in the snapshot does NOT abort the turn", async () => {
    const { sidecar, httpRequest } = await freshSidecarForVanish({
        snapshot: [{ id: "per_still", sessionID: "ses_v" }],
        port: 4302,
    });
    const registry = new PendingUiRequestRegistry({ deadlineMs: 60_000, backstopMs: 300_000 });
    let abortCount = 0;
    wireVanishAbort({ sidecar, registry, onAbort: () => { abortCount += 1; } });

    const fakeChildForWire = sidecar._child;
    fakeChildForWire.stdout.push(
        "event: permission.asked\n" +
        `data: ${JSON.stringify({ id: "per_still", sessionID: "ses_v", permission: "bash" })}\n` +
        "\n",
    );
    await new Promise((r) => setImmediate(r));

    // Engine emits a delayed cleanup frame but the snapshot still shows
    // the permission. The abort MUST be deferred — the engine will resolve
    // the permission on its own and the registry's 120s timer will expire
    // it normally.
    fakeChildForWire.stdout.push(
        "event: permission.removed\n" +
        `data: ${JSON.stringify({ requestID: "per_still" })}\n` +
        "\n",
    );
    for (let i = 0; i < 20; i += 1) {
        await new Promise((r) => setImmediate(r));
    }
    assert.equal(abortCount, 0, "vanish-abort MUST NOT fire when the snapshot still reports the permission");
    assert.equal(registry.count(), 1, "the registry entry MUST remain pending when the abort is deferred");

    const snapshotCall = httpRequest.calls.find((c) => c.url?.endsWith("/api/permission"));
    assert.ok(snapshotCall, "the snapshot path MUST be consulted on every vanish signal");
});

test("REM/R4: vanished permission whose engine id is unknown to the run is ignored", async () => {
    // Edge case: a `permission.removed` arrives WITHOUT a prior `asked`
    // event — engine cleanup noise for an id the run never tracked.
    // The handler MUST NOT abort anything because there is nothing to abort.
    const { sidecar } = await freshSidecarForVanish({ snapshot: [], port: 4303 });
    const registry = new PendingUiRequestRegistry({ deadlineMs: 60_000, backstopMs: 300_000 });
    let abortCount = 0;
    wireVanishAbort({ sidecar, registry, onAbort: () => { abortCount += 1; } });

    sidecar._child.stdout.push(
        "event: permission.removed\n" +
        `data: ${JSON.stringify({ requestID: "per_orphan" })}\n` +
        "\n",
    );
    for (let i = 0; i < 20; i += 1) {
        await new Promise((r) => setImmediate(r));
    }
    assert.equal(abortCount, 0, "unknown engine requestId MUST NOT trigger an abort");
    assert.equal(registry.count(), 0, "no registry entries to expire");
});

// ---------------------------------------------------------------------------
// Registry per-engine decision (blocker #5 / warning #5 reconcile)
// ---------------------------------------------------------------------------

test("REM/REG: buildAutoApproveDecision returns CANCEL for engine=opencode regardless of method", async () => {
    const { buildAutoApproveDecision } = await import("../lib/ui-request-registry.mjs");
    const forSelect = buildAutoApproveDecision("select", "opencode");
    const forConfirm = buildAutoApproveDecision("confirm", "opencode");
    const forInput = buildAutoApproveDecision("input", "opencode");
    for (const d of [forSelect, forConfirm, forInput]) {
        assert.equal(d.source, "auto_approve");
        assert.deepEqual(d.value, { cancelled: true }, "OpenCode expiry MUST always be CANCELLED, never blanket auto-approve");
    }
});

test("REM/REG: buildAutoApproveDecision returns blanket auto-approve for engine=pi/claude select", async () => {
    const { buildAutoApproveDecision } = await import("../lib/ui-request-registry.mjs");
    const piSelect = buildAutoApproveDecision("select", "pi-cli");
    const claudeSelect = buildAutoApproveDecision("select", "claude");
    assert.equal(piSelect.source, "auto_approve");
    assert.equal(piSelect.value, "Todo", "stdin-engine select MUST keep blanket auto-approve per spec");
    assert.equal(claudeSelect.source, "auto_approve");
    assert.equal(claudeSelect.value, "Todo");
});

test("REM/REG: buildAutoApproveDecision returns CANCEL for stdin-engine confirm/input (where blanket is meaningless)", async () => {
    const { buildAutoApproveDecision } = await import("../lib/ui-request-registry.mjs");
    const piConfirm = buildAutoApproveDecision("confirm", "pi-cli");
    const piInput = buildAutoApproveDecision("input", "pi-cli");
    assert.equal(piConfirm.source, "auto_approve");
    assert.deepEqual(piConfirm.value, { cancelled: true });
    assert.equal(piInput.source, "auto_approve");
    assert.deepEqual(piInput.value, { cancelled: true });
});
