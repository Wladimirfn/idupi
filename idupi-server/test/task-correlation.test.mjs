// ============================================================================
// idupi-server/test/task-correlation.test.mjs
//
// `activeTask` is a single global, replaced on every POST /chat/message. The
// app abandons that POST after 20s (a long CLI answer outlives a mobile NAT's
// idle window) and falls back to polling /chat/active-task -- but the poll only
// checked `status == "completed"`, never which task it belonged to.
//
// So sending a second message while the first is still polling made the first
// poll return the SECOND answer, or miss its own entirely. That is exactly the
// "did all the work but the answer never arrived" the user hit with Claude.
//
// The client owns the correlation key: it never sees the server's taskId,
// because it gave up on the POST before the response came back.
//
// Run (from repo root):
//   node --test idupi-server/test/task-correlation.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";

import { TaskRegistry } from "../lib/task-registry.mjs";

test("a finished task is still findable after a newer one started", () => {
    const reg = new TaskRegistry();
    reg.start("client-a", "primer mensaje");
    reg.finish("client-a", { output: "respuesta A" });

    reg.start("client-b", "segundo mensaje");

    const a = reg.get("client-a");
    assert.equal(a.status, "completed");
    assert.equal(a.output, "respuesta A");
});

test("a poll never receives another task's answer", () => {
    const reg = new TaskRegistry();
    reg.start("client-a", "primer mensaje");
    reg.start("client-b", "segundo mensaje");
    reg.finish("client-b", { output: "respuesta B" });

    const a = reg.get("client-a");
    assert.notEqual(a.output, "respuesta B");
    assert.equal(a.status, "running");
});

test("an error is reported to its own task", () => {
    const reg = new TaskRegistry();
    reg.start("client-a", "m");
    reg.finish("client-a", { error: "explotó" });

    const a = reg.get("client-a");
    assert.equal(a.status, "error");
    assert.equal(a.error, "explotó");
});

test("an unknown id reports unknown rather than someone else's state", () => {
    const reg = new TaskRegistry();
    reg.start("client-a", "m");
    reg.finish("client-a", { output: "respuesta A" });

    assert.equal(reg.get("nunca-existio").status, "unknown");
});

test("a client that sends no id still gets the current task, as before", () => {
    const reg = new TaskRegistry();
    reg.start("client-a", "m");
    reg.finish("client-a", { output: "respuesta A" });

    const current = reg.current();
    assert.equal(current.status, "completed");
    assert.equal(current.output, "respuesta A");
});

test("the registry is bounded and keeps the newest tasks", () => {
    const reg = new TaskRegistry({ cap: 5 });
    for (let i = 0; i < 50; i++) {
        reg.start(`c${i}`, "m");
        reg.finish(`c${i}`, { output: `out ${i}` });
    }
    assert.equal(reg.get("c49").output, "out 49");
    assert.equal(reg.get("c0").status, "unknown");
    assert.ok(reg.size <= 5, `size ${reg.size}`);
});

test("finishing a task nobody started is ignored, not invented", () => {
    const reg = new TaskRegistry();
    reg.finish("fantasma", { output: "x" });
    assert.equal(reg.get("fantasma").status, "unknown");
});
