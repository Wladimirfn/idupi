import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { basename, join } from "node:path";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { pathToFileURL } from "node:url";

import { SERVER_DIR, REPO_ROOT, PROJECTS_JSON_FILE } from "../lib/server-paths.mjs";

test("SERVER_DIR is the idupi-server folder and REPO_ROOT its parent", () => {
    assert.equal(basename(SERVER_DIR), "idupi-server");
    assert.ok(existsSync(join(SERVER_DIR, "index.mjs")));
    assert.equal(join(REPO_ROOT, "idupi-server"), SERVER_DIR);
});

test("PROJECTS_JSON_FILE lives in idupi-server, not under a doubled folder", () => {
    assert.equal(PROJECTS_JSON_FILE, join(SERVER_DIR, "projects.json"));
});

test("paths do not depend on the working directory", () => {
    const moduleUrl = pathToFileURL(join(SERVER_DIR, "lib", "server-paths.mjs")).href;
    const out = execFileSync(
        process.execPath,
        ["--input-type=module", "-e", `import(${JSON.stringify(moduleUrl)}).then((m) => console.log(JSON.stringify(m)))`],
        { cwd: tmpdir(), encoding: "utf8" },
    );
    assert.deepEqual(JSON.parse(out), { PROJECTS_JSON_FILE, REPO_ROOT, SERVER_DIR });
});
