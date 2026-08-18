// ============================================================================
// idupi-server/test/sessions.test.mjs
//
// node:test suite for idupi-server/lib/sessions.mjs (PR1 scope only: SQL
// escaping, cursor encode/decode, and the combined-page k-way merge). Run:
//   node --test idupi-server/test/sessions.test.mjs
//
// This is the first automated Node test suite in idupi-server/ — it exists
// specifically because lib/sessions.mjs is where the design.md-mandated
// pure logic had to live to be testable at all.
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

import {
    escapeSqlValue,
    validateNumeric,
    SqlValueError,
    encodeCursor,
    decodeCursor,
    CursorDecodeError,
    ENGINES,
    DONE,
    mergePage,
    normalizePathForCompare,
    encodeProjectDirName,
    orderClaude,
    stripPiDirWrapper,
    piDirNamePreFilter,
    extractPiSessionMeta,
    matchOrderPi,
    resolveOpenCodeExePath,
    _resetOpenCodeExePathCacheForTests,
    buildClaudeIndex,
    buildPiIndex,
    buildIndex
} from "../lib/sessions.mjs";

// ----------------------------------------------------------------------------
// SQL Safety Contract (task 1.1) — Threat Matrix: SQL string construction
// ----------------------------------------------------------------------------

test("escapeSqlValue rejects a NUL byte", () => {
    assert.throws(
        () => escapeSqlValue("safe\u0000value"),
        (err) => err instanceof SqlValueError && err.reason === "nul-byte"
    );
});

test("escapeSqlValue rejects a ';' character (statement-chaining guard)", () => {
    assert.throws(
        () => escapeSqlValue("C:/proj; DROP TABLE session"),
        (err) => err instanceof SqlValueError && err.reason === "semicolon"
    );
});

test("escapeSqlValue doubles every single quote", () => {
    assert.equal(escapeSqlValue("no quotes here"), "no quotes here");
    assert.equal(escapeSqlValue("it's"), "it''s");
    assert.equal(escapeSqlValue("''"), "''''");
});

test("escapeSqlValue round-trips the O'Brien path example from the design", () => {
    // design.md SQL Safety Contract: `C:\Users\O'Brien\proj` must round-trip
    // to `'C:\Users\O''Brien\proj'` once wrapped in SQL literal quotes.
    const path = "C:\\Users\\O'Brien\\proj";
    const escaped = escapeSqlValue(path);
    assert.equal(escaped, "C:\\Users\\O''Brien\\proj");
    const literal = `'${escaped}'`;
    assert.equal(literal, "'C:\\Users\\O''Brien\\proj'");
});

test("escapeSqlValue rejects a NUL byte even when combined with other content", () => {
    assert.throws(
        () => escapeSqlValue("C:\\Users\\O'Brien\u0000\\proj"),
        (err) => err instanceof SqlValueError && err.reason === "nul-byte"
    );
});

test("escapeSqlValue rejects a non-string value", () => {
    assert.throws(
        () => escapeSqlValue(42),
        (err) => err instanceof SqlValueError && err.reason === "not-a-string"
    );
});

test("validateNumeric accepts a finite number within range", () => {
    assert.equal(validateNumeric(1786803563192), 1786803563192);
    assert.equal(validateNumeric(30, { min: 1, max: 200, integer: true }), 30);
});

test("validateNumeric rejects a non-numeric value", () => {
    assert.throws(
        () => validateNumeric("30"),
        (err) => err instanceof SqlValueError && err.reason === "not-a-number"
    );
    assert.throws(
        () => validateNumeric(Number.NaN),
        (err) => err instanceof SqlValueError && err.reason === "not-a-number"
    );
    assert.throws(
        () => validateNumeric(Infinity),
        (err) => err instanceof SqlValueError && err.reason === "not-a-number"
    );
});

test("validateNumeric rejects a non-integer when integer is required (limit)", () => {
    assert.throws(
        () => validateNumeric(30.5, { integer: true }),
        (err) => err instanceof SqlValueError && err.reason === "not-an-integer"
    );
});

test("validateNumeric rejects a value outside [min, max] (limit range [1, 200])", () => {
    assert.throws(
        () => validateNumeric(0, { min: 1, max: 200, integer: true }),
        (err) => err instanceof SqlValueError && err.reason === "out-of-range"
    );
    assert.throws(
        () => validateNumeric(201, { min: 1, max: 200, integer: true }),
        (err) => err instanceof SqlValueError && err.reason === "out-of-range"
    );
    assert.equal(validateNumeric(1, { min: 1, max: 200, integer: true }), 1);
    assert.equal(validateNumeric(200, { min: 1, max: 200, integer: true }), 200);
});

// ----------------------------------------------------------------------------
// Cursor & Pagination Contract (task 1.2)
// ----------------------------------------------------------------------------

test("encodeCursor/decodeCursor round-trip a per-engine {ts, id} cursor", () => {
    const cursor = { ts: 1786803598000, id: "abc123" };
    const token = encodeCursor(cursor);
    assert.equal(typeof token, "string");
    // base64url alphabet only: no '+', '/', or '=' padding on the wire.
    assert.match(token, /^[A-Za-z0-9\-_]+$/);
    assert.deepEqual(decodeCursor(token), cursor);
});

test("decodeCursor treats an absent/empty cursor as first page (null)", () => {
    assert.equal(decodeCursor(null), null);
    assert.equal(decodeCursor(undefined), null);
    assert.equal(decodeCursor(""), null);
});

test("decodeCursor rejects a malformed token", () => {
    assert.throws(() => decodeCursor("!!!not-valid-base64url!!!"), CursorDecodeError);
});

test("encodeCursor/decodeCursor round-trip the combined per-engine object, using the frozen wire keys", () => {
    const combined = {
        "pi-cli": { ts: 1786803598000, id: "abc123" },
        opencode: { ts: 1786803563192, id: "s_9f2" },
        claude: null
    };
    const token = encodeCursor(combined);
    const decoded = decodeCursor(token);
    assert.deepEqual(decoded, combined);
    assert.deepEqual(Object.keys(decoded).sort(), [...ENGINES].sort());
});

test("encodeCursor/decodeCursor round-trip the \"done\" sentinel per engine", () => {
    const combined = {
        "pi-cli": DONE,
        opencode: { ts: 1000, id: "x" },
        claude: DONE
    };
    const token = encodeCursor(combined);
    const decoded = decodeCursor(token);
    assert.equal(decoded["pi-cli"], "done");
    assert.equal(decoded.claude, "done");
    assert.deepEqual(decoded.opencode, { ts: 1000, id: "x" });
});

test("combined cursor wire encoding never contains an abbreviated engine key (pi/oc/cl)", () => {
    const combined = {
        "pi-cli": { ts: 1, id: "a" },
        opencode: { ts: 2, id: "b" },
        claude: DONE
    };
    const token = encodeCursor(combined);
    const decodedJson = JSON.stringify(decodeCursor(token));

    // The frozen wire values must be present verbatim.
    assert.match(decodedJson, /"pi-cli"/);
    assert.match(decodedJson, /"opencode"/);
    assert.match(decodedJson, /"claude"/);

    // No abbreviated key ever leaks onto the wire as a JSON object key.
    assert.doesNotMatch(decodedJson, /"pi":/);
    assert.doesNotMatch(decodedJson, /"oc":/);
    assert.doesNotMatch(decodedJson, /"cl":/);
});

test("cursor comparison uses (ts, id) as a tiebreaker — encode/decode preserves both fields independently", () => {
    const a = encodeCursor({ ts: 1000, id: "a" });
    const b = encodeCursor({ ts: 1000, id: "b" });
    assert.notEqual(a, b);
    assert.deepEqual(decodeCursor(a), { ts: 1000, id: "a" });
    assert.deepEqual(decodeCursor(b), { ts: 1000, id: "b" });
});

// ----------------------------------------------------------------------------
// Combined-page algorithm / mergePage (task 1.3)
// ----------------------------------------------------------------------------

function item(ts, id) {
    return { ts, id };
}

test("mergePage: rule 1 — a failed fetch leaves the sub-cursor unchanged and NOT done", () => {
    const previousCursor = { ts: 500, id: "prev" };
    const result = mergePage(10, {
        claude: { cursor: previousCursor, fetchResult: { failed: true } },
        opencode: { cursor: null, fetchResult: { failed: false, items: [item(900, "o1")] } }
    });

    assert.deepEqual(result.nextCursors.claude, previousCursor);
    assert.notEqual(result.nextCursors.claude, DONE);
    assert.equal(result.done, false);
});

test("mergePage: rule 2 — short fetch, fully emitted -> exhausted (\"done\")", () => {
    // n=2 < limit=10, and both items get emitted (nothing else competes).
    const result = mergePage(10, {
        claude: { cursor: null, fetchResult: { failed: false, items: [item(200, "c2"), item(100, "c1")] } }
    });

    assert.equal(result.nextCursors.claude, DONE);
    assert.equal(result.done, true);
    assert.equal(result.items.length, 2);
});

test("mergePage: rule 3 — n == limit && m == n (full fetch, fully emitted, ordinary single-engine page)", () => {
    const items = Array.from({ length: 5 }, (_, i) => item(100 - i, `c${i}`));
    const result = mergePage(5, {
        claude: { cursor: null, fetchResult: { failed: false, items } }
    });

    // n == limit == 5, m == 5 -> rule 2's "n < limit" clause fails, falls to rule 3.
    assert.notEqual(result.nextCursors.claude, DONE);
    assert.deepEqual(result.nextCursors.claude, { ts: items[4].ts, id: items[4].id });
    assert.equal(result.done, false);
});

test("mergePage: rule 4 — m == 0, fully crowded out, sub-cursor left unchanged", () => {
    const previousCursor = { ts: 5000, id: "prevClaude" };
    // Claude's fetch returns items far older than OpenCode's, so none of
    // Claude's items make the top-1 cut.
    const result = mergePage(1, {
        claude: { cursor: previousCursor, fetchResult: { failed: false, items: [item(100, "c1"), item(90, "c2")] } },
        opencode: { cursor: null, fetchResult: { failed: false, items: [item(9999, "o1")] } }
    });

    assert.deepEqual(result.nextCursors.claude, previousCursor);
    assert.notEqual(result.nextCursors.claude, DONE);
    assert.equal(result.items.length, 1);
    assert.equal(result.items[0].engine, "opencode");
});

test("mergePage: an engine already \"done\" is skipped entirely (never re-queried, no fetchResult expected)", () => {
    const result = mergePage(10, {
        claude: { cursor: DONE },
        opencode: { cursor: null, fetchResult: { failed: false, items: [item(100, "o1")] } }
    });

    assert.equal(result.nextCursors.claude, DONE);
    assert.equal(result.items.length, 1);
    assert.equal(result.items[0].engine, "opencode");
});

test("mergePage: nextCursor collapses to done=true only when every engine in scope is done", () => {
    // limit=1 with a full 1-item fetch: n == limit, so rule 2's "n < limit"
    // clause fails and this falls to rule 3 (m >= 1) — pi-cli is NOT done,
    // only claude (already done before this round) is.
    const result = mergePage(1, {
        claude: { cursor: DONE },
        "pi-cli": { cursor: null, fetchResult: { failed: false, items: [item(50, "p1")] } }
    });
    assert.notEqual(result.nextCursors["pi-cli"], DONE);
    assert.equal(result.done, false);
});

test("mergePage: all engines exhausted in one round -> done=true, nextCursor is null on the wire", () => {
    const result = mergePage(10, {
        claude: { cursor: null, fetchResult: { failed: false, items: [item(50, "c1")] } },
        "pi-cli": { cursor: DONE }
    });
    assert.equal(result.nextCursors.claude, DONE);
    assert.equal(result.done, true);
});

test("mergePage: identical (ts, id) across two engines breaks the tie by engine name ascending", () => {
    const result = mergePage(10, {
        "pi-cli": { cursor: null, fetchResult: { failed: false, items: [item(100, "same")] } },
        claude: { cursor: null, fetchResult: { failed: false, items: [item(100, "same")] } },
        opencode: { cursor: null, fetchResult: { failed: false, items: [item(100, "same")] } }
    });

    assert.equal(result.items.length, 3);
    assert.deepEqual(
        result.items.map((it) => it.engine),
        ["claude", "opencode", "pi-cli"] // alphabetical
    );
});

test("mergePage regression guard: 9 Claude items, only 2 emitted because 183 OpenCode sessions are newer (limit=30)", () => {
    // Worked counterexample from design.md's "Combined-page algorithm" step 5:
    // this exact scenario broke two earlier rounds of the design (the "done"
    // exhaustion rule reintroducing the 9->2 bug). Claude's remaining 7
    // sessions MUST stay reachable (sub-cursor NOT "done") rather than being
    // permanently dropped.
    const limit = 30;

    // Claude: 9 items. Top 2 are the newest of the whole 69-candidate set;
    // the remaining 7 are much older than everything else fetched this round.
    const claudeItems = [
        item(5000, "c1"),
        item(4999, "c2"),
        item(106, "c3"),
        item(105, "c4"),
        item(104, "c5"),
        item(103, "c6"),
        item(102, "c7"),
        item(101, "c8"),
        item(100, "c9")
    ];
    assert.equal(claudeItems.length, 9);

    // OpenCode: a full page of 30 items, all strictly between Claude's top 2
    // and Claude's 3rd-newest (183 sessions "newer than Claude's 3rd-newest"
    // in the design's narrative; here scaled down to a 30-item fetch that
    // fully occupies the remaining page slots).
    const openCodeItems = Array.from({ length: 30 }, (_, i) => item(4998 - i, `o${i}`));

    // Pi: a full page of 30 items, all older than every OpenCode item fetched,
    // so none of them make the top-30 cut either.
    const piItems = Array.from({ length: 30 }, (_, i) => item(500 - i, `p${i}`));

    const result = mergePage(limit, {
        claude: { cursor: null, fetchResult: { failed: false, items: claudeItems } },
        opencode: { cursor: null, fetchResult: { failed: false, items: openCodeItems } },
        "pi-cli": { cursor: null, fetchResult: { failed: false, items: piItems } }
    });

    assert.equal(result.items.length, 30);

    const claudeEmitted = result.items.filter((it) => it.engine === "claude");
    assert.equal(claudeEmitted.length, 2, "n=9, m=2 — only Claude's 2 newest are emitted");
    assert.deepEqual(
        claudeEmitted.map((it) => it.id),
        ["c1", "c2"]
    );

    // The core regression guard: Claude must NOT be marked "done" — its
    // remaining 7 sessions (c3..c9) must stay reachable on the next page.
    assert.notEqual(result.nextCursors.claude, DONE, "Claude's 7 un-emitted sessions would be permanently lost if marked done");
    assert.deepEqual(result.nextCursors.claude, { ts: 4999, id: "c2" });
    assert.equal(result.done, false);

    // OpenCode: n=30 == limit, all 30 emitted -> rule 3 (n < limit fails),
    // watermark is its own last emitted item, not done.
    assert.equal(result.nextCursors.opencode.id, "o27"); // last of the 28 opencode items that fit after claude's 2
    assert.notEqual(result.nextCursors.opencode, DONE);

    // Pi: fetched 30 items, none emitted (m=0) -> rule 4, unchanged (null, first page).
    assert.equal(result.nextCursors["pi-cli"], null);
});

// ----------------------------------------------------------------------------
// Property-style test: no gaps, no duplicates across 3+ simulated pages
// ----------------------------------------------------------------------------

/**
 * Deterministic pseudo-random generator (mulberry32) so this test is
 * reproducible across runs without needing a real RNG dependency.
 */
function mulberry32(seed) {
    let a = seed;
    return function () {
        a |= 0;
        a = (a + 0x6d2b79f5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

function buildSyntheticDataset(rand, engineName, count, tsStart) {
    // Descending-ish timestamps with random gaps; unique ids per engine.
    const items = [];
    let ts = tsStart;
    for (let i = 0; i < count; i++) {
        ts -= 1 + Math.floor(rand() * 5);
        items.push(item(ts, `${engineName}-${String(i).padStart(3, "0")}`));
    }
    // Already in (ts DESC, id DESC) order by construction (ts strictly
    // decreasing as i increases; ids are unique per engine so no tie).
    return items;
}

function fetchFromDataset(dataset, cursor, limit) {
    let startIndex = 0;
    if (cursor && cursor !== DONE) {
        startIndex = dataset.findIndex(
            (it) => it.ts < cursor.ts || (it.ts === cursor.ts && it.id < cursor.id)
        );
        if (startIndex === -1) startIndex = dataset.length;
    }
    return dataset.slice(startIndex, startIndex + limit);
}

test("mergePage property test: no gaps, no duplicates across 3+ simulated pages (all engines healthy)", () => {
    const rand = mulberry32(20260817);
    const limit = 5;

    const datasets = {
        "pi-cli": buildSyntheticDataset(rand, "pi-cli", 25, 100000),
        opencode: buildSyntheticDataset(rand, "opencode", 12, 100000),
        claude: buildSyntheticDataset(rand, "claude", 7, 100000)
    };

    let cursors = { "pi-cli": null, opencode: null, claude: null };
    const emittedAll = [];
    let pageCount = 0;
    const MAX_PAGES = 200; // safety valve against an infinite-loop bug

    while (true) {
        const activeEngines = ENGINES.filter((name) => cursors[name] !== DONE);
        if (activeEngines.length === 0) break;

        pageCount++;
        assert.ok(pageCount <= MAX_PAGES, "pagination did not terminate — possible infinite loop");

        const engineStates = {};
        for (const name of ENGINES) {
            if (cursors[name] === DONE) {
                engineStates[name] = { cursor: DONE };
            } else {
                const items = fetchFromDataset(datasets[name], cursors[name], limit);
                engineStates[name] = { cursor: cursors[name], fetchResult: { failed: false, items } };
            }
        }

        const result = mergePage(limit, engineStates);
        emittedAll.push(...result.items);
        cursors = result.nextCursors;

        if (result.done) break;
    }

    assert.ok(pageCount >= 3, `expected at least 3 pages, got ${pageCount}`);

    const totalExpected = datasets["pi-cli"].length + datasets.opencode.length + datasets.claude.length;
    assert.equal(emittedAll.length, totalExpected, "every item across all engines must be emitted exactly once (no gaps)");

    // No duplicates: every (engine, id) pair appears exactly once.
    const seen = new Set();
    for (const it of emittedAll) {
        const key = `${it.engine}:${it.id}`;
        assert.ok(!seen.has(key), `duplicate emission detected: ${key}`);
        seen.add(key);
    }

    // Per-engine emitted order must exactly match that engine's full dataset,
    // in order (proves no gaps and no reordering within an engine's stream).
    for (const name of ENGINES) {
        const emittedForEngine = emittedAll.filter((it) => it.engine === name);
        assert.deepEqual(
            emittedForEngine.map((it) => ({ ts: it.ts, id: it.id })),
            datasets[name].map((it) => ({ ts: it.ts, id: it.id })),
            `engine ${name} emitted set/order must exactly match its full dataset`
        );
    }

    // Global monotonicity: the merged output overall must be non-increasing
    // by (ts DESC, id DESC) across every page boundary too.
    for (let i = 1; i < emittedAll.length; i++) {
        const prev = emittedAll[i - 1];
        const curr = emittedAll[i];
        const prevRank = [prev.ts, prev.id];
        const currRank = [curr.ts, curr.id];
        assert.ok(
            prevRank[0] > currRank[0] || (prevRank[0] === currRank[0] && prevRank[1] >= currRank[1]),
            `global order violated between index ${i - 1} (${JSON.stringify(prev)}) and ${i} (${JSON.stringify(curr)})`
        );
    }
});

test("mergePage property test: no gaps, no duplicates when one engine intermittently fails then recovers", () => {
    const rand = mulberry32(42);
    const limit = 4;

    const datasets = {
        "pi-cli": buildSyntheticDataset(rand, "pi-cli", 10, 50000),
        opencode: buildSyntheticDataset(rand, "opencode", 10, 50000),
        claude: buildSyntheticDataset(rand, "claude", 6, 50000)
    };

    let cursors = { "pi-cli": null, opencode: null, claude: null };
    const emittedAll = [];
    let pageCount = 0;
    const MAX_PAGES = 200;
    let openCodeFailuresInjected = 0;

    while (true) {
        const activeEngines = ENGINES.filter((name) => cursors[name] !== DONE);
        if (activeEngines.length === 0) break;

        pageCount++;
        assert.ok(pageCount <= MAX_PAGES, "pagination did not terminate — possible infinite loop");

        const engineStates = {};
        for (const name of ENGINES) {
            if (cursors[name] === DONE) {
                engineStates[name] = { cursor: DONE };
                continue;
            }
            // Fail OpenCode on page 2 only, to exercise rule 1 mid-pagination.
            if (name === "opencode" && pageCount === 2) {
                openCodeFailuresInjected++;
                engineStates[name] = { cursor: cursors[name], fetchResult: { failed: true } };
                continue;
            }
            const items = fetchFromDataset(datasets[name], cursors[name], limit);
            engineStates[name] = { cursor: cursors[name], fetchResult: { failed: false, items } };
        }

        const result = mergePage(limit, engineStates);
        emittedAll.push(...result.items);
        cursors = result.nextCursors;

        if (result.done) break;
    }

    assert.equal(openCodeFailuresInjected, 1, "the injected failure must actually occur once");
    assert.ok(pageCount >= 3, `expected at least 3 pages, got ${pageCount}`);

    const totalExpected = datasets["pi-cli"].length + datasets.opencode.length + datasets.claude.length;
    assert.equal(emittedAll.length, totalExpected, "a transient failure must not cause any item to be lost (no gaps) once it recovers");

    const seen = new Set();
    for (const it of emittedAll) {
        const key = `${it.engine}:${it.id}`;
        assert.ok(!seen.has(key), `duplicate emission detected: ${key}`);
        seen.add(key);
    }

    for (const name of ENGINES) {
        const emittedForEngine = emittedAll.filter((it) => it.engine === name);
        assert.deepEqual(
            emittedForEngine.map((it) => ({ ts: it.ts, id: it.id })),
            datasets[name].map((it) => ({ ts: it.ts, id: it.id })),
            `engine ${name} emitted set/order must exactly match its full dataset even with a transient failure`
        );
    }
});

// ----------------------------------------------------------------------------
// PR2: Ordering Key & Content-Free Matching (per engine)
// ----------------------------------------------------------------------------

// All fixture directories below live under a fresh mkdtempSync() temp dir,
// never under the developer's real ~/.pi or ~/.claude — per the PR2 testing
// requirement that the suite not depend on real machine state.
function makeTempDir(prefix) {
    return mkdtempSync(join(tmpdir(), prefix));
}

// ---- task 2.1: orderClaude ----

test("orderClaude returns statSync(file).mtimeMs", () => {
    const dir = makeTempDir("idupi-claude-order-");
    try {
        const filePath = join(dir, "session1.jsonl");
        writeFileSync(filePath, '{"type":"session"}\n');
        const expectedMtimeMs = fs.statSync(filePath).mtimeMs;
        assert.equal(orderClaude(filePath), expectedMtimeMs);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("orderClaude reads ZERO file content — its source never references readFileSync/openSync/readSync", () => {
    // node:test's mock.method cannot redefine a Node core module's ESM
    // named-export bindings (they are non-configurable module-namespace
    // properties, unlike a userland CJS module's live-binding interop —
    // verified: mutating the `fs` default export does not affect a
    // separately-imported `{ readFileSync }` binding elsewhere). Structural
    // source inspection is therefore the deterministic way to prove
    // orderClaude never reads content: its body is a single statSync call.
    const src = orderClaude.toString();
    assert.match(src, /statSync/);
    assert.doesNotMatch(src, /readFileSync/);
    assert.doesNotMatch(src, /openSync/);
    assert.doesNotMatch(src, /readSync/);

    // Value-level corroboration: the function still does its one job.
    const dir = makeTempDir("idupi-claude-order-nocontent-");
    try {
        const filePath = join(dir, "session1.jsonl");
        writeFileSync(filePath, '{"type":"session"}\n');
        assert.equal(typeof orderClaude(filePath), "number");
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

// ---- task 2.2: matchOrderPi and its building blocks ----

test("stripPiDirWrapper strips exactly one leading and one trailing '--'", () => {
    assert.equal(
        stripPiDirWrapper("--C--Users-dev-AndroidStudioProjects-IDUPI--"),
        "C--Users-dev-AndroidStudioProjects-IDUPI"
    );
    // No wrapper present: unchanged.
    assert.equal(stripPiDirWrapper("no-wrapper-here"), "no-wrapper-here");
    // Only a leading wrapper: only that end is stripped.
    assert.equal(stripPiDirWrapper("--only-leading"), "only-leading");
    assert.equal(stripPiDirWrapper("only-trailing--"), "only-trailing");
});

test("piDirNamePreFilter matches a real wrapped Pi directory name against the project's encoded path", () => {
    const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
    const normalized = normalizePathForCompare(projectPath);
    const encoded = encodeProjectDirName(normalized);

    // Verbatim real-world shape: leading and trailing literal "--".
    const realDirName = "--C--Users-dev-AndroidStudioProjects-IDUPI--";
    assert.equal(piDirNamePreFilter(realDirName, encoded), true);

    // A sibling worktree directory (different encoded content) must NOT match.
    const siblingDirName = "--C--Users-dev-AndroidStudioProjects-IDUPI-worktrees-foo--";
    assert.equal(piDirNamePreFilter(siblingDirName, encoded), false);
});

test("piDirNamePreFilter: the pre-fix comparison (no wrapper stripping) would have always failed on real dir names", () => {
    // Regression guard for the dead-bug repair itself: prove the OLD
    // comparison style (direct equality, no stripping) never matches a real
    // wrapped directory name, which is exactly why the pre-filter was dead.
    const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
    const encoded = encodeProjectDirName(normalizePathForCompare(projectPath));
    const realDirName = "--C--Users-dev-AndroidStudioProjects-IDUPI--";
    assert.notEqual(realDirName.toLowerCase(), encoded, "old bug: unwrapped comparison never matches");
    assert.equal(piDirNamePreFilter(realDirName, encoded), true, "fixed: wrapper-stripped comparison matches");
});

test("extractPiSessionMeta parses id/cwd/timestamp from a type:\"session\" line and normalizes ISO timestamp to epoch ms", () => {
    const iso = "2026-07-30T14:17:02.436Z";
    const windowText = JSON.stringify({ type: "session", id: "019fb362-c4e4", cwd: "C:\\proj", timestamp: iso }) + "\n";
    const meta = extractPiSessionMeta(windowText);
    assert.deepEqual(meta, { id: "019fb362-c4e4", cwd: "C:\\proj", timestampMs: Date.parse(iso) });
});

test("extractPiSessionMeta returns timestampMs: null for a NaN/unparseable timestamp (caller falls back to stat.mtime)", () => {
    const windowText = JSON.stringify({ type: "session", id: "abc", cwd: "C:\\proj", timestamp: "not-a-date" }) + "\n";
    const meta = extractPiSessionMeta(windowText);
    assert.equal(meta.timestampMs, null);
});

test("extractPiSessionMeta returns null when no type:\"session\" line is found in the window", () => {
    const windowText = JSON.stringify({ type: "message", role: "user" }) + "\n";
    assert.equal(extractPiSessionMeta(windowText), null);
});

test("extractPiSessionMeta skips malformed JSON lines and a possibly-truncated trailing line at the window boundary", () => {
    const windowText =
        "not json at all\n" +
        JSON.stringify({ type: "session", id: "x1", cwd: "C:\\proj", timestamp: "2026-01-01T00:00:00.000Z" }) +
        "\n" +
        '{"truncated": "mid-obj';
    const meta = extractPiSessionMeta(windowText);
    assert.equal(meta.id, "x1");
});

test("extractPiSessionMeta: cwd/id absent from the session line surface as null (caller decides exclusion/fallback)", () => {
    const windowText = JSON.stringify({ type: "session", timestamp: "2026-01-01T00:00:00.000Z" }) + "\n";
    const meta = extractPiSessionMeta(windowText);
    assert.equal(meta.cwd, null);
    assert.equal(meta.id, null);
});

test("matchOrderPi: matches when cwd equals the project path (normalized) and orders by the ISO timestamp", () => {
    const dir = makeTempDir("idupi-pi-match-");
    try {
        const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
        const filePath = join(dir, "2026-07-30T14-17-02-436Z_019fb362.jsonl");
        const iso = "2026-07-30T14:17:02.436Z";
        writeFileSync(
            filePath,
            JSON.stringify({ type: "session", id: "019fb362-c4e4-7cae-8a08-771a7439834e", cwd: projectPath, timestamp: iso }) + "\n"
        );

        const result = matchOrderPi(filePath, normalizePathForCompare(projectPath));
        assert.equal(result.match, true);
        assert.equal(result.id, "019fb362-c4e4-7cae-8a08-771a7439834e");
        assert.equal(result.timestampMs, Date.parse(iso));
        // filePath must NOT equal the basename-derived id (fact 6: 0/1106 match) —
        // this test's own fixture filename deliberately differs from meta.id,
        // and matchOrderPi must have used meta.id, not the filename.
        assert.notEqual(result.id, "2026-07-30T14-17-02-436Z_019fb362");
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("matchOrderPi: falls back to stat.mtime when the ISO timestamp is missing/unparseable", () => {
    const dir = makeTempDir("idupi-pi-match-fallback-");
    try {
        const projectPath = "C:\\Users\\dev\\proj";
        const filePath = join(dir, "session.jsonl");
        writeFileSync(filePath, JSON.stringify({ type: "session", id: "s1", cwd: projectPath, timestamp: "garbage" }) + "\n");

        const expectedMtimeMs = fs.statSync(filePath).mtimeMs;
        const result = matchOrderPi(filePath, normalizePathForCompare(projectPath));
        assert.equal(result.match, true);
        assert.equal(result.timestampMs, expectedMtimeMs);
        assert.equal(result.mtimeMs, expectedMtimeMs);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("matchOrderPi: cwd mismatch -> no match", () => {
    const dir = makeTempDir("idupi-pi-match-mismatch-");
    try {
        const filePath = join(dir, "session.jsonl");
        writeFileSync(
            filePath,
            JSON.stringify({ type: "session", id: "s1", cwd: "C:\\Users\\dev\\other-project", timestamp: "2026-01-01T00:00:00.000Z" }) + "\n"
        );

        const result = matchOrderPi(filePath, normalizePathForCompare("C:\\Users\\dev\\AndroidStudioProjects\\IDUPI"));
        assert.equal(result.match, false);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("matchOrderPi: session with no usable cwd is excluded, never included via directory-name fallback (spec requirement)", () => {
    const dir = makeTempDir("idupi-pi-match-nocwd-");
    try {
        const filePath = join(dir, "session.jsonl");
        writeFileSync(filePath, JSON.stringify({ type: "session", id: "s1", timestamp: "2026-01-01T00:00:00.000Z" }) + "\n");

        const result = matchOrderPi(filePath, normalizePathForCompare("C:\\Users\\dev\\AndroidStudioProjects\\IDUPI"));
        assert.equal(result.match, false);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("matchOrderPi: 8KB match-key miss excludes the session, never performs a full-file read, emits the diagnostic", () => {
    // Regression test for the ratified bounded-read contract: when the
    // type:"session" line is not found within the bounded 8KB window, the
    // session is excluded and the observable structured diagnostic is emitted.
    // The full-file fallback (readFullFile) MUST NOT be invoked — proving the
    // function can never perform an unbounded read.
    const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";

    let escalationCalls = 0;
    let fullReadCalls = 0;
    let headReadCalls = 0;

    const result = matchOrderPi("fake/path/session.jsonl", normalizePathForCompare(projectPath), {
        readHead: () => {
            headReadCalls++;
            // Bounded 8KB window with NO parseable type:"session" line.
            return "first line is not a session line\nanother non-json line\n";
        },
        // If the bounded contract were violated, this would be invoked and
        // return a matching record that fabricates a match. It MUST stay uncalled.
        readFullFile: (fp) => {
            fullReadCalls++;
            return JSON.stringify({ type: "session", id: "s1", cwd: projectPath, timestamp: "2026-01-01T00:00:00.000Z" }) + "\n";
        },
        statFile: () => ({ mtimeMs: 12345 }),
        onEscalation: (fp) => {
            escalationCalls++;
            assert.equal(fp, "fake/path/session.jsonl");
        }
    });

    assert.equal(headReadCalls, 1, "exactly one bounded head read");
    assert.equal(fullReadCalls, 0, "full-file read MUST NEVER be invoked after an 8KB miss (bounded contract)");
    assert.equal(escalationCalls, 1, "observable structured diagnostic MUST be emitted");
    assert.deepEqual(result, { match: false }, "session excluded, never fabricated");
});

test("matchOrderPi: usable cwd absent within the bounded window (session line present, no cwd) -> excluded + diagnostic, no full read", () => {
    const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";

    let escalationCalls = 0;
    let fullReadCalls = 0;

    const result = matchOrderPi("fake/path/session.jsonl", normalizePathForCompare(projectPath), {
        readHead: () => JSON.stringify({ type: "session", id: "s1", timestamp: "2026-01-01T00:00:00.000Z" }) + "\n",
        readFullFile: (fp) => {
            fullReadCalls++;
            return JSON.stringify({ type: "session", id: "s1", cwd: projectPath, timestamp: "2026-01-01T00:00:00.000Z" }) + "\n";
        },
        statFile: () => ({ mtimeMs: 12345 }),
        onEscalation: (fp) => { escalationCalls++; }
    });

    assert.equal(fullReadCalls, 0, "no full-file read when cwd is absent in the bounded window");
    assert.equal(escalationCalls, 1, "diagnostic emitted when cwd is absent");
    assert.equal(result.match, false);
});

test("matchOrderPi: match key not found within the bounded head window -> excluded (bounded-only, no full-file read)", () => {
    // Regression guard for the corrected contract: a full-file read is never
    // attempted; an unparseable window yields exclusion + a diagnostic, never
    // a fabricated match.
    const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";

    let escalationCalls = 0;
    let fullReadCalls = 0;

    const result = matchOrderPi("fake/path/session.jsonl", normalizePathForCompare(projectPath), {
        readHead: () => "not a session line at all",
        readFullFile: () => {
            fullReadCalls++;
            return "still not a session line";
        },
        statFile: () => ({ mtimeMs: 1 }),
        onEscalation: () => { escalationCalls++; }
    });

    assert.equal(escalationCalls, 1, "diagnostic logged exactly once");
    assert.equal(fullReadCalls, 0, "no full-file read; bounded-only contract");
    assert.equal(result.match, false);
});

// ---- task 2.3: resolveOpenCodeExePath ----

// Verbatim shim content captured in design.md's "OpenCode Invocation —
// Verified Resolution" section, reproduced exactly (do not depend on the
// real file being present on whatever machine runs this suite).
const VERBATIM_OPENCODE_SHIM_CONTENT =
    "@ECHO off\r\n" +
    "GOTO start\r\n" +
    ":find_dp0\r\n" +
    "SET dp0=%~dp0\r\n" +
    "EXIT /b\r\n" +
    ":start\r\n" +
    "SETLOCAL\r\n" +
    "CALL :find_dp0\r\n" +
    '"%dp0%\\node_modules\\opencode-ai\\bin\\opencode.exe"   %*\r\n';

test("resolveOpenCodeExePath parses the verbatim captured shim content and resolves the real exe path", () => {
    _resetOpenCodeExePathCacheForTests();
    const shimPath = "C:\\Users\\fakeuser\\AppData\\Roaming\\npm\\opencode.cmd";
    const expected = join(
        "C:\\Users\\fakeuser\\AppData\\Roaming\\npm",
        "node_modules\\opencode-ai\\bin\\opencode.exe"
    );

    const resolved = resolveOpenCodeExePath({
        shimPath,
        readShim: (p) => {
            assert.equal(p, shimPath);
            return VERBATIM_OPENCODE_SHIM_CONTENT;
        },
        pathExists: () => true
    });

    assert.equal(resolved, expected);
});

test("resolveOpenCodeExePath caches the resolved path — a second call does not re-read the shim", () => {
    _resetOpenCodeExePathCacheForTests();
    const shimPath = "C:\\fake\\opencode.cmd";
    let readCalls = 0;

    const deps = {
        shimPath,
        readShim: () => {
            readCalls++;
            return VERBATIM_OPENCODE_SHIM_CONTENT;
        },
        pathExists: () => true
    };

    const first = resolveOpenCodeExePath(deps);
    const second = resolveOpenCodeExePath(deps);

    assert.equal(first, second);
    assert.equal(readCalls, 1, "the shim must only be read once while the cached path still exists");
});

test("resolveOpenCodeExePath re-resolves lazily when the cached path stops existing", () => {
    _resetOpenCodeExePathCacheForTests();
    const shimPath = "C:\\fake\\opencode.cmd";
    let readCalls = 0;
    let pathExistsCalls = 0;

    const deps = {
        shimPath,
        readShim: () => {
            readCalls++;
            return VERBATIM_OPENCODE_SHIM_CONTENT;
        },
        // Call 1: first invocation's final-resolution validation -> exists.
        // Call 2: second invocation's cache check -> simulate the cached
        //   resolved .exe having disappeared (e.g. an upgrade), forcing a
        //   fresh shim read.
        // Call 3: second invocation's fresh final-resolution validation,
        //   after re-reading the shim -> exists again.
        pathExists: () => {
            pathExistsCalls++;
            return pathExistsCalls !== 2;
        }
    };

    const first = resolveOpenCodeExePath(deps);
    const second = resolveOpenCodeExePath(deps);

    assert.equal(first, second);
    assert.equal(readCalls, 2, "must re-read the shim once the cached resolved path no longer exists");
});

test("resolveOpenCodeExePath throws a clear error when the shim cannot be parsed (format drift), never crashes the process", () => {
    _resetOpenCodeExePathCacheForTests();
    assert.throws(
        () =>
            resolveOpenCodeExePath({
                shimPath: "C:\\fake\\opencode.cmd",
                readShim: () => "@ECHO off\r\nsome unrecognized future shim format\r\n",
                pathExists: () => true
            }),
        /Cannot parse OpenCode shim/
    );
});

test("resolveOpenCodeExePath throws a clear error when the shim itself cannot be read", () => {
    _resetOpenCodeExePathCacheForTests();
    assert.throws(
        () =>
            resolveOpenCodeExePath({
                shimPath: "C:\\fake\\opencode.cmd",
                readShim: () => {
                    throw new Error("ENOENT: no such file or directory");
                },
                pathExists: () => true
            }),
        /Cannot read OpenCode shim/
    );
});

test("resolveOpenCodeExePath throws a clear error when the resolved target path does not exist on disk", () => {
    _resetOpenCodeExePathCacheForTests();
    assert.throws(
        () =>
            resolveOpenCodeExePath({
                shimPath: "C:\\fake\\opencode.cmd",
                readShim: () => VERBATIM_OPENCODE_SHIM_CONTENT,
                pathExists: () => false
            }),
        /Resolved OpenCode path missing/
    );
});

// ---- task 2.4: buildClaudeIndex / buildPiIndex / buildIndex ----

test("buildClaudeIndex returns {id, timestamp, filePath} records sorted (timestamp DESC, id DESC) plus max(mtimeMs) freshness token", () => {
    const dir = makeTempDir("idupi-claude-index-");
    try {
        const fileA = join(dir, "session-aaa.jsonl");
        const fileB = join(dir, "session-bbb.jsonl");
        writeFileSync(fileA, '{"type":"session"}\n');
        writeFileSync(fileB, '{"type":"session"}\n');

        // Force a deterministic mtime ordering: B newer than A.
        const now = Date.now() / 1000;
        utimesSync(fileA, now - 100, now - 100);
        utimesSync(fileB, now, now);

        const { records, freshnessToken } = buildClaudeIndex(dir);

        assert.equal(records.length, 2);
        assert.deepEqual(
            records.map((r) => r.id),
            ["session-bbb", "session-aaa"]
        );
        assert.equal(records[0].filePath, fileB);
        assert.equal(freshnessToken, Math.max(records[0].timestamp, records[1].timestamp));
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("buildClaudeIndex returns an empty index (freshnessToken 0) for a non-existent directory", () => {
    const { records, freshnessToken } = buildClaudeIndex(join(tmpdir(), "idupi-claude-index-does-not-exist"));
    assert.deepEqual(records, []);
    assert.equal(freshnessToken, 0);
});

test("buildPiIndex prunes non-matching directories via the pre-filter (never opens their files) and indexes only cwd-matched files", () => {
    const baseDir = makeTempDir("idupi-pi-index-");
    try {
        const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
        const normalizedProjectPath = normalizePathForCompare(projectPath);

        const matchedDir = join(baseDir, "--C--Users-dev-AndroidStudioProjects-IDUPI--");
        const unmatchedDir = join(baseDir, "--C--Users-dev-SomeOtherProject--");
        mkdirSync(matchedDir);
        mkdirSync(unmatchedDir);

        const fileInMatched = join(matchedDir, "s1.jsonl");
        writeFileSync(
            fileInMatched,
            JSON.stringify({ type: "session", id: "s1", cwd: projectPath, timestamp: "2026-01-01T00:00:00.000Z" }) + "\n"
        );

        // A file in the unmatched directory that WOULD match by cwd if ever
        // opened — proving the pre-filter, not luck, is what prunes it.
        const fileInUnmatched = join(unmatchedDir, "s2.jsonl");
        writeFileSync(
            fileInUnmatched,
            JSON.stringify({ type: "session", id: "s2", cwd: projectPath, timestamp: "2026-01-02T00:00:00.000Z" }) + "\n"
        );

        const { records, freshnessToken } = buildPiIndex(baseDir, normalizedProjectPath);

        assert.equal(records.length, 1, "only the pre-filter-matched directory's file must be indexed");
        assert.equal(records[0].id, "s1");
        assert.equal(records[0].filePath, fileInMatched);
        // freshnessToken is max(mtimeMs) -- real filesystem metadata -- not
        // the semantic `timestamp` (meta.timestamp/ISO), which can legitimately
        // differ from the file's actual on-disk mtime in this fixture.
        assert.equal(freshnessToken, fs.statSync(fileInMatched).mtimeMs);
    } finally {
        rmSync(baseDir, { recursive: true, force: true });
    }
});

test("buildPiIndex: matched directory but per-file cwd mismatch excludes that file (dir pre-filter is necessary, not sufficient)", () => {
    const baseDir = makeTempDir("idupi-pi-index-necessary-not-sufficient-");
    try {
        const projectPath = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
        const normalizedProjectPath = normalizePathForCompare(projectPath);

        const matchedDir = join(baseDir, "--C--Users-dev-AndroidStudioProjects-IDUPI--");
        mkdirSync(matchedDir);

        // Same directory, but this file's own cwd points elsewhere (e.g. a
        // worktree session that happened to land in a shared dir bucket).
        const fileWrongCwd = join(matchedDir, "s1.jsonl");
        writeFileSync(
            fileWrongCwd,
            JSON.stringify({ type: "session", id: "s1", cwd: "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI-worktrees\\foo", timestamp: "2026-01-01T00:00:00.000Z" }) + "\n"
        );

        const { records } = buildPiIndex(baseDir, normalizedProjectPath);
        assert.equal(records.length, 0);
    } finally {
        rmSync(baseDir, { recursive: true, force: true });
    }
});

test("buildPiIndex regression guard: project path with a space and an underscore matches its real-world Pi directory encoding", () => {
    // Regression test for a bug found during PR3 live verification against
    // real data: piDirNamePreFilter was comparing against
    // encodeProjectDirName's output (Claude's convention -- ALL non-alnum,
    // including space and underscore, collapse to "-"), but Pi's real
    // directory-naming convention only replaces path separators and the
    // drive colon; it leaves spaces and underscores untouched. Every
    // existing fixture above used "IDUPI" (no space, no underscore), so
    // none of them exercised the character classes that broke this. Real
    // directory observed on disk:
    // "--C--Users-dev-OneDrive-Escritorio-Mis proyectos-Sistema_de_mantencion--"
    // With the wrong encoder this filter always returned false for this
    // project, silently zeroing its indexed Pi session count from 75 to 0.
    const baseDir = makeTempDir("idupi-pi-index-space-underscore-");
    try {
        const projectPath = "C:\\Users\\dev\\OneDrive\\Escritorio\\Mis proyectos\\Sistema_de_mantencion";
        const normalizedProjectPath = normalizePathForCompare(projectPath);

        const matchedDir = join(baseDir, "--C--Users-dev-OneDrive-Escritorio-Mis proyectos-Sistema_de_mantencion--");
        mkdirSync(matchedDir);

        const filePath = join(matchedDir, "s1.jsonl");
        writeFileSync(
            filePath,
            JSON.stringify({ type: "session", id: "s1", cwd: projectPath, timestamp: "2026-01-01T00:00:00.000Z" }) + "\n"
        );

        const { records } = buildPiIndex(baseDir, normalizedProjectPath);
        assert.equal(records.length, 1, "a real-shaped Pi directory name with a space and an underscore must still match");
        assert.equal(records[0].id, "s1");
    } finally {
        rmSync(baseDir, { recursive: true, force: true });
    }
});

test("buildPiIndex returns an empty index for a non-existent base directory", () => {
    const { records, freshnessToken } = buildPiIndex(join(tmpdir(), "idupi-pi-index-does-not-exist"), normalizePathForCompare("C:\\proj"));
    assert.deepEqual(records, []);
    assert.equal(freshnessToken, 0);
});

test("buildIndex dispatches 'claude' to buildClaudeIndex and 'pi-cli' to buildPiIndex", () => {
    const claudeDir = makeTempDir("idupi-buildindex-claude-");
    const piBaseDir = makeTempDir("idupi-buildindex-pi-");
    try {
        writeFileSync(join(claudeDir, "s1.jsonl"), '{"type":"session"}\n');
        const claudeResult = buildIndex("claude", { claudeProjectDir: claudeDir });
        assert.equal(claudeResult.records.length, 1);

        const piResult = buildIndex("pi-cli", {
            piSessionsBaseDir: piBaseDir,
            normalizedProjectPath: normalizePathForCompare("C:\\proj")
        });
        assert.deepEqual(piResult.records, []);
    } finally {
        rmSync(claudeDir, { recursive: true, force: true });
        rmSync(piBaseDir, { recursive: true, force: true });
    }
});

test("buildIndex throws a clear error for the out-of-scope 'opencode' engine (SQL-side, wired in PR3)", () => {
    assert.throws(() => buildIndex("opencode", {}), /unsupported engine 'opencode'/);
});
