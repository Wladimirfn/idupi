import test from "node:test";
import assert from "node:assert/strict";

import { createScreenStream } from "../lib/screen-stream.mjs";

function fakeHelper() {
    const calls = [];
    let seq = 0;
    return {
        calls,
        async capture({ monitor, width, height, quality }) {
            calls.push({ monitor, width, height, quality });
            // Simulate helper latency so in-flight rules are observable.
            await new Promise((r) => setTimeout(r, 5));
            const id = ++seq;
            return {
                meta: { id, w: width, h: height },
                jpeg: Buffer.from([0xff, 0xd8, id]),
            };
        },
    };
}

test("start() sends exactly one fresh frame and never pushes on a timer", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 1,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    assert.equal(frames.length, 1);
    assert.equal(helper.calls.length, 1);
    // Wait longer than any plausible internal timer tick: nothing else arrives.
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(frames.length, 1);
    assert.equal(helper.calls.length, 1);
});

test("a second frame is captured only after the receiver acknowledges", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 1,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();

    stream.onAck({ frameId: frames[0].meta.id });
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(frames.length, 2);
    // Every captured frame is fresh: capture happens after ack, not before.
    assert.equal(helper.calls.length, 2);
});

test("an ack while a capture is already in flight never double-captures", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 1,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();

    // Duplicate acks for the same frame must be idempotent.
    stream.onAck({ frameId: frames[0].meta.id });
    stream.onAck({ frameId: frames[0].meta.id });
    stream.onAck({ frameId: frames[0].meta.id });
    await new Promise((r) => setTimeout(r, 30));
    assert.equal(
        helper.calls.length,
        2,
        "duplicate acks must not queue extra frames",
    );
});

test("stop() refuses further work and discards in-flight captures", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 1,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    stream.stop();

    await assert.rejects(
        stream.onAck({ frameId: frames[0].meta.id }),
        /stopped|shut ?down/i,
    );
});

test("setQuality applies to subsequent captures without pushing a frame", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 1,
        width: 800,
        height: 450,
        quality: 55,
    });
    const controls = [];
    stream.onControl((c) => controls.push(c));
    await stream.start();

    stream.setQuality(75);
    assert.equal(
        controls.filter((c) => c.type === "quality_changed").length,
        0,
        "quality change alone must not trigger an unsolicited frame",
    );

    const frames = [];
    stream.onFrame((f) => frames.push(f));
    stream.onAck({ frameId: 1 });
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(helper.calls[helper.calls.length - 1].quality, 75);
});

// --- Auto quality ladder (hito 9, brief §4.4) ---

test("auto starts at MEDIA: scaled capture dimensions and its jpeg quality", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
        quality: "auto",
    });
    await stream.start();
    const call = helper.calls[0];
    assert.equal(call.width, Math.round(800 * 0.7)); // 560
    assert.equal(call.height, Math.round(450 * 0.7)); // 315
    assert.equal(call.quality, 55);
});

test("one congested ack steps down fast and announces it as a control", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
        quality: "auto",
    });
    const frames = [];
    const controls = [];
    stream.onFrame((f) => frames.push(f));
    stream.onControl((c) => controls.push(c));
    await stream.start();

    // The client rendered too slowly: one bad ack must fall to BAJA NOW.
    await stream.onAck({ frameId: frames[0].meta.id, renderMs: 400 });
    await new Promise((r) => setTimeout(r, 20));

    const lastCall = helper.calls.at(-1);
    assert.equal(lastCall.width, Math.round(800 * 0.4)); // 320
    assert.equal(lastCall.height, Math.round(450 * 0.4)); // 180
    assert.equal(lastCall.quality, 40);
    const changed = controls.find((c) => c.type === "quality_changed");
    assert.ok(changed, "a quality_changed control must be announced");
    assert.equal(changed.name, "baja");
});

test("manual numeric quality keeps the legacy fixed behaviour", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
        quality: 75,
    });
    const controls = [];
    stream.onControl((c) => controls.push(c));
    await stream.start();
    await stream.onAck({ frameId: 1, renderMs: 900 });
    await new Promise((r) => setTimeout(r, 20));

    assert.equal(helper.calls[0].width, 800); // unscaled
    assert.equal(helper.calls[0].quality, 75);
    // Manual mode never moves and never announces: the human owns it.
    assert.equal(controls.length, 0);
});

// --- Instrumentation (hito 9 optimization phase B) ---

test("stats() exposes frame count and average helper latency", async () => {
    const helper = fakeHelper(); // ~5ms simulated helper latency
    const stream = createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();
    for (const f of frames.slice()) {
        await stream.onAck({ frameId: f.meta.id, renderMs: 10 });
        await new Promise((r) => setTimeout(r, 15));
    }

    const stats = stream.stats();
    assert.equal(stats.frames, frames.length);
    assert.ok(stats.avgHelperMs >= 4, `avgHelperMs should reflect the ~5ms fake delay, got ${stats.avgHelperMs}`);
});

test("each frame's meta carries the measured helper latency", async () => {
    const helper = fakeHelper();
    const stream = createScreenStream({
        helper,
        monitor: 0,
        width: 800,
        height: 450,
    });
    const frames = [];
    stream.onFrame((f) => frames.push(f));
    await stream.start();

    const f = frames[0];
    assert.equal(typeof f.meta.helperMs, "number");
    assert.ok(f.meta.helperMs >= 0);
});
