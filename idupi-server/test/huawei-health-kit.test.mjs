// Tests for the disabled Huawei Health Kit connector (phone-health, T4). The
// module only reads a JSON shape and reports { enabled, configured, reason };
// there is no network client to fake.
//
// Run (from repo root):
//   node --test idupi-server/test/huawei-health-kit.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import {
    HUAWEI_CLIENT_SECRET_ENV,
    futureHealthSources,
    huaweiHealthKitStatus,
} from "../lib/huawei-health-kit.mjs";

const silentLog = { warn() {} };
const SECRET = "super-secret-value";

const COMPLETE_CONFIG = {
    enabled: true,
    clientId: "1234567890",
    redirectUri: "idupi://oauth/huawei-health",
    scopes: [
        "https://www.huawei.com/healthkit/step.read",
        "https://www.huawei.com/healthkit/sleep.read",
    ],
};

async function tempDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-huawei-kit-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

async function writeConfig(dir, value) {
    await writeFile(join(dir, "huawei-health-kit.json"), JSON.stringify(value), "utf8");
}

test("a missing config file reports disabled and unconfigured", async (t) => {
    const dir = await tempDir(t);
    const status = huaweiHealthKitStatus({ dataDir: dir, env: {}, log: silentLog });
    assert.deepEqual(status, { enabled: false, configured: false, reason: "disabled" });
});

test("a corrupt config file never throws", async (t) => {
    const dir = await tempDir(t);
    await writeFile(join(dir, "huawei-health-kit.json"), "{broken", "utf8");
    const status = huaweiHealthKitStatus({ dataDir: dir, env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET }, log: silentLog });
    assert.deepEqual(status, { enabled: false, configured: false, reason: "disabled" });
});

test("enabled with every field and the env secret reports ready", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, COMPLETE_CONFIG);
    const status = huaweiHealthKitStatus({
        dataDir: dir,
        env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET },
        log: silentLog,
    });
    assert.deepEqual(status, { enabled: true, configured: true, reason: "ready" });
});

test("enabled without the secret names the missing variable", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, COMPLETE_CONFIG);
    const status = huaweiHealthKitStatus({ dataDir: dir, env: {}, log: silentLog });
    assert.equal(status.enabled, true);
    assert.equal(status.configured, false);
    assert.match(status.reason, /^missing_config:/);
    assert.match(status.reason, new RegExp(HUAWEI_CLIENT_SECRET_ENV));
});

test("missing client fields are reported, not invented", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, { enabled: true, clientId: "123", scopes: [] });
    const status = huaweiHealthKitStatus({
        dataDir: dir,
        env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET },
        log: silentLog,
    });
    assert.equal(status.configured, false);
    assert.match(status.reason, /redirectUri/);
    assert.match(status.reason, /scopes/);
    assert.doesNotMatch(status.reason, /clientId/);
});

test("the secret never travels in the config file", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, { ...COMPLETE_CONFIG, clientSecret: "should-not-be-here" });
    const status = huaweiHealthKitStatus({ dataDir: dir, env: {}, log: silentLog });
    assert.equal(status.configured, false);
    assert.match(status.reason, new RegExp(HUAWEI_CLIENT_SECRET_ENV));
});

test("a complete but disabled config reports registered_but_disabled", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, { ...COMPLETE_CONFIG, enabled: false });
    const status = huaweiHealthKitStatus({
        dataDir: dir,
        env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET },
        log: silentLog,
    });
    assert.deepEqual(status, { enabled: false, configured: true, reason: "registered_but_disabled" });
});

test("a custom secret variable name is honored", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, { ...COMPLETE_CONFIG, clientSecretEnv: "MY_HUAWEI_SECRET" });
    assert.equal(
        huaweiHealthKitStatus({ dataDir: dir, env: { MY_HUAWEI_SECRET: SECRET }, log: silentLog }).configured,
        true,
    );
    assert.equal(
        huaweiHealthKitStatus({ dataDir: dir, env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET }, log: silentLog }).configured,
        false,
    );
});

test("futureHealthSources lists the planned source without captures", async (t) => {
    const dir = await tempDir(t);
    await writeConfig(dir, { ...COMPLETE_CONFIG, enabled: false });
    const listed = futureHealthSources({
        dataDir: dir,
        env: { [HUAWEI_CLIENT_SECRET_ENV]: SECRET },
        log: silentLog,
    });
    assert.equal(listed.length, 1);
    assert.equal(listed[0].id, "huawei_health_kit");
    assert.equal(listed[0].label, "Huawei Health Kit");
    assert.equal(listed[0].enabled, false);
    assert.equal(listed[0].configured, true);
    assert.equal(listed[0].lastCaptureAt, null);
    assert.equal(listed[0].lastDate, null);
});
