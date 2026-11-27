// ============================================================================
// idupi-server/test/claude-spawn-scope.test.mjs
//
// resolveClaudeSpawnTarget() was declared inside handleRequest, so it only
// existed while an HTTP request was being handled. resolveInboxAgentCommand()
// lives at module scope and calls it for the "claude" agent; the bubble agent
// (always claude) therefore failed every turn with
// "resolveClaudeSpawnTarget is not defined". Its cache was also reset on every
// request. The resolver must be a module-scope declaration, outside the
// request handler.
//
// Run (from repo root):
//   node --test idupi-server/test/claude-spawn-scope.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const lines = readFileSync(SERVER_FILE, "utf8").split(/\r?\n/);

const lineOf = (pattern) => lines.findIndex((line) => pattern.test(line));

test("the Claude spawn resolver is declared at module scope, before the request handler", () => {
    const resolver = lineOf(/^function resolveClaudeSpawnTarget\(/);
    const cache = lineOf(/^let claudeSpawnTargetCache\b/);
    const handler = lineOf(/^const handleRequest\s*=/);
    assert.ok(handler >= 0, "handleRequest not found");
    assert.ok(resolver >= 0, "resolveClaudeSpawnTarget must be a top-level declaration (column 0)");
    assert.ok(cache >= 0, "its cache must be a top-level declaration too");
    assert.ok(resolver < handler, "resolveClaudeSpawnTarget must not live inside handleRequest");
    assert.ok(cache < handler, "claudeSpawnTargetCache must not be reset per request");
});

test("resolveInboxAgentCommand only calls a resolver it can see", () => {
    const caller = lineOf(/^function resolveInboxAgentCommand\(/);
    const handler = lineOf(/^const handleRequest\s*=/);
    assert.ok(caller >= 0 && caller < handler, "resolveInboxAgentCommand is expected at module scope");
    assert.equal(lines.filter((line) => /^\s+function resolveClaudeSpawnTarget\(/.test(line)).length, 0,
        "no nested redeclaration may shadow the module-scope resolver");
});
