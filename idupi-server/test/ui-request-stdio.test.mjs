// ============================================================================
// idupi-server/test/ui-request-stdio.test.mjs
//
// Phase 6.6 — RED-first coverage for the "Claude + OpenCode stdin is now a
// pipe" contract added in Phase 3.
//
// The Phase 3 change set switched Claude and OpenCode spawn options from
//   stdio: ["ignore", "pipe", "pipe"]
// to
//   stdio: ["pipe", "pipe", "pipe"]
// so the server can deliver the user's UI answer as one JSON line to the
// engine's writable stdin. Without the pipe the child.stdin.write path in
// index.mjs is dead -- the `child.stdin?.write === "function"` guard short-
// circuits and the registry entry never gets a writer.
//
// These tests do NOT depend on a real Claude/OpenCode install. They use a
// tiny inline stub child that:
//   1. Reads ONE line from stdin
//   2. Echoes it on stdout so the test can assert on the exact payload
//   3. Exits 0
//
// The two cases pin the per-engine writer call-site shape -- Claude writes
// `{ type: "ui_response", requestId, value }`, OpenCode writes the same shape
// (mirrors index.mjs L4344 + L4573). If a future refactor drifts either
// adapter's JSON envelope or forgets to install the writer, these tests fail
// before the integration.
//
// Run (from repo root):
//
//     node --test idupi-server/test/ui-request-stdio.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * Inline stub child script. Reads ONE line from stdin, echoes it verbatim to
 * stdout, then exits 0. The parent asserts the echoed payload is exactly the
 * JSON line it wrote -- which is the only thing the server can deliver.
 */
const STUB_BODY = `
let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
    buf += chunk;
    let nl = buf.indexOf("\\n");
    if (nl >= 0) {
        const line = buf.slice(0, nl);
        process.stdout.write(line + "\\n");
        process.exit(0);
    }
});
// If the parent never writes, surface a clear failure instead of hanging.
setTimeout(() => {
    process.stdout.write("\\"no-stdin-line\\"\\n");
    process.exit(1);
}, 2000);
`.trim();

/**
 * Spawns a child with the same stdio shape index.mjs uses for Claude +
 * OpenCode (`["pipe", "pipe", "pipe"]`), writes one JSON line to it via
 * `child.stdin.write`, and returns the echoed payload as a parsed object.
 *
 * Reuses Node itself as the interpreter so we don't depend on the user's
 * Claude/OpenCode install. The stub script is fed via `-e` to keep this file
 * self-contained.
 */
function deliverUiResponse({ value, requestId }) {
    return deliverLine(JSON.stringify({ type: "ui_response", requestId, value }) + "\n");
}

/**
 * Echoes one raw JSON line through the same pipe-child seam and returns the
 * parsed payload. Used to pin the exact envelope each engine writer produces
 * (Claude/OpenCode: `ui_response`; Pi: `extension_ui_response`).
 */
function deliverLine(line) {
    return new Promise((resolve, reject) => {
        const child = spawn(
            process.execPath,
            ["-e", STUB_BODY],
            {
                cwd: repoRoot,
                windowsHide: true,
                // The contract under test: `stdio[0]` must be "pipe" so the
                // server can deliver answers. Anything else (the historical
                // "ignore") makes `child.stdin.write === undefined` and the
                // answer never reaches the engine.
                stdio: ["pipe", "pipe", "pipe"],
                env: process.env,
            },
        );

        let stdoutBuf = "";
        child.stdout.on("data", (chunk) => {
            stdoutBuf += chunk.toString("utf8");
        });

        let stderrBuf = "";
        child.stderr.on("data", (chunk) => {
            stderrBuf += chunk.toString("utf8");
        });

        child.on("error", (err) => reject(err));
        child.on("close", (code) => {
            if (code !== 0) {
                reject(new Error(
                    `stub child exited with code=${code}; stderr=${stderrBuf.trim()}`,
                ));
                return;
            }
            const echoed = stdoutBuf.trim();
            if (!echoed) {
                reject(new Error("stub child wrote nothing to stdout"));
                return;
            }
            try {
                resolve(JSON.parse(echoed));
            } catch (err) {
                reject(new Error(
                    `stub child stdout was not valid JSON: ${echoed}`,
                ));
            }
        });

        // The exact JSON line the server writes on the engine's stdin
        // (index.mjs L4344 for Claude, L4573 for OpenCode). Pinning the
        // envelope shape here is the regression guard.
        child.stdin.write(line, (err) => {
            if (err) reject(err);
        });
        child.stdin.end();
    });
}

// ============================================================================
// Claude adapter path (index.mjs L4344)
// ============================================================================

test("Claude-style writer delivers one JSON line via a writable stdin pipe", async () => {
    const echoed = await deliverUiResponse({
        requestId: "uir_claude_smoke",
        value: "B",
    });
    assert.equal(echoed.type, "ui_response", "envelope MUST include type=ui_response");
    assert.equal(echoed.requestId, "uir_claude_smoke");
    assert.equal(echoed.value, "B", "value MUST reach the engine verbatim");
});

test("Claude-style writer preserves a JSON boolean for confirm-true (not the string \"true\")", async () => {
    // Same wire-shape guard as the Android-side test: a confirm answer
    // arrives as the JSON literal `true`, not the text "true" (the historical
    // value.toString() bug).
    const echoed = await deliverUiResponse({
        requestId: "uir_claude_confirm",
        value: true,
    });
    assert.strictEqual(echoed.value, true);
    assert.notStrictEqual(echoed.value, "true");
});

test("Claude-style writer preserves a free-text input verbatim", async () => {
    const echoed = await deliverUiResponse({
        requestId: "uir_claude_input",
        value: "hello world",
    });
    assert.strictEqual(echoed.value, "hello world");
});

// ============================================================================
// OpenCode adapter path (index.mjs L4573)
// ============================================================================

test("OpenCode-style writer delivers one JSON line via a writable stdin pipe", async () => {
    const echoed = await deliverUiResponse({
        requestId: "uir_opencode_smoke",
        value: "A",
    });
    assert.equal(echoed.type, "ui_response");
    assert.equal(echoed.requestId, "uir_opencode_smoke");
    assert.equal(echoed.value, "A");
});

test("OpenCode-style writer preserves a JSON boolean for confirm-true", async () => {
    const echoed = await deliverUiResponse({
        requestId: "uir_opencode_confirm",
        value: false,
    });
    assert.strictEqual(echoed.value, false);
    assert.notStrictEqual(echoed.value, "false");
});

// ============================================================================
// Pi adapter path (index.mjs extension_ui_response writer)
//
// Pi's RPC protocol correlates a UI answer by the `id` the `extension_ui_request`
// frame carried (verified against Pi 0.84.0 rpc-mode handleInputLine), so the
// writer MUST address the frame with the PI request id — never the registry's
// requestId — and use Pi's per-method fields:
//   select/input → { type, id, value }          (option text / free text)
//   confirm      → { type, id, confirmed }      (boolean)
//   expiry       → { type, id, cancelled: true } (blanket cancel for confirm/input)
// The pre-fix envelope { type: "ui_response", requestId, value } was silently
// ignored by Pi: the dialog stayed open until the 300s taskkill.
// ============================================================================

test("Pi-style writer addresses the frame with the PI request id (select answer)", async () => {
    // The id Pi put on the extension_ui_request frame — NOT the registry requestId.
    const piRequestId = "e39b608a-dbde-4b49-ae3d-d87fc73791cf";
    const echoed = await deliverLine(JSON.stringify({
        type: "extension_ui_response",
        id: piRequestId,
        value: "1. A — Choose option A.",
    }) + "\n");
    assert.equal(echoed.type, "extension_ui_response");
    assert.equal(echoed.id, piRequestId, "id MUST be the Pi request id");
    assert.equal(echoed.value, "1. A — Choose option A.");
    assert.equal(echoed.requestId, undefined, "the registry requestId must NOT leak into the Pi frame");
});

test("Pi-style writer sends confirmed for a confirm answer", async () => {
    const echoed = await deliverLine(JSON.stringify({
        type: "extension_ui_response",
        id: "pi-confirm-1",
        confirmed: true,
    }) + "\n");
    assert.equal(echoed.type, "extension_ui_response");
    assert.strictEqual(echoed.confirmed, true);
    assert.equal(echoed.cancelled, undefined);
});

test("Pi-style writer sends cancelled:true for the blanket expiry decision", async () => {
    // The registry's auto-approve decision for confirm/input is { cancelled: true };
    // Pi maps it to the correlated cancelled frame (verified exact capture).
    const echoed = await deliverLine(JSON.stringify({
        type: "extension_ui_response",
        id: "pi-expire-1",
        cancelled: true,
    }) + "\n");
    assert.equal(echoed.type, "extension_ui_response");
    assert.strictEqual(echoed.cancelled, true);
});

// ============================================================================
// stdio shape contract (the bit that bit us in Phase 3)
// ============================================================================

test("a child spawned with stdio[0]=ignore would have made child.stdin.write unavailable", async () => {
    // The negative control: with stdio[0]="ignore" Node exposes
    // `child.stdin` as `null`, which is exactly what the Phase 3 fix
    // replaced. We assert the negative shape so any future regression to the
    // old stdio surfaces here, NOT in production.
    const child = spawn(
        process.execPath,
        ["-e", "process.exit(0)"],
        {
            cwd: repoRoot,
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
            env: process.env,
        },
    );
    assert.equal(child.stdin, null, "stdio[0]='ignore' -> child.stdin IS null");
    // The current Claude/OpenCode spawn uses ["pipe","pipe","pipe"]:
    const goodChild = spawn(
        process.execPath,
        ["-e", "process.exit(0)"],
        {
            cwd: repoRoot,
            windowsHide: true,
            stdio: ["pipe", "pipe", "pipe"],
            env: process.env,
        },
    );
    assert.notEqual(goodChild.stdin, null, "stdio[0]='pipe' -> child.stdin IS writable");
    // Drain both so the parent doesn't see a child-leak warning.
    goodChild.stdin?.end();
    child.unref?.();
    goodChild.unref?.();
});
