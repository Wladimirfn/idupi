// ============================================================================
// idupi-server/test/module-scope.test.mjs
//
// Regression guard for a real production defect: AGENT_CLI_TIMEOUT_MS was
// declared INSIDE the http.createServer request handler. runClaudeCli and
// runOpenCodeCli are declared inside that same handler and could see it, but
// PiRpcManager is declared above it and could not -- so every Pi message threw
// `ReferenceError: AGENT_CLI_TIMEOUT_MS is not defined` and answered 500.
//
// The whole test suite passed while that was broken, because nothing here
// exercises the HTTP layer. This file checks the lexical invariant directly.
//
// Run (from repo root):
//   node --test idupi-server/test/module-scope.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const lines = readFileSync(SERVER_FILE, "utf8").split("\n");

/** 1-indexed line of the first line satisfying `pred`, or -1. */
function lineOf(pred) {
    const i = lines.findIndex(pred);
    return i < 0 ? -1 : i + 1;
}

const declLine = lineOf((l) => /^const AGENT_CLI_TIMEOUT_MS\s*=/.test(l));
const classLine = lineOf((l) => /^class PiRpcManager\b/.test(l));
const serverLine = lineOf((l) => /^const server = http\.createServer\(/.test(l));

test("AGENT_CLI_TIMEOUT_MS is declared at module scope (column 0)", () => {
    assert.notEqual(declLine, -1, "no module-scope declaration found");
});

test("AGENT_CLI_TIMEOUT_MS is visible to PiRpcManager, not just to the request handler", () => {
    assert.notEqual(classLine, -1);
    assert.notEqual(serverLine, -1);
    // Declared before the class that uses it...
    assert.ok(
        declLine < classLine,
        `declared at ${declLine} but PiRpcManager starts at ${classLine}`,
    );
    // ...and therefore also outside the createServer callback, which opens later.
    assert.ok(
        declLine < serverLine,
        `declared at ${declLine}, inside the handler that opens at ${serverLine}`,
    );
});

test("every AGENT_CLI_TIMEOUT_MS use sits below its single declaration", () => {
    const uses = [];
    lines.forEach((l, i) => {
        if (!l.includes("AGENT_CLI_TIMEOUT_MS")) return;
        if (/^\s*\/\//.test(l)) return; // comment-only line
        uses.push(i + 1);
    });
    const decls = uses.filter((n) => /const AGENT_CLI_TIMEOUT_MS/.test(lines[n - 1]));
    assert.equal(decls.length, 1, `expected exactly one declaration, got ${decls.length}`);
    for (const n of uses) {
        assert.ok(n >= declLine, `use at line ${n} precedes the declaration at ${declLine}`);
    }
});
