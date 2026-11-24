// Tests for the health routes, refresh jobs, collector and scheduler
// (phone-health, T1). A real http.Server wires the handler like index.mjs does
// after requireAuth; the ADB bridge, collector and clock are fakes.
//
// Run (from repo root):
//   node --test idupi-server/test/health-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { createHealthStore } from "../lib/health-store.mjs";
import {
    createHealthCollector,
    createHealthRoutes,
    createHealthScheduler,
    healthDayKey,
} from "../lib/health-routes.mjs";

const silentLog = { warn() {}, info() {} };
const NOW = new Date("2026-11-24T15:00:00.000Z"); // 12:00 at -180
const TODAY = "2026-11-24";

function sampleBody(overrides = {}) {
    return {
        tzOffsetMinutes: -180,
        samples: [{
            date: TODAY,
            source: "huawei_health",
            sourceApp: "com.huawei.health",
            metrics: { steps: 2444, sleepMinutes: 509, heartRateBpm: 72 },
            capturedAt: "2026-11-24T10:00:00.000Z",
        }],
        installedApps: [
            { packageName: "com.huawei.health", label: "Huawei Health", route: "hidden_display" },
        ],
        ...overrides,
    };
}

function fakeAdb(overrides = {}) {
    return {
        hasSavedTargets: () => true,
        ensureConnected: async () => ({ ok: true, serial: "192.168.1.50:37123", via: "cached" }),
        status: async () => ({
            adb: { path: "adb.exe", found: true },
            scrcpy: { path: "scrcpy.exe", found: true, version: "5.0.1" },
            connected: true,
            serial: "192.168.1.50:37123",
            via: "cached",
            targets: [],
        }),
        pair: async ({ hostPort }) => ({ ok: true, host: hostPort.split(":")[0] }),
        connect: async ({ hostPort }) => ({ ok: true, serial: hostPort }),
        ...overrides,
    };
}

function makeCollector(overrides = {}) {
    return {
        collect: async ({ source }) => ({
            source,
            sourceApp: "com.huawei.health",
            metrics: { steps: 2444, sleepMinutes: 509 },
            capturedAt: NOW.toISOString(),
            ...overrides,
        }),
    };
}

async function startHarness(t, { collector = makeCollector(), adb = fakeAdb() } = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-health-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const store = createHealthStore({ dataDir: dir, now: () => NOW, log: silentLog });
    const routes = createHealthRoutes({
        store,
        adb,
        collector,
        now: () => NOW,
        log: silentLog,
    });
    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await routes.handle(req, res, pathname)) return;
            res.writeHead(418);
            res.end();
        } catch (err) {
            if (!res.headersSent) res.writeHead(500);
            res.end(String((err && err.message) || err));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    t.after(async () => {
        await new Promise((resolve) => server.close(resolve));
    });

    async function request(method, path, body) {
        const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), "utf8");
        const headers = {};
        if (payload) {
            headers["Content-Type"] = "application/json";
            headers["Content-Length"] = String(payload.length);
        }
        const response = await fetch(`${base}${path}`, { method, headers, body: payload });
        const text = await response.text();
        return {
            status: response.status,
            json: text ? JSON.parse(text) : null,
        };
    }
    return { routes, store, request, base, server };
}

async function waitForJob(request, id) {
    for (let attempt = 0; attempt < 50; attempt += 1) {
        const res = await request("GET", `/api/v1/health/refresh/${id}`);
        assert.equal(res.status, 200);
        const status = res.json?.job?.status;
        if (status === "done" || status === "failed") return res.json.job;
        await new Promise((resolve) => setTimeout(resolve, 5));
    }
    throw new Error("refresh job did not settle");
}

// --- samples, summary, sources --------------------------------------------

test("POST /samples stores the upload and GET /summary merges it", async (t) => {
    const harness = await startHarness(t);

    const posted = await harness.request("POST", "/api/v1/health/samples", sampleBody());
    assert.equal(posted.status, 200);
    assert.equal(posted.json.ok, true);
    assert.equal(posted.json.stored, 1);

    const summary = await harness.request("GET", "/api/v1/health/summary?days=1");
    assert.equal(summary.status, 200);
    assert.equal(summary.json.rangeDays, 1);
    assert.equal(summary.json.days[0].date, TODAY);
    assert.equal(summary.json.days[0].metrics.steps, 2444);
    assert.equal(summary.json.latest.date, TODAY);

    const sources = await harness.request("GET", "/api/v1/health/sources");
    assert.equal(sources.status, 200);
    assert.equal(sources.json.sources.find((entry) => entry.id === "huawei_health").lastDate, TODAY);
    assert.equal(sources.json.installedApps[0].packageName, "com.huawei.health");
});

test("POST /samples rejects an unknown metric with 400 and stores nothing", async (t) => {
    const harness = await startHarness(t);
    const body = sampleBody();
    body.samples[0].metrics = { invented: 1 };
    const res = await harness.request("POST", "/api/v1/health/samples", body);
    assert.equal(res.status, 400);
    assert.equal(res.json.code, "INVALID_SAMPLE");
    assert.equal(harness.store.sampleCount(), 0);
});

test("GET /health root answers the default summary and rejects bad days", async (t) => {
    const harness = await startHarness(t);
    const root = await harness.request("GET", "/api/v1/health");
    assert.equal(root.status, 200);
    assert.equal(root.json.rangeDays, 7);

    const bad = await harness.request("GET", "/api/v1/health/summary?days=abc");
    assert.equal(bad.status, 400);
    assert.equal(bad.json.code, "INVALID_DAYS");

    const tooMany = await harness.request("GET", "/api/v1/health/summary?days=500");
    assert.equal(tooMany.status, 400);
});

test("GET /samples is 405", async (t) => {
    const harness = await startHarness(t);
    const res = await harness.request("GET", "/api/v1/health/samples");
    assert.equal(res.status, 405);
});

// --- refresh jobs ----------------------------------------------------------

test("POST /refresh starts a job that stores the parsed sample", async (t) => {
    const harness = await startHarness(t);
    const started = await harness.request("POST", "/api/v1/health/refresh", {});
    assert.equal(started.status, 202);
    assert.match(started.json.job.id, /^hgj-[0-9a-f]{12}$/);

    const job = await waitForJob(harness.request, started.json.job.id);
    assert.equal(job.status, "done");
    assert.equal(job.results[0].ok, true);
    assert.equal(job.results[0].metrics, 2);

    const summary = await harness.request("GET", "/api/v1/health/summary?days=1");
    assert.equal(summary.json.days[0].metrics.steps, 2444);
    assert.equal(summary.json.days[0].metrics.sleepMinutes, 509);
});

test("a second refresh with the same sources joins the running job", async (t) => {
    let release;
    const gate = new Promise((resolve) => {
        release = resolve;
    });
    const collector = {
        collect: async ({ source }) => {
            await gate;
            return {
                source,
                sourceApp: "com.huawei.health",
                metrics: { steps: 10 },
                capturedAt: NOW.toISOString(),
            };
        },
    };
    const harness = await startHarness(t, { collector });

    const first = await harness.request("POST", "/api/v1/health/refresh", {});
    const second = await harness.request("POST", "/api/v1/health/refresh", { sources: ["huawei_health"] });
    assert.equal(first.json.job.id, second.json.job.id);

    release();
    await waitForJob(harness.request, first.json.job.id);
});

test("POST /refresh rejects unsupported sources and malformed bodies", async (t) => {
    const harness = await startHarness(t);
    const badSource = await harness.request("POST", "/api/v1/health/refresh", {
        sources: ["samsung_health"],
    });
    assert.equal(badSource.status, 400);
    assert.equal(badSource.json.code, "INVALID_SOURCES");

    const badBody = await harness.request("POST", "/api/v1/health/refresh", []);
    assert.equal(badBody.status, 400);

    const missing = await harness.request("GET", "/api/v1/health/refresh/hgj-000000000000");
    assert.equal(missing.status, 404);
    const malformed = await harness.request("GET", "/api/v1/health/refresh/nope");
    assert.equal(malformed.status, 404);
});

test("a failing collector marks the job failed without leaking values", async (t) => {
    const collector = {
        collect: async () => {
            const err = new Error("could not read com.huawei.health:app_not_visible");
            err.code = "COLLECT_FAILED";
            throw err;
        },
    };
    const harness = await startHarness(t, { collector });
    const started = await harness.request("POST", "/api/v1/health/refresh", {});
    const job = await waitForJob(harness.request, started.json.job.id);
    assert.equal(job.status, "failed");
    assert.equal(job.results[0].ok, false);
    assert.equal(job.results[0].reason, "COLLECT_FAILED");
    assert.equal(job.error, "no health source could be read");
    assert.equal(harness.store.sampleCount(), 0);
});

// --- ADB routes ------------------------------------------------------------

test("GET /adb, POST /adb/pair and POST /adb/connect delegate to the manager", async (t) => {
    const calls = [];
    const adb = fakeAdb({
        pair: async (args) => {
            calls.push({ kind: "pair", args });
            return { ok: true };
        },
        connect: async (args) => {
            calls.push({ kind: "connect", args });
            return { ok: true, serial: args.hostPort };
        },
    });
    const harness = await startHarness(t, { adb });

    const status = await harness.request("GET", "/api/v1/health/adb");
    assert.equal(status.status, 200);
    assert.equal(status.json.connected, true);

    const pair = await harness.request("POST", "/api/v1/health/adb/pair", {
        hostPort: "192.168.1.50:37123",
        code: "123456",
    });
    assert.equal(pair.status, 200);
    assert.deepEqual(calls[0], {
        kind: "pair",
        args: { hostPort: "192.168.1.50:37123", code: "123456" },
    });

    const connect = await harness.request("POST", "/api/v1/health/adb/connect", {
        hostPort: "192.168.1.50:37123",
    });
    assert.equal(connect.status, 200);
    assert.equal(connect.json.serial, "192.168.1.50:37123");
});

test("ADB failures map to 400 and unexpected throws to 500", async (t) => {
    const harness = await startHarness(t, {
        adb: fakeAdb({
            pair: async () => ({ ok: false, reason: "invalid_code" }),
            connect: async () => ({ ok: false, reason: "connect_failed" }),
        }),
    });
    const pair = await harness.request("POST", "/api/v1/health/adb/pair", {
        hostPort: "192.168.1.50:37123",
        code: "000000",
    });
    assert.equal(pair.status, 400);
    assert.equal(pair.json.code, "PAIR_FAILED");

    const connect = await harness.request("POST", "/api/v1/health/adb/connect", {
        hostPort: "192.168.1.50:37123",
    });
    assert.equal(connect.status, 400);

    const broken = await startHarness(t, {
        adb: fakeAdb({
            status: async () => {
                throw new Error("boom");
            },
        }),
    });
    const status = await broken.request("GET", "/api/v1/health/adb");
    assert.equal(status.status, 500);
});

// --- collector -------------------------------------------------------------

test("the collector prefers an app the phone reported on the hidden-display route", async () => {
    const reads = [];
    const reader = {
        listAllowed: () => [
            { packageName: "com.huawei.health", source: "huawei_health" },
            { packageName: "com.hihonor.health", source: "huawei_health" },
        ],
        readApp: async ({ packageName }) => {
            reads.push(packageName);
            if (packageName === "com.hihonor.health") {
                return { ok: false, reason: "no_display" };
            }
            return { ok: true, nodes: [] };
        },
    };
    const store = {
        installedApps: () => [
            { packageName: "com.hihonor.health", route: "hidden_display" },
        ],
    };
    // parseHealthApp needs real nodes to produce metrics; stub the reader result
    // with one allowlisted step node.
    reader.readApp = async ({ packageName }) => {
        reads.push(packageName);
        return {
            ok: true,
            nodes: [{
                text: "1,000/10,000 pasos",
                resourceId: `${packageName}:id/step_text`,
                contentDesc: "",
                packageName,
                bounds: "[0,0][100,50]",
                rect: { x1: 0, y1: 0, x2: 100, y2: 50 },
            }],
        };
    };
    const collector = createHealthCollector({ reader, store, now: () => NOW });
    const result = await collector.collect({ source: "huawei_health" });
    assert.equal(reads[0], "com.hihonor.health");
    assert.equal(result.sourceApp, "com.hihonor.health");
    assert.equal(result.metrics.steps, 1000);
});

test("the collector throws COLLECT_FAILED when every app fails", async () => {
    const reader = {
        listAllowed: () => [{ packageName: "com.huawei.health", source: "huawei_health" }],
        readApp: async () => ({ ok: false, reason: "app_not_visible" }),
    };
    const collector = createHealthCollector({
        reader,
        store: { installedApps: () => [] },
        now: () => NOW,
    });
    await assert.rejects(
        () => collector.collect({ source: "huawei_health" }),
        (err) => err.code === "COLLECT_FAILED" && /app_not_visible/.test(err.message),
    );
    await assert.rejects(
        () => collector.collect({ source: "health_connect" }),
        (err) => err.code === "INVALID_SOURCE",
    );
});

// --- scheduler -------------------------------------------------------------

test("the scheduler skips without a target and never overlaps a running refresh", async () => {
    let saved = false;
    const adb = fakeAdb({ hasSavedTargets: () => saved });
    const scheduler = createHealthScheduler({
        adb,
        triggerRefresh: () => {
            throw new Error("must not refresh without a target");
        },
    });
    assert.deepEqual(await scheduler.runNow(), { ok: false, skipped: "no_target" });

    saved = true;
    let release;
    const gate = new Promise((resolve) => {
        release = resolve;
    });
    let triggered = 0;
    const running = createHealthScheduler({
        adb,
        triggerRefresh: () => {
            triggered += 1;
            return { job: { status: "running" }, promise: gate };
        },
    });
    const first = running.runNow();
    const second = await running.runNow();
    assert.deepEqual(second, { ok: false, skipped: "overlap" });
    assert.equal(triggered, 1);
    release({ status: "done" });
    assert.equal((await first).ok, true);
});

test("the scheduler skips quietly when adb is unreachable", async () => {
    const scheduler = createHealthScheduler({
        adb: fakeAdb({ ensureConnected: async () => ({ ok: false, reason: "unreachable" }) }),
        triggerRefresh: () => {
            throw new Error("must not refresh while adb is down");
        },
        log: silentLog,
    });
    assert.deepEqual(await scheduler.runNow(), {
        ok: false,
        skipped: "adb_unreachable",
        reason: "unreachable",
    });
});

test("the scheduler refreshes Huawei Health when the link is up", async () => {
    const requested = [];
    const scheduler = createHealthScheduler({
        adb: fakeAdb(),
        triggerRefresh: (sources, meta) => {
            requested.push({ sources, meta });
            return { job: { status: "done" }, promise: Promise.resolve({ status: "done" }) };
        },
    });
    const result = await scheduler.runNow();
    assert.equal(result.ok, true);
    assert.deepEqual(requested[0].sources, ["huawei_health"]);
    assert.equal(requested[0].meta.reason, "schedule");
});

test("healthDayKey uses the phone offset", () => {
    assert.equal(healthDayKey(new Date("2026-11-24T15:00:00.000Z"), -180), TODAY);
    assert.equal(healthDayKey(new Date("2026-11-24T15:00:00.000Z"), 120), "2026-11-24");
    assert.equal(healthDayKey(new Date("2026-11-24T22:30:00.000Z"), 120), "2026-11-25");
});
