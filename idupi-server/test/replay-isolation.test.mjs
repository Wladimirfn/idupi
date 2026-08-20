// ============================================================================
// idupi-server/test/replay-isolation.test.mjs
//
// Task 2.1 (RED then GREEN under Strict TDD): SSE per-context isolation, no
// broadcast, isolated replay, bounded queue overflow, and the explicit proof
// that a subscriber disconnect does NOT stop the operation-owned
// ActivityRegistry 15s heartbeat.
//
// Run (from repo root):
//   node --test idupi-server/test/replay-isolation.test.mjs
//
// Each test uses a UNIQUE sessionId so the module-level replay ring buffer does
// not leak frames across tests. Subscribers are always disconnected in a
// finally block so the SSE keepalive interval never keeps the process alive.
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { subscribe, publish, enqueueBounded } from "../chat-events.mjs";
import { ACTIVITY_TYPES, ActivityRegistry } from "../lib/activity.mjs";

// --- minimal mock SSE request/response (compatible with chat-events.mjs) ---
function mockReq() {
    const handlers = {};
    return { req: { on: (evt, cb) => { handlers[evt] = cb; } }, handlers };
}
function mockRes() {
    const writes = [];
    const handlers = {};
    const res = {
        writeHead() {},
        write(chunk) { writes.push(String(chunk)); return true; },
        end() {},
        setHeader() {},
        on: (evt, cb) => { handlers[evt] = cb; },
        once: (evt, cb) => { handlers[evt] = cb; },
        removeListener: () => {},
    };
    return { res, writes, handlers };
}

function eventFrameCount(writes, type) {
    return writes.filter((w) => w.includes(`event: ${type}`)).length;
}

// Unique contexts per test to avoid replay-buffer cross-contamination.
const CTX = {
    a1: { engine: "claude", project: "P", sessionId: "S1" },
    b1: { engine: "opencode", project: "P", sessionId: "S1" },
    a2: { engine: "claude", project: "P", sessionId: "S2" },
    a3: { engine: "claude", project: "P", sessionId: "S3" },
    a4: { engine: "claude", project: "P", sessionId: "S4" },
    b4: { engine: "opencode", project: "P", sessionId: "S4" },
    own: { engine: "claude", project: "P", sessionId: "OWN" },
};

test("same opaque-bound subscriber receives its context activity; wrong context receives 0", () => {
    const subs = [];
    try {
        const a = mockReq(); const ma = mockRes(); subs.push(a);
        const b = mockReq(); const mb = mockRes(); subs.push(b);
        subscribe(a.req, ma.res, CTX.a1);
        subscribe(b.req, mb.res, CTX.b1);

        publish(ACTIVITY_TYPES.START, {
            ...CTX.a1, id: "op1", streamId: "st1", kind: "tool", name: "t", startedAt: 1,
        });

        assert.strictEqual(eventFrameCount(ma.writes, ACTIVITY_TYPES.START), 1, "A receives its activity");
        assert.strictEqual(eventFrameCount(mb.writes, ACTIVITY_TYPES.START), 0, "B (wrong engine) receives 0");
    } finally {
        subs.forEach((s) => s.handlers.close());
    }
});

test("wrong project/engine/session yields 0; activity is never broadcast", () => {
    const subs = [];
    try {
        const a = mockReq(); const ma = mockRes(); subs.push(a);
        subscribe(a.req, ma.res, CTX.a2);

        publish(ACTIVITY_TYPES.UPDATE, {
            engine: "claude", project: "OTHER", sessionId: "S2",
            id: "op2", streamId: "st2", lastUpdateAt: 2,
        });
        assert.strictEqual(eventFrameCount(ma.writes, ACTIVITY_TYPES.UPDATE), 0, "different project -> 0");

        publish(ACTIVITY_TYPES.UPDATE, {
            engine: "claude", project: "P", sessionId: "OTHER",
            id: "op3", streamId: "st3", lastUpdateAt: 3,
        });
        assert.strictEqual(eventFrameCount(ma.writes, ACTIVITY_TYPES.UPDATE), 0, "different session -> 0");
    } finally {
        subs.forEach((s) => s.handlers.close());
    }
});

test("subscriber with missing context params receives 0 activity", () => {
    const subs = [];
    try {
        const a = mockReq(); const ma = mockRes(); subs.push(a);
        subscribe(a.req, ma.res, { engine: "claude" }); // missing project/sessionId

        publish(ACTIVITY_TYPES.START, {
            ...CTX.a3, id: "op9", streamId: "st9", kind: "tool", name: "t", startedAt: 1,
        });
        assert.strictEqual(eventFrameCount(ma.writes, ACTIVITY_TYPES.START), 0, "incomplete context -> 0");
    } finally {
        subs.forEach((s) => s.handlers.close());
    }
});

test("reconnect re-binds context (old context not leaked to new subscriber)", () => {
    const subs = [];
    try {
        const a = mockReq(); const ma = mockRes(); subs.push(a);
        subscribe(a.req, ma.res, CTX.a4);
        publish(ACTIVITY_TYPES.START, {
            ...CTX.a4, id: "op1", streamId: "st1", kind: "tool", name: "t", startedAt: 1,
        });
        assert.strictEqual(eventFrameCount(ma.writes, ACTIVITY_TYPES.START), 1, "first context receives its activity");

        a.handlers.close(); // disconnect the first subscriber
        subs.pop();

        const b = mockReq(); const mb = mockRes(); subs.push(b);
        subscribe(b.req, mb.res, CTX.b4); // reconnect with a different context
        publish(ACTIVITY_TYPES.START, {
            ...CTX.b4, id: "op2", streamId: "st2", kind: "tool", name: "t2", startedAt: 2,
        });
        assert.strictEqual(eventFrameCount(mb.writes, ACTIVITY_TYPES.START), 1, "new context receives new activity");
        assert.strictEqual(eventFrameCount(mb.writes, ACTIVITY_TYPES.UPDATE), 0, "no leak from old context");
    } finally {
        subs.forEach((s) => s.handlers.close());
    }
});

test("enqueueBounded drops oldest, preserves newest and order", () => {
    let q = [];
    for (let i = 1; i <= 5; i++) q = enqueueBounded(q, i, 3);
    assert.deepStrictEqual(q, [3, 4, 5], "oldest dropped; newest + order preserved");
});

test("subscriber disconnect does NOT stop the operation-owned 15s heartbeat", () => {
    const subs = [];
    try {
        const frames = [];
        let now = 1000;
        const intervals = new Map();
        let iid = 0;
        const cleared = [];
        const clock = {
            now: () => now,
            setInterval: (cb) => { const id = ++iid; intervals.set(id, cb); return id; },
            clearInterval: (id) => { cleared.push(id); intervals.delete(id); },
            setTimeout: () => 0,
            _fire: () => { for (const cb of [...intervals.values()]) cb(); },
            _intervalCount: () => intervals.size,
            _clearedCount: () => cleared.length,
        };

        const reg = new ActivityRegistry({
            publish: (type, data) => frames.push({ type, data }),
            clock,
        });
        reg.start("op1", {
            engine: "claude", project: "P", sessionId: "OWN", streamId: "st1", kind: "tool", name: "t",
        });
        assert.strictEqual(clock._intervalCount(), 1, "operation heartbeat interval running");

        // connect then immediately disconnect a chat-events subscriber
        const a = mockReq(); const ma = mockRes(); subs.push(a);
        subscribe(a.req, ma.res, CTX.own);
        a.handlers.close();

        // the disconnect must not have touched the registry heartbeat
        assert.strictEqual(clock._clearedCount(), 0, "disconnect must not clear the operation heartbeat");
        assert.strictEqual(clock._intervalCount(), 1, "operation heartbeat still scheduled");

        // fire the heartbeat: it must still emit a frame (operation still live)
        clock._fire();
        const hb = frames.filter((f) => f.type === ACTIVITY_TYPES.HEARTBEAT);
        assert.strictEqual(hb.length, 1, "heartbeat fired after subscriber disconnect");
    } finally {
        subs.forEach((s) => s.handlers.close());
    }
});
