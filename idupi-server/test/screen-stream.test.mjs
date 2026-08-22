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
