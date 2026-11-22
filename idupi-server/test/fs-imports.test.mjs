// ============================================================================
// idupi-server/test/fs-imports.test.mjs
//
// Regression guard: index.mjs called mkdirSync/unlinkSync without importing
// them, so POST /api/v1/orchestrator/profiles/delete always answered 500 with
// `ReferenceError: unlinkSync is not defined` (and profiles/save did the same
// with mkdirSync on a fresh machine). index.mjs starts the HTTP server on
// import, so this checks the lexical invariant directly: every bare call to a
// node:fs function must be named in the node:fs import.
//
// Run (from repo root):
//   node --test idupi-server/test/fs-imports.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

function importedFsNames(src) {
    const m = src.match(/import\s*\{([^}]*)\}\s*from\s*["']node:fs["']/);
    assert.ok(m, "index.mjs must import from node:fs");
    return new Set(m[1].split(",").map((s) => s.trim().split(/\s+as\s+/).pop()).filter(Boolean));
}

test("every bare node:fs call in index.mjs is imported", () => {
    const imported = importedFsNames(source);
    const fsFunctions = Object.keys(fs).filter((k) => typeof fs[k] === "function" && /Sync$/.test(k));
    const missing = fsFunctions.filter((name) => {
        // Bare call: not a property access (`.name(`) and not part of a longer identifier.
        const bareCall = new RegExp(`(^|[^.\\w$])${name}\\s*\\(`, "m");
        return bareCall.test(source) && !imported.has(name);
    });
    assert.deepEqual(missing, [], `fs functions called but not imported: ${missing.join(", ")}`);
});
