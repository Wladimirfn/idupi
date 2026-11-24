// Tests for the health store (phone-health, T1): validation, merge, retention
// and persistence. Times are UTC; day keys use the phone's tzOffsetMinutes.
//
// Run (from repo root):
//   node --test idupi-server/test/health-store.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import {
    createHealthStore,
    mergeMetrics,
    METRIC_KEYS,
    normalizeMetrics,
    RETENTION_DAYS,
} from "../lib/health-store.mjs";

const silentLog = { warn() {} };
const NOW = new Date("2026-11-24T15:00:00.000Z"); // 12:00 at -180
const TODAY = "2026-11-24";

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-health-store-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

function makeStore(dir, now = () => NOW) {
    return createHealthStore({ dataDir: dir, now, log: silentLog });
}

function sample(overrides = {}) {
    return {
        date: TODAY,
        source: "huawei_health",
        sourceApp: "com.huawei.health",
        metrics: { steps: 2444, sleepMinutes: 509 },
        capturedAt: "2026-11-24T10:00:00.000Z",
        ...overrides,
    };
}

test("normalizeMetrics drops nulls and rejects unknown or insane keys", () => {
    assert.deepEqual(normalizeMetrics({ steps: 100, heartRateBpm: null }), { steps: 100 });
    assert.throws(() => normalizeMetrics({ invented: 1 }), /unknown metric/);
    assert.throws(() => normalizeMetrics({ steps: 999999 }), /outside/);
    assert.throws(() => normalizeMetrics({ steps: 1.5 }), /integer/);
    assert.throws(() => normalizeMetrics({ sleepStages: { deep: 10, nap: 5 } }), /not a known stage/);
    assert.deepEqual(
        normalizeMetrics({ sleepStages: { deep: 90, awake: null } }),
        { sleepStages: { deep: 90 } },
    );
    assert.deepEqual(
        normalizeMetrics({ topApps: [{ label: "Instagram", minutes: 42 }] }),
        { topApps: [{ label: "Instagram", minutes: 42 }] },
    );
    assert.throws(() => normalizeMetrics({ topApps: [{ label: "", minutes: 1 }] }), /label/);
});

test("mergeMetrics keeps older keys and lets newer non-null values win", () => {
    assert.deepEqual(
        mergeMetrics({ steps: 1, sleepMinutes: 480 }, { steps: 2, sleepMinutes: null }),
        { steps: 2, sleepMinutes: 480 },
    );
    assert.deepEqual(
        mergeMetrics({ sleepStages: { deep: 90 } }, { sleepStages: { rem: 60 } }),
        { sleepStages: { deep: 90, rem: 60 } },
    );
});

test("recordSamples merges sources into one day view with per-metric sources", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);

    store.recordSamples({
        tzOffsetMinutes: -180,
        samples: [sample()],
    });
    const result = store.recordSamples({
        tzOffsetMinutes: -180,
        samples: [sample({
            source: "health_connect",
            sourceApp: "com.sec.android.app.shealth",
            metrics: { steps: 2500, heartRateBpm: 68 },
            capturedAt: "2026-11-24T11:00:00.000Z",
        })],
    });
    assert.equal(result.ok, true);
    assert.equal(result.stored, 1);

    const summary = store.summary({ days: 7 });
    assert.equal(summary.rangeDays, 7);
    assert.equal(summary.days.length, 7);
    const today = summary.days[summary.days.length - 1];
    assert.equal(today.date, TODAY);
    assert.equal(today.metrics.steps, 2500);
    assert.equal(today.metrics.heartRateBpm, 68);
    assert.equal(today.metrics.sleepMinutes, 509);
    assert.equal(today.sources.steps, "health_connect");
    assert.equal(today.sources.sleepMinutes, "huawei_health");
    assert.equal(summary.latest.date, TODAY);
    assert.equal(summary.tzOffsetMinutes, -180);
});

test("the newest capture per source/day wins and older values survive", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);

    store.recordSamples({
        samples: [sample({ metrics: { steps: 1000, sleepMinutes: 400 } })],
    });
    store.recordSamples({
        samples: [sample({
            metrics: { steps: 1500, heartRateBpm: 70 },
            capturedAt: "2026-11-24T12:00:00.000Z",
        })],
    });
    const summary = store.summary({ days: 1 });
    const today = summary.days[0];
    assert.equal(today.metrics.steps, 1500);
    assert.equal(today.metrics.sleepMinutes, 400);
    assert.equal(today.metrics.heartRateBpm, 70);
    assert.equal(today.sources.steps, "huawei_health");
    assert.equal(today.sources.sleepMinutes, "huawei_health");
    assert.equal(store.sampleCount(), 1);
});

test("validate rejects future dates, old dates and bad tz offsets", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);

    assert.throws(
        () => store.recordSamples({ samples: [sample({ date: "2026-11-25" })] }),
        /future/,
    );
    assert.throws(
        () => store.recordSamples({ samples: [sample({ date: "2026-06-01" })] }),
        /retention/,
    );
    assert.throws(
        () => store.recordSamples({ tzOffsetMinutes: 1000, samples: [] }),
        /tzOffsetMinutes/,
    );
    assert.throws(
        () => store.recordSamples({ samples: [sample({ source: "Bad Source" })] }),
        /source/,
    );
});

test("a rejected upload leaves no half-applied state", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);
    store.recordSamples({
        tzOffsetMinutes: -180,
        samples: [sample()],
        installedApps: [{ packageName: "com.huawei.health", label: "Huawei Health", route: "hidden_display" }],
    });

    assert.throws(() => store.recordSamples({
        tzOffsetMinutes: 120,
        samples: [sample({ metrics: { nonsense: 1 } })],
    }));
    assert.equal(store.tzOffsetMinutes(), -180);
    assert.equal(store.installedApps().length, 1);
});

test("installed apps are validated, deduped and updated", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);

    const result = store.recordSamples({
        samples: [],
        installedApps: [
            { packageName: "com.huawei.health", label: "Huawei Health", route: "hidden_display" },
            { packageName: "com.hihonor.health", label: "Honor Health", route: "hidden_display" },
        ],
    });
    assert.equal(result.installedApps, 2);

    store.recordSamples({
        samples: [],
        installedApps: [
            { packageName: "com.huawei.health", label: "Huawei Health 2", route: "health_connect" },
        ],
    });
    const apps = store.installedApps();
    assert.equal(apps.length, 2);
    const huawei = apps.find((entry) => entry.packageName === "com.huawei.health");
    assert.equal(huawei.route, "health_connect");

    assert.throws(
        () => store.recordSamples({ installedApps: [{ packageName: "bad package", route: "x" }] }),
        /packageName/,
    );
});

test("store state survives a reload from disk", async (t) => {
    const dir = await makeDir(t);
    makeStore(dir).recordSamples({ tzOffsetMinutes: -180, samples: [sample()] });

    const reloaded = makeStore(dir);
    const summary = reloaded.summary({ days: 1 });
    assert.equal(summary.days[0].metrics.steps, 2444);
    assert.equal(reloaded.tzOffsetMinutes(), -180);

    const raw = JSON.parse(await readFile(join(dir, "health.json"), "utf8"));
    assert.equal(raw.samples.length, 1);
    assert.equal(raw.tzOffsetMinutes, -180);
});

test("samples older than the retention window are pruned on the next write", async (t) => {
    const dir = await makeDir(t);
    const old = "2026-05-01";
    await writeFile(
        join(dir, "health.json"),
        JSON.stringify({
            version: 1,
            tzOffsetMinutes: -180,
            samples: [{
                date: old,
                source: "huawei_health",
                sourceApp: null,
                metrics: { steps: 1 },
                capturedAt: "2026-05-01T12:00:00.000Z",
            }],
            installedApps: [],
        }),
        "utf8",
    );
    const store = makeStore(dir);
    const result = store.recordSamples({ samples: [sample()] });
    assert.equal(result.pruned, 1);
    assert.equal(store.sampleCount(), 1);
    assert.ok(RETENTION_DAYS >= 120);

    // The old day is outside the reported window too.
    const summary = store.summary({ days: RETENTION_DAYS });
    assert.equal(summary.days.some((entry) => entry.date === old), false);
});

test("sources reports each source with its last capture", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);
    store.recordSamples({
        samples: [
            sample(),
            sample({
                source: "wellbeing",
                sourceApp: "com.android.settings",
                metrics: { screenTimeMinutes: 120, unlocks: 40 },
                capturedAt: "2026-11-24T13:00:00.000Z",
            }),
        ],
    });
    store.recordSamples({
        installedApps: [{ packageName: "com.huawei.health", label: "Huawei Health", route: "hidden_display" }],
    });

    const payload = store.sources();
    const huawei = payload.sources.find((entry) => entry.id === "huawei_health");
    assert.equal(huawei.lastDate, TODAY);
    assert.equal(huawei.lastCaptureAt, "2026-11-24T10:00:00.000Z");
    const wellbeing = payload.sources.find((entry) => entry.id === "wellbeing");
    assert.equal(wellbeing.lastCaptureAt, "2026-11-24T13:00:00.000Z");
    assert.equal(payload.installedApps.length, 1);
    assert.ok(payload.sources.some((entry) => entry.id === "health_connect"));
});

test("summary rejects a days value outside the accepted range", async (t) => {
    const dir = await makeDir(t);
    const store = makeStore(dir);
    assert.throws(() => store.summary({ days: 0 }), /days/);
    assert.throws(() => store.summary({ days: RETENTION_DAYS + 1 }), /days/);
    assert.ok(METRIC_KEYS.includes("steps"));
    assert.ok(METRIC_KEYS.includes("topApps"));
});
