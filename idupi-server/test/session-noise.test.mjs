// ============================================================================
// idupi-server/test/session-noise.test.mjs
//
// The session list was unusable on OpenCode. Measured against the real store
// for this project: 9 main sessions and 99 subagent ones -- 92% of the list was
// work the user never started by hand.
//
// OpenCode v2 splits the two kinds of noise:
//   - Subagent sessions are excluded BY CONSTRUCTION: `session list` only
//     returns top-level sessions of the current project (its own help states
//     it), so the listing no longer needs a parent_id predicate.
//   - A one-exchange session (a one-shot run, a test, an abandoned start) is
//     still filtered here: the adapter hides sessions with <= 2 messages from
//     the v2 `session_message` table. The filter runs BEFORE slicing, so pages
//     are not shortened after the fact, and the count uses the same predicate.
//     When the store cannot be read, the counts are unknown: nothing is
//     filtered and no count is invented.
//
// Deliberately NOT applied to Pi and Claude: their index reads a bounded 8 KiB
// head window and never the whole file (lib/sessions.mjs matchOrderPi), so
// counting turns there would break a ratified constraint, not just cost I/O.
//
// Run (from repo root):
//   node --test idupi-server/test/session-noise.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { createOpenCodeCli } from "../lib/opencode-cli.mjs";

const TEST_DIR = dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = join(TEST_DIR, "..", "index.mjs");
const ADAPTER_FILE = join(TEST_DIR, "..", "lib", "opencode-cli.mjs");
const source = readFileSync(SERVER_FILE, "utf8");
const adapterSource = readFileSync(ADAPTER_FILE, "utf8");

const PROJ = "C:\\Users\\elmas\\AndroidStudioProjects\\IDUPI";

function fnBody(text, name) {
    const at = text.indexOf(`function ${name}(`);
    assert.notEqual(at, -1, `no se encontró ${name}`);
    return text.slice(at, text.indexOf("\n}", at));
}

function sessionRow(id, updated) {
    return { id, title: id, updated, created: updated - 1, projectId: "p1", directory: PROJ };
}

function makeCli(countsById, { openError } = {}) {
    const warnings = [];
    const cli = createOpenCodeCli({
        execFile: (file, args, opts, cb) => {
            queueMicrotask(() => cb(null, JSON.stringify([
                sessionRow("ses_real", 30),
                sessionRow("ses_one_shot", 20),
                sessionRow("ses_abandoned", 10)
            ]), ""));
        },
        execFileSync: () => "",
        resolveExe: () => "C:\\fake\\opencode.exe",
        statDirectory: () => true,
        openCountsDb: () => {
            if (openError) throw new Error(openError);
            return {
                prepare: () => ({
                    all: (...ids) => ids
                        .filter((id) => countsById[id] !== undefined)
                        .map((id) => ({ session_id: id, n: countsById[id] }))
                }),
                close() {}
            };
        },
        countsDbPath: () => "C:\\fake\\opencode.db",
        now: () => 1000,
        warn: (m) => warnings.push(m)
    });
    return { cli, warnings };
}

test("subagent noise is excluded by construction: v2 `session list` is top-level only", () => {
    // The CLI contract replaces the old `parent_id IS NULL` SQL predicate.
    // Pinning the invocation keeps the guarantee visible at the seam we own.
    assert.match(adapterSource, /\["session", "list", "--format", "json", "--max-count"/,
        "el listado tiene que salir de `session list` (top-level por contrato de v2)");
    assert.ok(!/parent_id/.test(source),
        "el index ya no arma SQL: no debe quedar el predicado de v1");
});

test("a one-exchange session is excluded by default", async () => {
    const { cli } = makeCli({ ses_real: 4, ses_one_shot: 2, ses_abandoned: 1 });
    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10
    });
    assert.deepStrictEqual(page.map((s) => s.id), ["ses_real"],
        "una sola ida y vuelta (<= 2 mensajes) no es una sesión que abrir");
    assert.deepStrictEqual(page.map((s) => s.msgCount), [4], "el conteo mostrado es el exacto");
});

test("the switch brings the one-exchange sessions back", async () => {
    const { cli } = makeCli({ ses_real: 4, ses_one_shot: 2, ses_abandoned: 1 });
    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10, includeAll: true
    });
    assert.deepStrictEqual(page.map((s) => s.id), ["ses_real", "ses_one_shot", "ses_abandoned"],
        "el switch muestra todo; no invierte la lista");
    assert.deepStrictEqual(page.map((s) => s.msgCount), [4, 2, 1]);
});

test("the count matches what the list shows", async () => {
    const counts = { ses_real: 4, ses_one_shot: 2, ses_abandoned: 1 };
    const { cli } = makeCli(counts);
    const filtered = await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ });
    const all = await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ, includeAll: true });
    assert.strictEqual(filtered, 1, "un conteo que ignorara el filtro reportaría 3 junto a una lista de 1");
    assert.strictEqual(all, 3);
});

test("unknown message counts skip the filter instead of fabricating one", async () => {
    const { cli, warnings } = makeCli({}, { openError: "unable to open database file" });
    const page = await cli.fetchPage({
        projectDir: PROJ, normProjectPath: PROJ, subCursor: null, limit: 10
    });
    assert.deepStrictEqual(page.map((s) => s.id), ["ses_real", "ses_one_shot", "ses_abandoned"],
        "sin conteos confiables no se esconde nada");
    assert.deepStrictEqual(page.map((s) => s.msgCount), [null, null, null], "nunca se inventa un número");
    assert.strictEqual(await cli.countSessions({ projectDir: PROJ, normProjectPath: PROJ }), 3,
        "el conteo sin filtro es el verdadero, no un cero");
    assert.strictEqual(warnings.length, 1, "un solo aviso: el store está ilegible");
});

test("the route reads the switch from the request", () => {
    const at = source.indexOf('pathname === "/api/v1/sessions" && req.method === "GET"');
    assert.notEqual(at, -1);
    const route = source.slice(at, at + 1600);
    assert.match(route, /includeAll/, "la app tiene que poder pedir la lista completa");
});

test("Pi and Claude are left alone", () => {
    // Their index is bounded to an 8 KiB head window by design; a turn count
    // would need the whole file.
    const body = fnBody(source, "fetchEnginePageResult");
    assert.ok(
        !/parent_id|msg_count/.test(body),
        "no se puede filtrar por turnos lo que nunca se leyó",
    );
});
