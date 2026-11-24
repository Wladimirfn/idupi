// ============================================================================
// idupi-server/test/opencode-cli.test.mjs
//
// Unit coverage for the OpenCode v2 CLI adapter (lib/opencode-cli.mjs) with
// injected execFile / execFileSync / sqlite handles. No real OpenCode install
// or store is touched here; the live shape is exercised by the read-only smoke
// and by sessions-runtime's real-CLI concurrency proof.
//
// Run (from repo root):
//   node --test idupi-server/test/opencode-cli.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";

import {
    createOpenCodeCli,
    MODELS_CACHE_TTL_MS,
    SESSION_LIST_CACHE_TTL_MS
} from "../lib/opencode-cli.mjs";

const PROJ = "C:\\Users\\elmas\\AndroidStudioProjects\\IDUPI";
const OTHER = "C:\\Users\\elmas\\AndroidStudioProjects\\Other";

function sessionRow(id, updated, { title = "t", directory = PROJ } = {}) {
    return { id, title, updated, created: updated - 10, projectId: "p1", directory };
}

/** Callback-style execFile fake; `handler` returns the stdout text or { error }. */
function fakeExecFile(handler = () => "[]") {
    const calls = [];
    const fn = (file, args, opts, cb) => {
        const call = { file, args, opts };
        calls.push(call);
        let out;
        try {
            out = handler({ file, args, opts, call: calls.length });
        } catch (err) {
            queueMicrotask(() => cb(err));
            return;
        }
        if (out && out.error) {
            queueMicrotask(() => cb(out.error));
            return;
        }
        const stdout = typeof out === "string" ? out : JSON.stringify(out);
        queueMicrotask(() => cb(null, stdout, ""));
    };
    fn.calls = calls;
    return fn;
}

/** node:sqlite-shaped fake plus its call log. */
function fakeCountsDb(countsById, opts = {}) {
    const state = { opened: 0, closed: 0, queries: [], lastParams: null };
    state.open = () => {
        state.opened += 1;
        if (opts.openError) throw new Error(opts.openError);
        return {
            prepare(sql) {
                state.queries.push(sql);
                if (opts.prepareError) throw new Error(opts.prepareError);
                return {
                    all(...params) {
                        state.lastParams = params;
                        return params
                            .filter((id) => countsById[id] !== undefined)
                            .map((id) => ({ session_id: id, n: countsById[id] }));
                    }
                };
            },
            close() { state.closed += 1; }
        };
    };
    return state;
}

function makeCli({
    execFile = fakeExecFile(),
    execFileSync = () => "",
    statDirectory = () => true,
    countsDb = fakeCountsDb({}),
    now = () => 1000,
    warn = () => {}
} = {}) {
    const cli = createOpenCodeCli({
        execFile,
        execFileSync,
        resolveExe: () => "C:\\fake\\opencode.exe",
        statDirectory,
        openCountsDb: countsDb.open,
        countsDbPath: () => "C:\\fake\\opencode.db",
        now,
        warn
    });
    return { cli, countsDb };
}

// ---------------------------------------------------------------------------
// Listing: parse, order, cursor, directory filter
// ---------------------------------------------------------------------------

test("session list JSON is parsed, sorted newest first, and paged in JS", async () => {
    const execFile = fakeExecFile(() => [
        sessionRow("ses_a", 100),
        sessionRow("ses_b", 300),
        sessionRow("ses_c", 200)
    ]);
    const { cli } = makeCli({ execFile, countsDb: fakeCountsDb({ ses_b: 5, ses_c: 4 }) });

    const page = await cli.fetchPage({
        projectDir: PROJ,
        normProjectPath: PROJ.toLowerCase().replace(/\\/g, "/"),
        subCursor: null,
        limit: 2,
        includeAll: true
    });

    assert.deepStrictEqual(page.map((p) => p.id), ["ses_b", "ses_c"], "updated DESC, limited");
    assert.deepStrictEqual(page[0], { ts: 300, id: "ses_b", title: "t", msgCount: 5 });

    const call = execFile.calls[0];
    assert.strictEqual(call.file, "C:\\fake\\opencode.exe");
    assert.deepStrictEqual(call.args, ["session", "list", "--format", "json", "--max-count", "1000"]);
    assert.strictEqual(call.opts.cwd, PROJ, "cwd is the original-case project directory");
    assert.strictEqual(call.opts.windowsHide, true);
    assert.ok(call.opts.timeout > 0);
});

test("rows from another directory are filtered out defensively", async () => {
    const execFile = fakeExecFile(() => [
        sessionRow("ses_here", 10),
        sessionRow("ses_elsewhere", 20, { directory: OTHER }),
        sessionRow("ses_slashcase", 30, { directory: "c:/users/elmas/androidstudioprojects/idupi" })
    ]);
    const { cli } = makeCli({ execFile, countsDb: fakeCountsDb({}) });

    const page = await cli.fetchPage({
        projectDir: PROJ,
        normProjectPath: PROJ,
        subCursor: null,
        limit: 10,
        includeAll: true
    });

    assert.deepStrictEqual(page.map((p) => p.id), ["ses_slashcase", "ses_here"],
        "normalized directory equality (case/separators) still matches");
});

test("cursor paging has no gap or duplicate across the boundary (ts DESC, id DESC)", async () => {
    const rows = [
        sessionRow("ses_b", 300),
        sessionRow("ses_c", 200),
        sessionRow("ses_a", 200), // same ts as ses_c: larger id sorts first
        sessionRow("ses_d", 100)
    ];
    const execFile = fakeExecFile(() => rows);
    const { cli } = makeCli({ execFile, countsDb: fakeCountsDb({}) });

    const first = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 2, includeAll: true
    });
    assert.deepStrictEqual(first.map((p) => p.id), ["ses_b", "ses_c"]);

    const second = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ,
        subCursor: { ts: first[1].ts, id: first[1].id }, limit: 2, includeAll: true
    });
    assert.deepStrictEqual(second.map((p) => p.id), ["ses_a", "ses_d"],
        "tie broken by id DESC; no row skipped or repeated");
});

test("an invalid cursor is rejected instead of silently ignored", async () => {
    const { cli } = makeCli({ countsDb: fakeCountsDb({}) });
    await assert.rejects(
        cli.fetchPage({
            projectDir: PROJ, normProjectPath: PROJ,
            subCursor: { ts: "not-a-number", id: "ses_x" }, limit: 5, includeAll: true
        }),
        /Invalid OpenCode cursor/
    );
});

test("a project directory that no longer exists yields an empty list without invoking the CLI", async () => {
    const execFile = fakeExecFile();
    const { cli } = makeCli({ execFile, statDirectory: () => false, countsDb: fakeCountsDb({}) });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 5
    });
    const count = await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ });
    const latest = await cli.findLatestSession(PROJ);

    assert.deepStrictEqual(page, []);
    assert.strictEqual(count, 0);
    assert.strictEqual(latest, null);
    assert.strictEqual(execFile.calls.length, 0, "no CLI call for a missing directory");
});

test("broken JSON degrades to an empty page with one warning, never a throw", async () => {
    const warnings = [];
    const { cli } = makeCli({
        execFile: fakeExecFile(() => "not json at all"),
        countsDb: fakeCountsDb({}),
        warn: (m) => warnings.push(m)
    });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 5, includeAll: true
    });

    assert.deepStrictEqual(page, []);
    assert.strictEqual(warnings.length, 1);
    assert.match(warnings[0], /JSON inválido/);
});

test("legacy time_updated/time_created rows are normalized as well", async () => {
    const execFile = fakeExecFile(() => [
        { id: "ses_old", title: "old", time_updated: 42, time_created: 10, directory: PROJ }
    ]);
    const { cli } = makeCli({ execFile, countsDb: fakeCountsDb({}) });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 5, includeAll: true
    });
    assert.deepStrictEqual(page, [{ ts: 42, id: "ses_old", title: "old", msgCount: 0 }]);
});

// ---------------------------------------------------------------------------
// Cache
// ---------------------------------------------------------------------------

test("the parsed list is cached per directory for the TTL window", async () => {
    let now = 1000;
    let listCalls = 0;
    const execFile = fakeExecFile(() => {
        listCalls += 1;
        return [sessionRow("ses_new", listCalls)];
    });
    const { cli } = makeCli({
        execFile,
        countsDb: fakeCountsDb({ sessions: 0 }),
        now: () => now
    });

    const args = { projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 5, includeAll: true };
    await cli.fetchPage(args);
    await cli.fetchPage(args);
    assert.strictEqual(listCalls, 1, "second page inside the TTL reuses the parsed list");

    now += SESSION_LIST_CACHE_TTL_MS - 1;
    await cli.fetchPage(args);
    assert.strictEqual(listCalls, 1, "still cached just inside the TTL");

    now += 1;
    await cli.fetchPage(args);
    assert.strictEqual(listCalls, 2, "expired TTL triggers one fresh CLI call");
});

// ---------------------------------------------------------------------------
// Message counts: known vs unknown, noise filter on/off
// ---------------------------------------------------------------------------

test("known counts: one-exchange sessions are hidden and msgCount is exact", async () => {
    const execFile = fakeExecFile(() => [
        sessionRow("ses_real", 50),
        sessionRow("ses_noise", 40),
        sessionRow("ses_empty", 30)
    ]);
    const countsDb = fakeCountsDb({ ses_real: 3, ses_noise: 2, ses_empty: 0 });
    const { cli } = makeCli({ execFile, countsDb });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10
    });
    assert.deepStrictEqual(page, [{ ts: 50, id: "ses_real", title: "t", msgCount: 3 }],
        "<= 2 messages is noise; the count is the exact SQL value");

    const count = await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ });
    assert.strictEqual(count, 1, "the count applies the same filter as the listing");
});

test("the includeAll switch brings noisy sessions back with their exact counts", async () => {
    const execFile = fakeExecFile(() => [
        sessionRow("ses_real", 50),
        sessionRow("ses_noise", 40)
    ]);
    const countsDb = fakeCountsDb({ ses_real: 3, ses_noise: 2 });
    const { cli } = makeCli({ execFile, countsDb });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10, includeAll: true
    });
    assert.deepStrictEqual(page.map((p) => [p.id, p.msgCount]),
        [["ses_real", 3], ["ses_noise", 2]], "no filter; counts still exact");
    assert.strictEqual(await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ, includeAll: true }), 2);
});

test("unknown counts: no filter, msgCount null, exactly one warning, never a fabricated count", async () => {
    const warnings = [];
    const execFile = fakeExecFile(() => [
        sessionRow("ses_a", 50),
        sessionRow("ses_b", 40)
    ]);
    const countsDb = fakeCountsDb({}, { openError: "unable to open database file" });
    const { cli } = makeCli({ execFile, countsDb, warn: (m) => warnings.push(m) });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10
    });
    assert.deepStrictEqual(page, [
        { ts: 50, id: "ses_a", title: "t", msgCount: null },
        { ts: 40, id: "ses_b", title: "t", msgCount: null }
    ], "counts unknown -> the noise filter is skipped and no number is invented");

    // A second page must not log a second warning (one per adapter/process).
    await cli.fetchPage({ projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10 });
    assert.strictEqual(warnings.length, 1);
    assert.match(warnings[0], /counts=unknown/);
    assert.match(warnings[0], /unable to open database file/);

    const count = await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ });
    assert.strictEqual(count, 2, "unknown counts report the true, unfiltered count");
});

test("a missing v2 table/column degrades to unknown counts instead of failing", async () => {
    const warnings = [];
    const execFile = fakeExecFile(() => [sessionRow("ses_a", 50)]);
    const countsDb = fakeCountsDb({}, { prepareError: "no such table: session_message" });
    const { cli } = makeCli({ execFile, countsDb, warn: (m) => warnings.push(m) });

    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10
    });
    assert.deepStrictEqual(page, [{ ts: 50, id: "ses_a", title: "t", msgCount: null }]);
    assert.strictEqual(warnings.length, 1);
    assert.match(warnings[0], /no such table: session_message/);
    assert.strictEqual(countsDb.closed, 1, "the read-only handle is closed even after a failure");
});

test("message counts use bound parameters only (no id interpolated into SQL)", async () => {
    const execFile = fakeExecFile(() => [sessionRow("ses_a", 50), sessionRow("ses_b", 40)]);
    const countsDb = fakeCountsDb({ ses_a: 4, ses_b: 4 });
    const { cli } = makeCli({ execFile, countsDb });

    await cli.fetchPage({ projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10 });

    assert.strictEqual(countsDb.queries.length, 1);
    const sql = countsDb.queries[0];
    assert.match(sql, /FROM session_message WHERE session_id IN \(\?,\?\) GROUP BY session_id/);
    assert.ok(!sql.includes("ses_a") && !sql.includes("ses_b"), "session ids never reach the SQL string");
    assert.deepStrictEqual(countsDb.lastParams, ["ses_a", "ses_b"], "ids are bound as parameters");
});

// ---------------------------------------------------------------------------
// Latest-session lookup
// ---------------------------------------------------------------------------

test("latest-session lookup uses a fresh --max-count 1 call, bypassing the list cache", async () => {
    let newest = sessionRow("ses_first", 10);
    const execFile = fakeExecFile(({ args }) => {
        if (args[args.length - 1] === "1") return [newest];
        return [newest, sessionRow("ses_second", 5)];
    });
    const { cli } = makeCli({ execFile, countsDb: fakeCountsDb({}) });

    await cli.fetchPage({ projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 5, includeAll: true });
    assert.strictEqual(execFile.calls.length, 1);

    newest = sessionRow("ses_brand_new", 99);
    const latest = await cli.findLatestSession(PROJ);

    assert.strictEqual(latest, "ses_brand_new", "the session created after the cached listing is visible");
    assert.strictEqual(execFile.calls.length, 2, "the lookup does not reuse the stale cached list");
    assert.deepStrictEqual(execFile.calls[1].args,
        ["session", "list", "--format", "json", "--max-count", "1"]);
    assert.strictEqual(execFile.calls[1].opts.cwd, PROJ);
});

test("latest-session lookup never throws into the chat request", async () => {
    const warnings = [];
    const { cli } = makeCli({
        execFile: fakeExecFile(() => ({ error: new Error("opencode exited 1") })),
        countsDb: fakeCountsDb({}),
        warn: (m) => warnings.push(m)
    });

    const latest = await cli.findLatestSession(PROJ);
    assert.strictEqual(latest, null);
    assert.strictEqual(warnings.length, 1);
    assert.match(warnings[0], /última sesión/);
});

// ---------------------------------------------------------------------------
// Models: one cached catalog, provider prefix filter
// ---------------------------------------------------------------------------

test("models: one cached catalog call, providers filtered by prefix", () => {
    let now = 1000;
    let modelCalls = 0;
    const lines = [
        "Notice: something",
        "opencode-go/gpt-5.6-luna",
        "opencode-go/deepseek-v4-pro",
        "anthropic/claude-opus-5",
        "",
        "Error: provider not found"
    ];
    const execFileSync = () => {
        modelCalls += 1;
        return lines.join("\n");
    };
    const { cli } = makeCli({ execFileSync, now: () => now });

    assert.deepStrictEqual(cli.listAllModels(), [
        "opencode-go/gpt-5.6-luna",
        "opencode-go/deepseek-v4-pro",
        "anthropic/claude-opus-5"
    ]);
    assert.deepStrictEqual(cli.modelsForProvider("opencode-go"), [
        "opencode-go/gpt-5.6-luna",
        "opencode-go/deepseek-v4-pro"
    ]);
    assert.deepStrictEqual(cli.modelsForProvider("anthropic"), ["anthropic/claude-opus-5"]);
    assert.deepStrictEqual(cli.modelsForProvider("missing"), []);
    assert.strictEqual(modelCalls, 1, "all three reads share the 60 s cache");

    now += MODELS_CACHE_TTL_MS;
    cli.listAllModels();
    assert.strictEqual(modelCalls, 2, "the catalog is refreshed after the TTL");
});

test("models: a CLI failure rejects so the caller keeps its existing degradation path", () => {
    const { cli } = makeCli({
        execFileSync: () => { throw new Error("Unexpected positional argument"); }
    });
    assert.throws(() => cli.modelsForProvider("opencode-go"), /Unexpected positional argument/);
});
