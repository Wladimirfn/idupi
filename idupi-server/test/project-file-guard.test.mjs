import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";

import { resolveProjectFilePath } from "../lib/project-files.mjs";

// The file-content endpoint once did join(projectRoot, userPath) with NO
// containment check: ?path=../../../anything read arbitrary files, and a
// Windows-absolute userPath made join() DISCARD the project root entirely.
// This guard is the whole reason the endpoint is safe.

const ROOT = join("C:", "projects", "mi-proyecto");

test("a normal relative file resolves inside the project", () => {
    const r = resolveProjectFilePath(ROOT, "src/main.kt");
    assert.equal(r.ok, true);
    assert.equal(r.path.toLowerCase(), join(ROOT, "src", "main.kt").toLowerCase());
});

test("dot-dot traversal outside the project is rejected", () => {
    assert.equal(resolveProjectFilePath(ROOT, "../../secretos.txt").ok, false);
    assert.equal(resolveProjectFilePath(ROOT, "src/../../../../etc/hosts").ok, false);
});

test("an absolute user path cannot discard the project root", () => {
    // join(root, "C:\\Windows\\x") silently returns C:\Windows\x -- the exact
    // bug. The guard must refuse it instead of resolving it.
    assert.equal(resolveProjectFilePath(ROOT, "C:\\Windows\\system32\\drivers\\etc\\hosts").ok, false);
});

test("a path that merely CONTAINS dot-dot but stays inside is allowed", () => {
    // A directory legitimately named "..data" or "a..b" is not an escape.
    const r = resolveProjectFilePath(ROOT, "src/..data/file.txt");
    assert.equal(r.ok, true);
});

test("backslashes traverse exactly like forward slashes", () => {
    assert.equal(resolveProjectFilePath(ROOT, "..\\..\\claves.txt").ok, false);
});
