import test from "node:test";
import assert from "node:assert/strict";

import { makeIsProtectedSystemPath } from "../lib/system-paths.mjs";

// Project removal can rmSync an entire folder recursively. This guard decides
// whether a path is system territory where that must NEVER happen. The old
// inline version protected shallow children only (C:\Windows\System32\config
// was deletable!) and missed whole roots like /usr/local and /opt.

const HOME = "C:\\Users\\elmas";
const guard = makeIsProtectedSystemPath(HOME);

test("system roots are protected at ANY depth", () => {
    assert.equal(guard("C:\\Windows"), true);
    assert.equal(guard("C:\\Windows\\System32\\config"), true);
    assert.equal(guard("/usr/local/share/myapp"), true);
    assert.equal(guard("/opt/myapp"), true);
});

test("user land stays deletable", () => {
    assert.equal(guard("C:\\Users\\elmas\\AndroidStudioProjects\\mi-proyecto"), false);
    // The operator's own POSIX projects (another account's tree is protected).
    assert.equal(makeIsProtectedSystemPath("/home/dev")("/home/dev/code/proyecto"), false);
});

test("the home directory itself and users/home roots are protected", () => {
    assert.equal(guard(HOME), true);
    assert.equal(guard("c:\\users"), true);
    assert.equal(guard("/home"), true);
});

test("drive roots and empties are protected", () => {
    assert.equal(guard("D:\\"), true);
    assert.equal(guard(""), true);
    assert.equal(guard(undefined), true);
});

test("trailing separators are normalized before comparing", () => {
    assert.equal(guard("C:\\Windows\\"), true);
    assert.equal(guard("/usr/local/"), true);
});

// --- Hardening (2026-11-22 audit): projects/remove with deleteFiles could
// rmSync the running IDUPI repo or whole user folders. ---

const SERVER_ROOT = "C:\\Users\\elmas\\AndroidStudioProjects\\IDUPI";
const hardened = makeIsProtectedSystemPath(HOME, {
    protectedTrees: [SERVER_ROOT],
    realpath: (p) => p,
});

test("the running server tree, its ancestors and its descendants are protected", () => {
    assert.equal(hardened(SERVER_ROOT), true);
    assert.equal(hardened(SERVER_ROOT + "\\idupi-server"), true);
    assert.equal(hardened("C:\\Users\\elmas\\AndroidStudioProjects"), true);
    // A sibling project stays deletable.
    assert.equal(hardened("C:\\Users\\elmas\\AndroidStudioProjects\\otro-proyecto"), false);
});

test("top-level home folders are protected; projects inside them are not", () => {
    for (const p of ["Desktop", "Documents", "Downloads", "AndroidStudioProjects"]) {
        assert.equal(hardened(`${HOME}\\${p}`), true, p);
    }
    assert.equal(hardened(`${HOME}\\Documents\\mi-app`), false);
});

test("home dot-directories and AppData are protected at any depth", () => {
    assert.equal(hardened(`${HOME}\\.ssh`), true);
    assert.equal(hardened(`${HOME}\\.config\\opencode`), true);
    assert.equal(hardened(`${HOME}\\AppData\\Roaming\\npm`), true);
});

test("other users' home folders are protected", () => {
    assert.equal(hardened("C:\\Users\\Public"), true);
    assert.equal(hardened("/home/otro"), true);
});

test("forward slashes, dot-dot segments and relative paths cannot sneak past", () => {
    assert.equal(hardened("C:/Windows/System32"), true);
    assert.equal(hardened(`${HOME}\\Documents\\mi-app\\..\\..\\..\\..\\Windows`), true);
    assert.equal(hardened("relative\\folder"), true);
});

// --- Cross-audit follow-ups (idu-pi audit-5, 2026-11-22) ---

test("other users' home trees are protected at any depth", () => {
    assert.equal(hardened("C:\\Users\\Alice\\Documents"), true);
    assert.equal(hardened("C:\\Users\\Alice\\Documents\\proyecto"), true);
    assert.equal(hardened("/home/alice/proyecto"), true);
    // ...while the operator's own projects stay deletable.
    assert.equal(hardened(`${HOME}\\Documents\\mi-app`), false);
});

test("a home directory behind a junction keeps its protections", () => {
    const realHome = "D:\\Homes\\elmas";
    const junctioned = makeIsProtectedSystemPath(HOME, {
        realpath: (p) => (p.toLowerCase().startsWith(HOME.toLowerCase()) ? realHome + p.slice(HOME.length) : p),
    });
    assert.equal(junctioned(`${HOME}\\AppData\\Roaming`), true);
    assert.equal(junctioned(`${HOME}\\.ssh`), true);
    assert.equal(junctioned(`${HOME}\\Documents\\mi-app`), false);
});

test("Windows system folders on a non-C: drive are protected", () => {
    const dDrive = makeIsProtectedSystemPath(HOME, {
        systemRoots: ["D:\\Windows", "D:\\Program Files", "D:\\ProgramData"],
        realpath: (p) => p,
    });
    assert.equal(dDrive("D:\\Windows\\System32"), true);
    assert.equal(dDrive("D:\\Program Files\\App"), true);
    assert.equal(dDrive("D:\\proyectos\\mi-app"), false);
});

test("namespace paths written with forward slashes fail closed", () => {
    assert.equal(hardened("//?/C:/Windows/System32"), true);
    assert.equal(hardened("//server/share/folder"), true);
});

test("a junction that resolves into protected territory is protected", () => {
    const viaJunction = makeIsProtectedSystemPath(HOME, {
        realpath: (p) => (p.endsWith("innocent-link") ? "C:\\Windows\\System32" : p),
    });
    assert.equal(viaJunction(`${HOME}\\Documents\\innocent-link`), true);
});
