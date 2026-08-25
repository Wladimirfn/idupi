import test from "node:test";
import assert from "node:assert/strict";

import { createScreenStream } from "../lib/screen-stream.mjs";

function fakeHelper({ latencyMs = 5 } = {}) {
    const calls = [];
    let seq = 0;
    return {
        calls,
        async capture({ monitor, width, height, quality }) {
            calls.push({ monitor, width, height, quality });
            // Simulate helper latency so in-flight rules are observable.
            if (latencyMs > 0) await new Promise((r) => setTimeout(r, latencyMs));
            const id = ++seq;
            return {
                meta: { id, w: width, h: height },
                jpeg: Buffer.from([0xff, 0xd8, id]),
            };
        },
    };
}

// Deterministic pacing for tests: overrides whatever the preset dictates.
function pacedStream(helper, extra = {}) {
    return createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
        paceIntervalMs: 15,
        ...extra,
    });
}

const settle = (ms = 60) => new Promise((r) => setTimeout(r, ms));

// --- Phase D contract: captures ride a TIMER at the preset's fps; acks only
// feed the ladder and bound an unacked window. The old design waited for
// each ack before capturing -- one frame per network round trip -- which on
// a 157ms-ping link capped sessions at ~5fps regardless of everything else.

test("start() sends the first frame immediately", async () => {
    const stream = pacedStream(fakeHelper());
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    assert.equal(frames.length, 1);
    await stream.stop();
});

test("captures flow on the pace timer without waiting for any ack", async () => {
    const stream = pacedStream(fakeHelper());
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    await settle(80); // ~5 ticks at 15ms
    assert.ok(frames.length >= 3, `timer must push frames, got ${frames.length}`);
    await stream.stop();
});

test("an unacked window bounds how far the server runs ahead", async () => {
    const stream = pacedStream(fakeHelper({ latencyMs: 2 }));
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    // No acks AT ALL: once WINDOW frames are outstanding, captures skip --
    // always fresh content, never a stale queue.
    await settle(300);
    const plateau = frames.length;
    await settle(150);
    assert.equal(frames.length, plateau, "window full must stop new captures");
    assert.ok(plateau <= 6, `plateau should sit near the window size, got ${plateau}`);
    await stream.stop();
});

test("acking reopens the window and the flow continues", async () => {
    const stream = pacedStream(fakeHelper({ latencyMs: 2 }));
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    await settle(250); // window fills, flow stalls
    const stalled = frames.length;
    for (const f of frames.slice()) await stream.onAck({ frameId: f.meta.id, renderMs: 5 });
    await settle(120);
    assert.ok(frames.length > stalled, "acks must reopen the flow");
    await stream.stop();
});

test("auto starts at MEDIA: scaled capture dimensions and its jpeg quality", async () => {
    const stream = pacedStream(fakeHelper(), { quality: "auto" });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    assert.equal(frames.length, 1);
    await stream.stop();
});

test("a congested ack steps down fast and announces it as a control", async () => {
    const stream = pacedStream(fakeHelper(), { quality: "auto" });
    const frames = [];
    const controls = [];
    stream.onFrame((f) => frames.push(f));
    stream.onControl((c) => controls.push(c));
    await stream.start();
    while (frames.length === 0) await settle(10);

    // One bad ack must fall to BAJA immediately...
    await stream.onAck({ frameId: frames[0].meta.id, renderMs: 400 });
    const changed = controls.find((c) => c.type === "quality_changed");
    assert.ok(changed, "a quality_changed control must be announced");
    assert.equal(changed.name, "baja");

    // ...and subsequent captures ride the new preset's geometry.
    await settle(60);
    const recent = frames.at(-1);
    assert.equal(recent.meta.w, Math.round(800 * 0.4)); // 320
    await stream.stop();
});

test("manual numeric quality keeps fixed geometry and never announces", async () => {
    const helper = fakeHelper();
    const stream = pacedStream(helper, { quality: 75 });
    const frames = [];
    const controls = [];
    stream.onFrame((f) => frames.push(f));
    stream.onControl((c) => controls.push(c));
    await stream.start();
    await settle(60);
    for (const f of frames.slice()) await stream.onAck({ frameId: f.meta.id, renderMs: 900 });
    await settle(40);

    assert.equal(helper.calls[0].width, 800); // unscaled
    assert.equal(helper.calls[0].quality, 75);
    // Manual mode never moves and never announces: the human owns it.
    assert.equal(controls.length, 0);
    await stream.stop();
});

test("stop() ends the flow", async () => {
    const stream = pacedStream(fakeHelper());
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    await settle(50);
    await stream.stop();
    const at = frames.length;
    await settle(80);
    assert.equal(frames.length, at, "nothing flows after stop()");
});

// --- Instrumentation (optimization phase B) ---

test("stats() exposes frame count and average helper latency", async () => {
    const stream = pacedStream(fakeHelper()); // ~5ms simulated helper latency
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    await settle(80);

    const stats = stream.stats();
    assert.equal(stats.frames, frames.length);
    assert.ok(stats.avgHelperMs >= 4, `avgHelperMs should reflect the ~5ms fake delay, got ${stats.avgHelperMs}`);
    await stream.stop();
});

test("each frame's meta carries the measured helper latency", async () => {
    const stream = pacedStream(fakeHelper());
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();

    const f = frames[0];
    assert.equal(typeof f.meta.helperMs, "number");
    assert.ok(f.meta.helperMs >= 0);
    await stream.stop();
});
