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

// --- Hardening (2026-11-22 audit): the lexical check alone let a symlink or
// NTFS junction inside a project read files anywhere on the host, and the tree
// walk followed directory links into cycles. Uses a real junction (no admin
// rights needed on Windows; a dir symlink elsewhere). ---

import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { getProjectFilesTree } from "../lib/project-files.mjs";

function withSandbox(fn) {
    const base = mkdtempSync(join(tmpdir(), "idupi-guard-"));
    const project = join(base, "project");
    const outside = join(base, "outside");
    mkdirSync(join(project, "src"), { recursive: true });
    mkdirSync(outside);
    writeFileSync(join(project, "src", "main.kt"), "fun main() {}");
    writeFileSync(join(outside, "secret.txt"), "top secret");
    symlinkSync(outside, join(project, "innocent"), process.platform === "win32" ? "junction" : "dir");
    // A cycle: a link inside the project pointing back at the project.
    symlinkSync(project, join(project, "src", "loop"), process.platform === "win32" ? "junction" : "dir");
    try {
        fn({ project, outside });
    } finally {
        rmSync(base, { recursive: true, force: true });
    }
}

test("a link inside the project cannot be used to read files outside it", () => {
    withSandbox(({ project }) => {
        assert.equal(resolveProjectFilePath(project, "innocent/secret.txt").ok, false);
        assert.equal(resolveProjectFilePath(project, "src/main.kt").ok, true);
    });
});

test("root casing differences do not break containment on Windows", { skip: process.platform !== "win32" }, () => {
    withSandbox(({ project }) => {
        assert.equal(resolveProjectFilePath(project.toUpperCase(), "src/main.kt").ok, true);
    });
});

test("the files tree skips symlinks/junctions instead of following them", () => {
    withSandbox(({ project }) => {
        const tree = getProjectFilesTree(project);
        const names = tree.map((n) => n.name).sort();
        assert.deepEqual(names, ["src"]);
        const src = tree.find((n) => n.name === "src");
        assert.deepEqual(src.children.map((n) => n.name), ["main.kt"]);
    });
});
