// ============================================================================
// idupi-server/test/exec-buffer.test.mjs
//
// `opencode export <session>` on a large session blew Node's default 1 MB
// stdout buffer, threw ENOBUFS, and the history endpoint degraded into a 404 --
// which the app showed as "error al reanudar la sesión: 404". Reproduced: 2 MB
// of stdout fails with ENOBUFS without maxBuffer and succeeds with it.
//
// Every synchronous exec in the server had the same gap, including one that
// runs an arbitrary user-supplied command. This checks the whole class rather
// than the single call that happened to break first.
//
// Run (from repo root):
//   node --test idupi-server/test/exec-buffer.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { execSync } from "node:child_process";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const lines = readFileSync(SERVER_FILE, "utf8").split("\n");

test("the 1 MB default is real: this is the failure being guarded against", () => {
    const big = 'node -e "process.stdout.write(\'x\'.repeat(2*1024*1024))"';
    assert.throws(() => execSync(big, { encoding: "utf8" }), (err) => err.code === "ENOBUFS");
    const out = execSync(big, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    assert.equal(out.length, 2 * 1024 * 1024);
});

test("every synchronous exec bounds its output explicitly", () => {
    const offenders = [];
    lines.forEach((line, i) => {
        if (!/\bexec(File)?Sync\s*\(/.test(line)) return;
        if (/^\s*(\/\/|\*)/.test(line)) return; // comment
        // Options may continue onto the next lines.
        const window = lines.slice(i, i + 4).join(" ");
        if (!/maxBuffer/.test(window)) offenders.push(`${i + 1}: ${line.trim().slice(0, 90)}`);
    });
    assert.deepEqual(offenders, [], `exec calls without maxBuffer:\n${offenders.join("\n")}`);
});

test("the shared limit is declared once, not sprinkled as magic numbers", () => {
    const decl = lines.findIndex((l) => /^const EXEC_MAX_BUFFER\s*=/.test(l));
    assert.notEqual(decl, -1, "EXEC_MAX_BUFFER is not declared at module scope");
    const literals = lines.filter((l) => /maxBuffer:\s*\d/.test(l));
    assert.deepEqual(literals, [], `maxBuffer given as a literal instead of the shared constant:\n${literals.join("\n")}`);
});
