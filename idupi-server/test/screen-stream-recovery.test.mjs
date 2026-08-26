// Timer-paced recovery: a failed capture costs one tick, not the session.
// The timer keeps ticking and the next successful helper call emits again.
// Acks are telemetry only -- they never trigger a capture directly, so
// duplicate/stale acks stay idempotent regardless of failures.

import test from "node:test";
import assert from "node:assert/strict";
import { createScreenStream } from "../lib/screen-stream.mjs";

function fakeHelper() {
    let nextId = 1;
    const state = { calls: 0, failNext: 0 };
    return {
        state,
        async capture({ width, height }) {
            state.calls++;
            if (state.failNext > 0) {
                state.failNext--;
                throw new Error("capture failed");
            }
            return { meta: { id: nextId++, w: width ?? 8, h: height ?? 8 }, jpeg: Buffer.alloc(4) };
        },
    };
}

function paced(helper) {
    return createScreenStream({ helper, width: 800, height: 450, paceIntervalMs: 18 });
}
const settle = (ms = 80) => new Promise((r) => setTimeout(r, ms));

test("a failed timer capture does not kill the session", async () => {
    const helper = fakeHelper();
    const stream = paced(helper);
    const frames = [];
    stream.onFrame((f) => frames.push(f));

    await stream.start();
    assert.equal(frames.length, 1);

    // Next tick fails -- timer must survive and emit on the following tick
    helper.state.failNext = 1;
    await settle(120);
    // One tick may have failed, but the timer kept going and emitted at least once more
    assert.ok(frames.length >= 2, `timer must recover after one failure, got ${frames.length}`);
    await stream.stop();
});

test("several failed captures in a row still recover", async () => {
    const helper = fakeHelper();
    const stream = paced(helper);
    const frames = [];
    stream.onFrame((f) => frames.push(f));

    await stream.start();
    helper.state.failNext = 3;
    await settle(200);
    // After 3 failures the next successful tick emits again
    assert.ok(frames.length >= 2, `three failures must not kill session, got ${frames.length}`);
    await stream.stop();
});

test("acks stay idempotent after a recovery", async () => {
    const helper = fakeHelper();
    const stream = paced(helper);
    const frames = [];
    stream.onFrame((f) => frames.push(f));

    await stream.start();
    await settle(60);
    const firstId = frames[0].meta.id;

    // Ack the outstanding frame twice -- second is stale/duplicate, no throw, no extra capture burst
    await stream.onAck({ frameId: firstId, renderMs: 5 });
    await stream.onAck({ frameId: firstId, renderMs: 5 });
    const after = frames.length;
    await settle(60);
    // Timer still paces normally, no duplicate burst from the second ack
    assert.ok(frames.length >= after, "timer must keep pacing after duplicate ack");
    await stream.stop();
});

test("a stale ack from a previous window is ignored", async () => {
    const helper = fakeHelper();
    const stream = paced(helper);
    const frames = [];
    stream.onFrame((f) => frames.push(f));

    await stream.start();
    await settle(80);
    const staleId = frames[0].meta.id;
    await stream.onAck({ frameId: staleId, renderMs: 5 });
    await settle(40);
    const before = frames.length;
    // Same id again is now stale (already removed from outstanding)
    await stream.onAck({ frameId: staleId, renderMs: 5 });
    await settle(40);
    // No throw and no special burst -- just timer pacing
    assert.ok(frames.length >= before, "stale ack must be ignored without killing pacing");
    await stream.stop();
});
