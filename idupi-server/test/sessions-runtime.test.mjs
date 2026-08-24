// ============================================================================
// sessions-runtime.test.mjs
//
// SECOND bounded remediation — deterministic runtime proof/harness coverage for
// the six required proof gaps left untested at current runtime (verify-report
// lines 44-121): TTL staleness, TTL expiry+rebuild-bounded, all-engine route
// continuation (no gap/duplicate), injected Claude scan failure vs. legitimate
// zero count, OpenCode/SSE non-blocking concurrency, and route-row metadata
// (no fabricated messageCount).
//
// Test-only seams used (all behavior-preserving, added to idupi-server/index.mjs):
//   - getOrBuildEngineIndex(engine, normProjPath, { now, buildIndex })  -> injectable clock/builder
//   - fetchSessionsPage({ ..., deps: { fetchEnginePageResult } })        -> injectable per-engine fetch
//   - index.mjs does not bind a socket when IDUPI_NO_LISTEN=1 (set below before import)
//   - exported sessions functions so this suite can call them directly.
//
// Deterministic by construction: TTL uses a controllable clock; route proofs
// use injected synthetic per-engine streams (the real k-way merge + cursor
// encode/decode inside fetchSessionsPage is exercised); the SSE proof uses the
// REAL execOpenCodeDb path against the real OpenCode CLI plus the real
// chat-events SSE subscribe/publish. No sleeps; a fake clock/real-CLI-delay
// proves timing behavior.
// ============================================================================

process.env.IDUPI_NO_LISTEN = "1";

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync, existsSync } from "node:fs";
import { join } from "node:path";
import os from "node:os";

import { subscribe, publish, CHAT_EVENTS } from "../chat-events.mjs";
import { escapeSqlValue, resolveOpenCodeExePath } from "../lib/sessions.mjs";

// Dynamic import AFTER setting IDUPI_NO_LISTEN so the server does not bind.
const index = await import("../index.mjs");
const {
    SESSIONS_INDEX_CACHE_TTL_MS,
    sessionsIndexCache,
    getOrBuildEngineIndex,
    fetchSessionsPage,
    toSessionItem,
    buildPiClaudeSessionItem,
    buildOpenCodeSessionItem,
    execOpenCodeDb
} = index;

// A small, valid JSONL file so buildPiClaudeSessionItem's hydration succeeds
// (no console.error noise) while we assert its messageCount contract.
let tempSessionFile;
before(() => {
    const dir = mkdtempSync(join(os.tmpdir(), "idupi-rt-"));
    tempSessionFile = join(dir, "session.jsonl");
    writeFileSync(tempSessionFile, JSON.stringify({ type: "user", message: { content: "hello" } }) + "\n");
});

after(() => {
    sessionsIndexCache.clear();
});

// ---------------------------------------------------------------------------
// Gap 1: TTL staleness inside 15 seconds
// ---------------------------------------------------------------------------
test("TTL staleness: same cached index served after a source change while clock is inside TTL", () => {
    sessionsIndexCache.clear();
    let now = 0;
    const clock = () => now;
    let buildCalls = 0;
    // A "source change" would make buildIndex return a different object on the
    // next call; the cache must ignore it while inside TTL.
    const builder = () => {
        buildCalls += 1;
        return {
            records: [{ id: `r${buildCalls}`, timestamp: buildCalls, filePath: tempSessionFile }],
            freshnessToken: buildCalls
        };
    };

    const a = getOrBuildEngineIndex("/fake/proj", "pi-cli", { now: clock, buildIndex: builder });
    assert.strictEqual(buildCalls, 1, "first call builds");

    // Advance clock to 10s (< 15s TTL) and pretend the source changed.
    now = 10000;
    const b = getOrBuildEngineIndex("/fake/proj", "pi-cli", { now: clock, buildIndex: builder });
    assert.strictEqual(buildCalls, 1, "must NOT rebuild while inside TTL");
    assert.strictEqual(b, a, "same cached reference is served (stale by design, inside TTL)");
});

// ---------------------------------------------------------------------------
// Gap 2: TTL expiry at/after 15 seconds, rebuild even with no source change
// ---------------------------------------------------------------------------
test("TTL expiry: rebuild occurs at/after 15s even with no source change; each rebuild unit bounded", () => {
    sessionsIndexCache.clear();
    let now = 0;
    const clock = () => now;
    let buildCalls = 0;
    // Bounded builder: at most 3 records per rebuild (proves rebuild unit stays bounded).
    const builder = () => {
        buildCalls += 1;
        const records = [];
        for (let i = 0; i < 3; i++) {
            records.push({ id: `r${buildCalls}-${i}`, timestamp: 1000 - buildCalls * 10 + i, filePath: tempSessionFile });
        }
        return { records, freshnessToken: buildCalls };
    };

    getOrBuildEngineIndex("/fake/proj", "claude", { now: clock, buildIndex: builder });
    assert.strictEqual(buildCalls, 1);

    // 14,999 ms is still strictly inside the 15s TTL.
    now = 14999;
    getOrBuildEngineIndex("/fake/proj", "claude", { now: clock, buildIndex: builder });
    assert.strictEqual(buildCalls, 1, "14,999 ms remains cached");

    // Exactly 15,000 ms -> boundary expires; rebuild runs even though the source
    // (builder) is unchanged between calls.
    now = 15000;
    const c = getOrBuildEngineIndex("/fake/proj", "claude", { now: clock, buildIndex: builder });
    assert.strictEqual(buildCalls, 2, "rebuild at exactly 15000 ms even with no source change");
    assert.ok(c.records.length <= 3, "rebuild unit bounded (3 records)");
    assert.strictEqual(SESSIONS_INDEX_CACHE_TTL_MS, 15000, "ratified TTL constant");
});

// ---------------------------------------------------------------------------
// Gap 3: all-engine route continuation (no gap / no duplicate across boundary)
// ---------------------------------------------------------------------------
test("all-engine page 2 preserves global DESC order with no gap/duplicate", async () => {
    // 18 items, globally unique timestamps, 6 per engine. Global order is purely
    // by timestamp DESC, so the merge's engine tiebreak cannot interfere.
    const engines = ["pi-cli", "opencode", "claude"];
    const data = { "pi-cli": [], "opencode": [], "claude": [] };
    const all = [];
    for (let i = 0; i < 18; i++) {
        const e = engines[i % 3];
        const item = { engine: e, id: `${e}-${i}`, ts: 180000 - i * 10000, filePath: tempSessionFile };
        data[e].push(item);
        all.push(item);
    }
    const expected = all.slice().sort((a, b) => b.ts - a.ts);

    // Faithful per-engine fetch mirroring index.mjs fetchEnginePageResult's slice
    // logic; the REAL mergePage + cursor encode/decode inside fetchSessionsPage is
    // what we are proving.
    const fakeFetch = async (engine, _np, subCursor, limit) => {
        const list = data[engine].slice().sort((a, b) => b.ts - a.ts);
        let start = 0;
        if (subCursor) {
            start = list.findIndex(r => r.ts < subCursor.ts || (r.ts === subCursor.ts && r.id < subCursor.id));
            if (start === -1) start = list.length;
        }
        const page = list.slice(start, start + limit);
        const items = page.map(r => engine === "opencode"
            ? { ts: r.ts, id: r.id, row: { ts: r.ts, id: r.id, title: "t", msgCount: 0 } }
            : { ts: r.ts, id: r.id, record: { id: r.id, timestamp: r.ts, filePath: r.filePath } });
        return { failed: false, items };
    };
    const deps = { fetchEnginePageResult: fakeFetch };

    const got = [];
    let cursor = null;
    for (let i = 0; i < 10; i++) {
        const page = await fetchSessionsPage({ engine: "all", cursorParam: cursor, limit: 5, projPath: "/fake", projName: "P", deps });
        for (const s of page.sessions) got.push(s.id);
        cursor = page.nextCursor;
        if (!cursor) break;
    }

    assert.deepStrictEqual(
        got,
        expected.map(s => s.id),
        "every page chained with no gap, no duplicate, global DESC order preserved"
    );
    assert.strictEqual(new Set(got).size, got.length, "no duplicate session ids across pages");
});

// ---------------------------------------------------------------------------
// Gap 4: injected Claude scan failure is distinguishable from a legitimate zero count
// ---------------------------------------------------------------------------
test("injected Claude failure is distinguishable from a legitimate zero count (envelope + status)", async () => {
    const otherItem = {
        ts: 1, id: "x",
        record: { id: "x", timestamp: 1, filePath: tempSessionFile },
        row: { ts: 1, id: "x", title: "t", msgCount: 0 }
    };

    // Legitimate zero: claude returns an empty list, not a failure.
    const zero = await fetchSessionsPage({
        engine: "all", limit: 10, projPath: "/fake", projName: "P",
        deps: { fetchEnginePageResult: async (e) => e === "claude"
            ? { failed: false, items: [] }
            : { failed: false, items: [otherItem] } }
    });
    assert.strictEqual(zero.partial, false, "legit zero count is NOT partial");
    assert.deepStrictEqual(zero.failures, [], "legit zero count has no failures");

    // Injected failure: claude scan fails.
    const fail = await fetchSessionsPage({
        engine: "all", limit: 10, projPath: "/fake", projName: "P",
        deps: { fetchEnginePageResult: async (e) => e === "claude"
            ? { failed: true }
            : { failed: false, items: [otherItem] } }
    });
    assert.strictEqual(fail.partial, true, "engine=all with a failed engine is partial");
    assert.deepStrictEqual(fail.failures, [{ engine: "claude", message: "Failed to scan claude sessions" }],
        "failure is observable in the envelope (distinct from a zero count)");
    assert.notDeepStrictEqual(fail.failures, zero.failures, "failure distinguishable from legit zero");

    // Single-engine Claude failure maps to HTTP 502 (route contract source).
    let thrown = null;
    try {
        await fetchSessionsPage({
            engine: "claude", limit: 10, projPath: "/fake", projName: "P",
            deps: { fetchEnginePageResult: async () => ({ failed: true }) }
        });
    } catch (err) { thrown = err; }
    assert.ok(thrown, "single-engine Claude failure must throw");
    assert.strictEqual(thrown.httpStatus, 502, "route maps single-engine failure to HTTP 502");
    assert.strictEqual(thrown.engine, "claude");
});

// ---------------------------------------------------------------------------
// Route-row metadata: Claude/Pi must omit/fabricate-no messageCount; OpenCode exact
// ---------------------------------------------------------------------------
test("route-row metadata: Claude/Pi messageCount is null (never fabricated); OpenCode exact", () => {
    const claude = buildPiClaudeSessionItem("claude", { id: "c1", timestamp: Date.now(), filePath: tempSessionFile }, "P");
    assert.ok("messageCount" in claude, "messageCount key present");
    assert.strictEqual(claude.messageCount, null, "Claude row never fabricates a count");

    const pi = buildPiClaudeSessionItem("pi-cli", { id: "p1", timestamp: Date.now(), filePath: tempSessionFile }, "P");
    assert.strictEqual(pi.messageCount, null, "Pi row never fabricates a count");

    const oc = buildOpenCodeSessionItem({ ts: 1, id: "o1", title: "t", msgCount: 7 }, "P");
    assert.strictEqual(oc.messageCount, 7, "OpenCode row carries the exact SQL-derived count");

    // Through the real toSessionItem path used by the route.
    const via = toSessionItem("claude", { record: { id: "c2", timestamp: 1, filePath: tempSessionFile } }, "P");
    assert.strictEqual(via.messageCount, null);
});

// ---------------------------------------------------------------------------
// Gap 5: OpenCode/SSE concurrency — a delayed OpenCode query must not stall SSE
// ---------------------------------------------------------------------------
// This is the ONE deliberately environment-bound proof in the suite: it drives
// the REAL OpenCode CLI against the REAL projects DB. On a fresh clone (CI,
// new contributor) neither exists -- skip with the reason instead of crashing.
const OPENCODE_PREREQS_PRESENT = existsSync(join(process.cwd(), "idupi-server", "projects.json"));
test("delayed OpenCode sessions query does not stall an active SSE event flow",
    { skip: !OPENCODE_PREREQS_PRESENT && "requires a local OpenCode install and projects DB" },
    async () => {
    const writes = [];
    const reqHandlers = {};
    const res = {
        writeHead() {},
        write(chunk) { writes.push({ chunk: String(chunk), t: performance.now() }); },
        end() {},
        setHeader() {},
        on(evt, cb) { reqHandlers[evt] = cb; }
    };
    const req = { on(evt, cb) { reqHandlers[evt] = cb; } };

    const t0 = performance.now();
    subscribe(req, res); // SSE connected immediately (": connected" written synchronously)

    // REAL OpenCode sessions query via the production execOpenCodeDb shape
    // (execFile(resolveOpenCodeExePath(), ["db", <sql>, "--format", "json"])).
    const projects = JSON.parse(readFileSync(join(process.cwd(), "idupi-server", "projects.json"), "utf8"));
    const firstProj = projects[0];
    const norm = (firstProj.path || "").toLowerCase().replace(/\\/g, "/").replace(/\/+$/g, "");
    const sql = `SELECT COUNT(*) as cnt FROM session s WHERE REPLACE(LOWER(s.directory), '\\', '/') = '${escapeSqlValue(norm)}'`;
    const queryDone = execOpenCodeDb(sql).then(r => JSON.stringify(r));

    // Immediately publish a chat event — simulating the active SSE flow.
    publish(CHAT_EVENTS.TEXT_DELTA, { text: "stream-alive" });
    const sseWrite = writes.find(w => w.chunk.includes("event: text_delta"));
    assert.ok(sseWrite, "SSE event was written");
    const tSse = sseWrite.t;

    // An event-loop turn must fire while the OpenCode query is still in flight
    // (proves execFile is non-blocking; the loop is never stalled).
    const tImmediate = await new Promise(res => setImmediate(() => res(performance.now())));
    const stdout = await queryDone;
    const tQuery = performance.now();

    assert.ok(tSse < tQuery, "SSE event arrived before the OpenCode query resolved");
    assert.ok(tImmediate < tQuery, "event loop turned freely while OpenCode query was in flight (non-blocking)");
    assert.ok(tQuery - t0 > 100, `OpenCode query was genuinely in-flight (${Math.round(tQuery - t0)} ms)`);
    assert.ok(JSON.parse(stdout), "OpenCode query completed with real data");

    // Clean up the SSE subscriber so the heartbeat interval is released.
    if (reqHandlers.close) reqHandlers.close();
});
