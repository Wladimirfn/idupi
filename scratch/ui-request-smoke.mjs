// ============================================================================
// scratch/ui-request-smoke.mjs
//
// Phase 6.5 — manual smoke harness for the `fix-ui-request-selection`
// registry + transport contract. Exercises the REAL PendingUiRequestRegistry
// (idupi-server/lib/ui-request-registry.mjs) end-to-end, without starting the
// HTTP server, mirroring how index.mjs uses it:
//
//   1. Register a select request, install a stdin writer that captures the
//      JSON line, resolve with the exact option "A" -> the writer is invoked
//      exactly once with the right payload.
//   2. Register a request and let it expire (deadlineMs: 1) -> the registry
//      emits `expire` with the blanket `Todo` decision (Phase A contract).
//   3. Register two requests for the same session -> the second bumps the
//      monotonic token, so resolving the FIRST with its stale token returns
//      409 (spec scenario: "Stale token rejected").
//
// Run from repo root:
//
//     node scratch/ui-request-smoke.mjs
//
// Exit code 0 on full success; 1 on any failure. Designed to be invoked by
// Phase 6's "auto-chain" delivery as the runtime smoke check (see
// tasks.md Unit 2 / design.md Testing Strategy).
// ============================================================================

import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";

import {
    PendingUiRequestRegistry,
    validateUiAnswer,
    buildAutoApproveDecision,
} from "../idupi-server/lib/ui-request-registry.mjs";

const HEADER = "[ui-request-smoke]";
const CHECK = (label) => console.log(`${HEADER} ✔ ${label}`);
const FAIL = (label, err) => {
    console.error(`${HEADER} ✘ ${label}`);
    if (err) console.error(err.stack || err.message || err);
    process.exitCode = 1;
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Wraps every scenario in a try/catch that prints a labelled PASS/FAIL line
 * without aborting the rest of the harness -- a single failure does not mask
 * the others (a Phase 6 RED-first contract: each scenario is a standalone
 * checkpoint).
 */
async function scenario(name, fn) {
    process.stdout.write(`${HEADER}  → ${name}\n`);
    const t0 = performance.now();
    try {
        await fn();
        CHECK(`${name}  (${(performance.now() - t0).toFixed(1)} ms)`);
    } catch (err) {
        FAIL(name, err);
    }
}

// ---------------------------------------------------------------------------
// 1. Select round-trip: register → install writer → resolve → writer fires
// ---------------------------------------------------------------------------

await scenario("select round-trip writes exactly one JSON line to stdin", async () => {
    const reg = new PendingUiRequestRegistry({ deadlineMs: 60_000 });
    const reg_ = reg.register({
        sessionId: "sess-smoke-1",
        engine: "pi",
        method: "select",
        options: ["A", "B"],
        title: "Pick one",
        message: "Smoke harness",
    });

    // Stand-in for the per-engine stdin writer that index.mjs installs. Pi's
    // adapter now emits the correlated `extension_ui_response` frame addressed
    // to the PI request id (the id Pi put on `extension_ui_request`) — never
    // the registry requestId — per the verified Pi 0.84.0 RPC contract.
    const piRequestId = "e39b608a-dbde-4b49-ae3d-d87fc73791cf";
    const writes = [];
    const writer = (value) => {
        writes.push(JSON.stringify({
            type: "extension_ui_response",
            id: piRequestId,
            value,
        }) + "\n");
        return true;
    };

    const result = reg.resolve({
        requestId: reg_.requestId,
        token: reg_.token,
        sessionId: "sess-smoke-1",
        value: "A",
    });

    // Mirror what index.mjs does after a successful resolve: invoke the
    // writer with the validated value.
    assert.equal(result.ok, true, "resolve must succeed for an exact-value select");
    writer(result.value);

    assert.equal(writes.length, 1, "exactly one stdin write per request");
    const payload = JSON.parse(writes[0].trimEnd());
    assert.equal(payload.type, "extension_ui_response");
    assert.equal(payload.id, piRequestId, "Pi frame MUST be addressed to the Pi request id");
    assert.equal(payload.value, "A", "value MUST be the exact option text");
});

// ---------------------------------------------------------------------------
// 2. Expiry blanket auto-approve: register → expire → blanket "Todo"
// ---------------------------------------------------------------------------

await scenario("120s expiry auto-approves select with the blanket Todo decision", async () => {
    // Tiny deadline so the test fires in milliseconds instead of minutes.
    const reg = new PendingUiRequestRegistry({ deadlineMs: 1 });

    const observed = [];
    reg.on("expire", (p) => observed.push(p));

    const reg_ = reg.register({
        sessionId: "sess-smoke-2",
        engine: "pi",
        method: "select",
        options: ["X", "Y"],
        title: "Pick",
        message: "Smoke expiry",
    });

    // Wait past the 1ms deadline plus a generous buffer for the event loop.
    await sleep(50);

    assert.equal(observed.length, 1, "exactly one expire event must fire");
    const { decision } = observed[0];
    assert.equal(decision.source, "auto_approve");
    assert.equal(decision.value, "Todo", "blanket auto-approve for select is Todo");
    assert.equal(reg.count(), 0, "expired entry MUST be cleared from the registry");

    // Pure-function helper agrees with the live expiry.
    assert.deepEqual(buildAutoApproveDecision("select"), {
        value: "Todo",
        source: "auto_approve",
    });
});

await scenario("120s expiry auto-resolves confirm with cancelled:true (approve is meaningless)", async () => {
    const reg = new PendingUiRequestRegistry({ deadlineMs: 1 });
    const observed = [];
    reg.on("expire", (p) => observed.push(p));

    reg.register({
        sessionId: "sess-smoke-confirm",
        engine: "opencode",
        method: "confirm",
        title: "Continue?",
        message: "smoke",
    });

    await sleep(50);
    assert.equal(observed.length, 1);
    assert.deepEqual(observed[0].decision.value, { cancelled: true });
});

// ---------------------------------------------------------------------------
// 3. Stale-token rejection: register → register newer → resolve older → 409
// ---------------------------------------------------------------------------

await scenario("superseded request's token is rejected with 409 (stale-token scenario)", async () => {
    const reg = new PendingUiRequestRegistry({ deadlineMs: 60_000 });

    const older = reg.register({
        sessionId: "sess-smoke-3",
        engine: "pi",
        method: "select",
        options: ["A", "B"],
        title: "Older",
        message: "first",
    });
    const newer = reg.register({
        sessionId: "sess-smoke-3",
        engine: "pi",
        method: "select",
        options: ["A", "B"],
        title: "Newer",
        message: "second",
    });
    assert.notEqual(older.token, newer.token, "monotonic token MUST advance per register()");

    // The older entry is still in the registry (its timer is still running),
    // but currentTokenFor(sessionId) has moved on.
    const stale = reg.resolve({
        requestId: older.requestId,
        token: older.token,
        sessionId: "sess-smoke-3",
        value: "A",
    });
    assert.equal(stale.ok, false);
    assert.equal(stale.status, 409, "stale token MUST surface as 409");
    assert.match(stale.reason, /superseded/i);

    // The newer entry is still answerable.
    const fresh = reg.resolve({
        requestId: newer.requestId,
        token: newer.token,
        sessionId: "sess-smoke-3",
        value: "B",
    });
    assert.equal(fresh.ok, true, "the newer request is still answerable");
    assert.equal(fresh.value, "B");
});

// ---------------------------------------------------------------------------
// 4. Out-of-date value rejection: value not in options → 400 (NOT a stdin write)
// ---------------------------------------------------------------------------

await scenario("out-of-date value (e.g. C not in options) is rejected with 400 before any stdin write", async () => {
    const reg = new PendingUiRequestRegistry({ deadlineMs: 60_000 });
    const reg_ = reg.register({
        sessionId: "sess-smoke-4",
        engine: "pi",
        method: "select",
        options: ["A", "B"],
        title: "Pick",
        message: "exact-value smoke",
    });

    const writes = [];
    const writer = (value) => {
        writes.push(value);
        return true;
    };

    const result = reg.resolve({
        requestId: reg_.requestId,
        token: reg_.token,
        sessionId: "sess-smoke-4",
        value: "C",
    });
    assert.equal(result.ok, false);
    assert.equal(result.status, 400, "out-of-date value MUST surface as 400");

    // The bug we're guarding: a 400 must NEVER trigger a stdin write.
    // We deliberately do NOT invoke writer() on a non-ok result.
    assert.equal(writes.length, 0, "no stdin write on validation failure");

    // Pure-function validator agrees.
    assert.equal(validateUiAnswer("select", "C", ["A", "B"]).ok, false);
});

// ---------------------------------------------------------------------------
// 5. Unknown requestId → 404 BEFORE any stdin write
// ---------------------------------------------------------------------------

await scenario("unknown requestId is rejected with 404 (the registry never sees a writer)", async () => {
    const reg = new PendingUiRequestRegistry({ deadlineMs: 60_000 });
    const result = reg.resolve({
        requestId: "uir_does_not_exist",
        token: 1,
        sessionId: "sess-smoke-5",
        value: "A",
    });
    assert.equal(result.ok, false);
    assert.equal(result.status, 404);
});

// ---------------------------------------------------------------------------
// 6. Expiry unblocks the engine: the writer receives the blanket decision
// ---------------------------------------------------------------------------

await scenario("expiry delivers the blanket decision to the writer exactly once (engine unblocks)", async () => {
    // Mirrors the index.mjs expire listener (Phase 3): the listener writes the
    // terminal decision to the engine's stdin writer BEFORE emitting
    // ui_request_resolved, so Pi's dialog does NOT ride into the 300s
    // taskkill. The registry itself owns only the lifecycle — the listener is
    // what performs the write, exactly like index.mjs wires it.
    const reg = new PendingUiRequestRegistry({ deadlineMs: 1 });
    const writes = [];

    reg.register({
        sessionId: "sess-smoke-6",
        engine: "pi",
        method: "select",
        options: ["X", "Y"],
        title: "Pick",
        message: "expiry stdin smoke",
    });

    // Stand-in for the stdin writer index.mjs installs at register-time.
    const writer = (value) => {
        writes.push(value);
        return true;
    };

    // Mirror of index.mjs' expire listener: write the decision to the writer.
    reg.on("expire", ({ decision }) => {
        writer(decision.value);
    });

    await sleep(50);

    assert.equal(writes.length, 1, "expire MUST deliver the decision to the writer exactly once");
    assert.equal(writes[0], "Todo", "the blanket auto-approve Todo reaches the engine");
});

console.log(`${HEADER} done (exitCode=${process.exitCode ?? 0})`);
