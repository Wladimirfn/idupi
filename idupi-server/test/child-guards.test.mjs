// ============================================================================
// idupi-server/test/child-guards.test.mjs
//
// Crash regression: an 'error' event with no listener (ENOENT on spawn,
// EPIPE writing to a child that just died) is thrown by Node and takes the
// WHOLE server down. guardChildStreams() gives every stdio stream a listener;
// spawn-level errors are handled at each call site.
//
// Run (from repo root):
//   node --test idupi-server/test/child-guards.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { EventEmitter } from "node:events";

import { guardChildStreams } from "../lib/child-guards.mjs";

function fakeChild() {
    const child = new EventEmitter();
    child.stdin = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    return child;
}

test("stdio stream errors are logged instead of thrown", () => {
    const logged = [];
    const child = guardChildStreams(fakeChild(), "test", { warn: (m) => logged.push(m) });
    const epipe = Object.assign(new Error("write EPIPE"), { code: "EPIPE" });
    assert.doesNotThrow(() => child.stdin.emit("error", epipe));
    assert.doesNotThrow(() => child.stdout.emit("error", new Error("boom")));
    assert.equal(logged.length, 2);
    assert.match(logged[0], /\[test\] stdin error: EPIPE/);
});

test("children without some stdio streams are tolerated", () => {
    const child = new EventEmitter();
    child.stdin = null;
    assert.doesNotThrow(() => guardChildStreams(child, "test", { warn() {} }));
});

test("an EPIPE on a real child's stdin does not crash the process", async () => {
    // Without a listener this exact sequence kills node with
    // "Error: write EPIPE" (verified before the fix).
    const logged = [];
    const child = guardChildStreams(
        spawn(process.execPath, ["-e", "setTimeout(() => {}, 2000)"], { stdio: ["pipe", "pipe", "pipe"] }),
        "epipe",
        { warn: (m) => logged.push(m) },
    );
    child.stdin.destroy(Object.assign(new Error("write EPIPE"), { code: "EPIPE" }));
    await new Promise((resolve) => setTimeout(resolve, 100));
    child.kill();
    assert.deepEqual(logged, ["[epipe] stdin error: EPIPE"]);
});
