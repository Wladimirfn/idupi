// ============================================================================
// idupi-server/test/screen-stream-recovery.test.mjs
//
// The stream died after 1996 good frames and never came back. Its pacing rule
// is that nothing is captured until the receiver acknowledges the frame it
// rendered, so silence from either side is silence forever -- there is no
// timer underneath to nudge it awake.
//
// onAck marked the frame acknowledged BEFORE capturing the next one. A single
// failed capture therefore consumed the acknowledgement without producing
// anything, and the retry for that same frame was rejected as a duplicate.
// The session stayed alive, holding a receiver that could no longer ask for
// anything, until the socket timed out thirty seconds later.
//
// One failure must cost one frame, not the session.
//
// Run (from repo root):
//   node --test idupi-server/test/screen-stream-recovery.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { createScreenStream } from "../lib/screen-stream.mjs";

/** A helper whose captures fail on demand, counting how often it was asked. */
function fakeHelper() {
    let nextId = 1;
    const state = { calls: 0, failNext: 0 };
    return {
        state,
        async capture() {
            state.calls++;
            if (state.failNext > 0) {
                state.failNext--;
                throw new Error("capture failed");
            }
            return { meta: { id: nextId++, w: 8, h: 8 }, jpeg: Buffer.alloc(4) };
        },
    };
}

function streamWith(helper) {
    const frames = [];
    const stream = createScreenStream({ helper, width: 8, height: 8 });
    stream.onFrame((f) => frames.push(f));
    return { stream, frames };
}

test("a failed capture costs one frame, not the session", async () => {
    const helper = fakeHelper();
    const { stream, frames } = streamWith(helper);

    await stream.start();
    const first = frames[0].meta.id;

    helper.state.failNext = 1;
    await assert.rejects(() => stream.onAck({ frameId: first }));

    // The receiver asks again for the frame it is still holding. Before the
    // fix this returned silently as a duplicate and nothing ever flowed again.
    await stream.onAck({ frameId: first });

    assert.equal(frames.length, 2, "el reintento del mismo ack debe volver a capturar");
});

test("a repeated ack still does not queue a second capture when it worked", async () => {
    // The duplicate guard is what keeps one frame in flight; recovery must not
    // cost us that.
    const helper = fakeHelper();
    const { stream, frames } = streamWith(helper);

    await stream.start();
    const first = frames[0].meta.id;

    await stream.onAck({ frameId: first });
    await stream.onAck({ frameId: first });
    await stream.onAck({ frameId: first });

    assert.equal(frames.length, 2, "un ack exitoso repetido no debe capturar de nuevo");
});

test("a stale ack is still ignored after a recovery", async () => {
    const helper = fakeHelper();
    const { stream, frames } = streamWith(helper);

    await stream.start();
    const first = frames[0].meta.id;

    helper.state.failNext = 1;
    await assert.rejects(() => stream.onAck({ frameId: first }));
    await stream.onAck({ frameId: first });

    const before = frames.length;
    await stream.onAck({ frameId: first }); // now genuinely stale
    assert.equal(frames.length, before, "un ack viejo no debe disparar una captura");
});

test("the session survives several failures in a row", async () => {
    const helper = fakeHelper();
    const { stream, frames } = streamWith(helper);

    await stream.start();
    const first = frames[0].meta.id;

    helper.state.failNext = 3;
    for (let i = 0; i < 3; i++) {
        await assert.rejects(() => stream.onAck({ frameId: first }));
    }
    await stream.onAck({ frameId: first });

    assert.equal(frames.length, 2, "tres fallas seguidas no deben cerrar la sesión");
});
