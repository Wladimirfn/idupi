// ============================================================================
// idupi-server/test/activity-encode.test.mjs
//
// Task 1.1 (RED then GREEN under Strict TDD): encode / redact / structural-MCP
// classification / operation-owned 15s heartbeat / idempotent terminalize for
// idupi-server/lib/activity.mjs.
//
// Run (from repo root):
//   node --test idupi-server/test/activity-encode.test.mjs
//
// This file is written BEFORE lib/activity.mjs exists, so the very first run
// must fail to load the module — that is the genuine RED.
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import {
    ACTIVITY_TYPES,
    redactActivity,
    classifyMcp,
    encodeActivity,
    ActivityRegistry,
} from "../lib/activity.mjs";

// ---------------------------------------------------------------------------
// redactActivity: secret / Authorization / URL-query / path stripping
// ---------------------------------------------------------------------------

test("redactActivity strips api_key secret", () => {
    const r = redactActivity("api_key=sk-1234567890secret");
    assert.ok(r.includes("[REDACTED]"), `expected [REDACTED], got: ${r}`);
    assert.ok(!r.includes("sk-1234567890secret"), "raw secret must not remain on the wire");
});

test("redactActivity strips Bearer authorization token", () => {
    const r = redactActivity("Authorization: Bearer abcdef123456token");
    assert.ok(!r.includes("abcdef123456token"), "bearer token must not remain on the wire");
    assert.ok(r.includes("[REDACTED]"), `expected redaction, got: ${r}`);
});

test("redactActivity strips URL-query secret", () => {
    const r = redactActivity("GET https://x.io/p?token=supersecret&other=1");
    assert.ok(!r.includes("supersecret"), "url-query secret must not remain on the wire");
    assert.ok(r.includes("?token=[REDACTED]"), `expected query redaction, got: ${r}`);
});

test("redactActivity strips filesystem / user path", () => {
    const r = redactActivity("writing /Users/dev/secret.txt now");
    assert.ok(!r.includes("/Users/dev/secret.txt"), "user path must not remain on the wire");
    assert.ok(r.includes("[PATH]"), `expected [PATH], got: ${r}`);
});

test("redactActivity drops malformed / binary input", () => {
    assert.strictEqual(redactActivity("bad\uFFFDbinary"), "");
});

test("redactActivity enforces per-field UTF-8 byte cap", () => {
    const r = redactActivity("é".repeat(100), { fieldCap: 8 });
    assert.ok(
        Buffer.byteLength(r, "utf8") <= 8,
        `byteLength ${Buffer.byteLength(r, "utf8")} must be <= 8`
    );
});

test("redactActivity enforces total 4096 byte cap", () => {
    const r = redactActivity("x".repeat(5000));
    assert.ok(
        Buffer.byteLength(r, "utf8") <= 4096,
        `byteLength ${Buffer.byteLength(r, "utf8")} must be <= 4096`
    );
});

// ---------------------------------------------------------------------------
// classifyMcp: structural detection for the three engines (zero allowlist)
// ---------------------------------------------------------------------------

test("classifyMcp Claude mcp__server__tool is MCP with server", () => {
    const c = classifyMcp({ engine: "claude", toolName: "mcp__s__t" });
    assert.deepStrictEqual(c, { engine: "claude", isMcp: true, server: "s", name: "mcp__s__t" });
});

test("classifyMcp Claude ToolSearch is generic (not MCP)", () => {
    const c = classifyMcp({ engine: "claude", toolName: "ToolSearch" });
    assert.strictEqual(c.isMcp, false, "ToolSearch must stay generic");
    assert.strictEqual(c.server, undefined);
});

test("classifyMcp OpenCode toolName visible, server additive", () => {
    const a = classifyMcp({ engine: "opencode", toolName: "my_tool" });
    assert.strictEqual(a.name, "my_tool", "toolName must be visible at start");
    assert.strictEqual(a.server, undefined);
    assert.strictEqual(a.isMcp, false);

    const b = classifyMcp({ engine: "opencode", toolName: "my_tool", server: "srv" });
    assert.strictEqual(b.name, "my_tool", "original name retained");
    assert.strictEqual(b.server, "srv", "server added additively");
    assert.strictEqual(b.isMcp, true);
});

test("classifyMcp Pi statusKey=mcp generic + additive server", () => {
    const a = classifyMcp({ engine: "pi", name: "run", statusKey: "mcp" });
    assert.strictEqual(a.isMcp, true);
    assert.strictEqual(a.server, undefined, "generic at start, no server yet");

    const b = classifyMcp({ engine: "pi", name: "run", statusKey: "mcp", server: "srv" });
    assert.strictEqual(b.isMcp, true);
    assert.strictEqual(b.server, "srv", "server added additively at end");

    const c = classifyMcp({ engine: "pi", name: "run" });
    assert.strictEqual(c.isMcp, false, "no mcp status -> not MCP");
});

// ---------------------------------------------------------------------------
// encodeActivity: identity + redaction present
// ---------------------------------------------------------------------------

test("encodeActivity start carries identity and redacts detail", () => {
    const entry = {
        id: "op1", streamId: "s1", engine: "claude", project: "P", sessionId: "S",
        kind: "tool", name: "mcp__s__t", server: undefined,
        detail: "api_key=sk-secret", startedAt: 1000,
    };
    const data = encodeActivity({ type: ACTIVITY_TYPES.START, entry });
    assert.strictEqual(data.id, "op1");
    assert.strictEqual(data.engine, "claude");
    assert.strictEqual(data.project, "P");
    assert.strictEqual(data.sessionId, "S");
    assert.strictEqual(data.name, "mcp__s__t");
    assert.ok(data.detail.includes("[REDACTED]"), "detail must be redacted");
    assert.ok(!data.detail.includes("sk-secret"), "raw secret must not remain");
});

// ---------------------------------------------------------------------------
// ActivityRegistry: injectable 15s heartbeat (inflight only) + terminal stops it
// ---------------------------------------------------------------------------

function fakeClock() {
    let now = 1000;
    const intervals = new Map();
    let iid = 0;
    const cleared = [];
    const timeouts = [];
    return {
        now: () => now,
        setInterval: (cb) => { const id = ++iid; intervals.set(id, cb); return id; },
        clearInterval: (id) => { cleared.push(id); intervals.delete(id); },
        setTimeout: (cb) => { timeouts.push(cb); return 0; },
        _advance: (ms) => { now += ms; },
        _fire: () => { for (const cb of [...intervals.values()]) cb(); },
        intervalCount: () => intervals.size,
        clearedCount: () => cleared.length,
        timeoutCount: () => timeouts.length,
    };
}

test("ActivityRegistry heartbeat reports inflight:true only; terminal stops it", () => {
    const frames = [];
    const clock = fakeClock();
    const reg = new ActivityRegistry({
        publish: (type, data) => frames.push({ type, data }),
        clock,
    });

    reg.start("op1", {
        engine: "claude", project: "P", sessionId: "S", streamId: "st1",
        kind: "tool", name: "t",
    });

    // START frame
    assert.strictEqual(frames.length, 1, "exactly one start frame");
    assert.strictEqual(frames[0].type, ACTIVITY_TYPES.START);
    assert.strictEqual(frames[0].data.id, "op1");
    assert.strictEqual(clock.intervalCount(), 1, "operation-owned heartbeat interval started");

    // fire the 15s heartbeat
    clock._advance(15000);
    clock._fire();
    const hb = frames.find((f) => f.type === ACTIVITY_TYPES.HEARTBEAT);
    assert.ok(hb, "heartbeat frame emitted at 15s");
    assert.strictEqual(hb.data.inflight, true, "heartbeat only reports inflight");
    assert.strictEqual(hb.data.elapsedMs, 15000, "elapsed computed from start");
    assert.ok(!("server" in hb.data), "heartbeat must NOT carry server");
    assert.ok(!("detail" in hb.data), "heartbeat must NOT carry detail");

    // terminalize
    reg.terminalize("op1", { ok: true });
    assert.strictEqual(clock.intervalCount(), 0, "heartbeat interval cleared on terminal");
    assert.strictEqual(clock.clearedCount(), 1, "clearInterval called exactly once");

    const terminal = frames.filter((f) => f.type === ACTIVITY_TYPES.END);
    assert.strictEqual(terminal.length, 1, "exactly one terminal frame");
    assert.strictEqual(terminal[0].data.ok, true);

    // after terminal, firing the interval again must NOT emit a new heartbeat
    const before = frames.length;
    clock._fire();
    assert.strictEqual(frames.length, before, "no heartbeat after terminal");
});

test("ActivityRegistry duplicate start ignored; terminalize idempotent", () => {
    const frames = [];
    const clock = fakeClock();
    const reg = new ActivityRegistry({ publish: (t, d) => frames.push({ type: t, data: d }), clock });

    reg.start("op1", { engine: "claude", project: "P", sessionId: "S", streamId: "st1", kind: "tool", name: "t" });
    reg.start("op1", { engine: "claude", project: "P", sessionId: "S", streamId: "st1", kind: "tool", name: "t" });
    assert.strictEqual(
        frames.filter((f) => f.type === ACTIVITY_TYPES.START).length,
        1,
        "duplicate start ignored"
    );

    reg.terminalize("op1", { ok: true });
    reg.terminalize("op1", { ok: true });
    assert.strictEqual(
        frames.filter((f) => f.type === ACTIVITY_TYPES.END).length,
        1,
        "terminalize idempotent"
    );
});
