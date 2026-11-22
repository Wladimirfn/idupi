// ============================================================================
// idupi-server/test/profile-path.test.mjs
//
// Security regression: SDD profile routes built `<profilesDir>/<profileId>.json`
// straight from the request body, so `{"profileId":"../../.gentle-ai/state"}`
// could read, overwrite or delete any .json file on the host. Profile ids must
// pass an allowlist before they ever reach a filesystem path.
//
// Run (from repo root):
//   node --test idupi-server/test/profile-path.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";

import { profileFilePath, profilesDir, isSafeProfileId } from "../lib/orchestrator/profile-path.mjs";
import { handleProfileApply, handleProfileApplyWithPresets } from "../lib/orchestrator/routes.mjs";

const HOME = join("C:", "fake-home");

test("accepts ids produced by the Android/server sanitizer", () => {
    for (const id of ["strong", "mid", "cheap", "mi-perfil", "perfil_2", "-"]) {
        assert.equal(isSafeProfileId(id), true, id);
        assert.equal(profileFilePath(id, HOME), join(profilesDir(HOME), `${id}.json`));
    }
});

test("rejects traversal, separators, absolute paths and non-strings", () => {
    const bad = ["../x", "..\\x", "a/b", "a\\b", "C:\\Windows\\x", "/etc/passwd", ".", "..", "", "a.json",
        "x".repeat(129), null, undefined, 42, {}, ["strong"]];
    for (const id of bad) {
        assert.equal(isSafeProfileId(id), false, String(id));
        assert.equal(profileFilePath(id, HOME), null, String(id));
    }
});

test("profiles/apply refuses an unsafe profileId with 400 before touching disk", async () => {
    const presets = { strong: { modelAssignments: {} } };
    const r1 = await handleProfileApplyWithPresets(presets, { profileId: "../../.gentle-ai/state" });
    assert.equal(r1.status, 400);
    const r2 = await handleProfileApply({ profileId: "..\\..\\x" });
    assert.equal(r2.status, 400);
});
