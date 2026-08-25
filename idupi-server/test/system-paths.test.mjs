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
    assert.equal(guard("/home/dev/proyecto"), false);
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
