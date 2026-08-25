import test from "node:test";
import assert from "node:assert/strict";

import {
    claudeArgs,
    openCodeArgs,
} from "../lib/agent-cmdline.mjs";

// The CLIs spawn WITHOUT a shell: arguments are an argv array, so a hostile
// payload is inert data. These tests pin that no quoting layer exists to
// break -- the evil message must arrive as ONE element, byte-for-byte.

const EVIL = 'x" & calc.exe & echo "pwned';

test("claude args keep the hostile message as one inert element", () => {
    const args = claudeArgs({ message: EVIL });
    assert.deepEqual(args.slice(-2), ["-p", JSON.stringify(EVIL)]);
    // The static flags stay exactly where the CLI expects them.
    assert.equal(args.indexOf("--output-format"), 0);
    assert.ok(args.includes("bypassPermissions"));
});

test("openCode args keep the hostile message as one inert element", () => {
    const args = openCodeArgs({ message: EVIL });
    assert.deepEqual(args, ["run", "--format", "json", "--auto", EVIL]);
});

test("model and session ids ride as their own array elements", () => {
    const evilSession = 'abc" & whoami & "';
    const args = claudeArgs({ modelId: "m1", sessionId: evilSession, isNewSession: false, message: "hi" });
    assert.deepEqual(args.slice(0, 5), ["--model", "m1", "-r", evilSession, "--output-format"]);
});

test("happy path matches the documented CLI shape", () => {
    const args = claudeArgs({ modelId: "claude-x", sessionId: "s-123", isNewSession: true, message: "hola" });
    assert.deepEqual(args, [
        "--model", "claude-x",
        "--session-id", "s-123",
        "--output-format", "stream-json",
        "--verbose",
        "--permission-mode", "bypassPermissions",
        "-p", '"hola"',
    ]);
    assert.deepEqual(openCodeArgs({ sessionId: "s-9", message: "hola" }),
        ["run", "--format", "json", "--auto", "-s", "s-9", "hola"]);
});
