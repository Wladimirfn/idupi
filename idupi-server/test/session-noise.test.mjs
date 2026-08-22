// ============================================================================
// idupi-server/test/session-noise.test.mjs
//
// The session list was unusable on OpenCode. Measured against the real store
// for this project: 9 main sessions and 99 subagent ones -- 92% of the list was
// work the user never started by hand.
//
// OpenCode marks it explicitly: `session.parent_id` is NULL for a session the
// user opened and set to the parent for one a subagent ran under. The listing
// query selected every row in the directory, parent or child alike.
//
// The other kind of noise is a session with a single exchange: a one-shot run,
// a test, an abandoned start. For OpenCode the message count is already in the
// query, so both filters are free and, being in SQL, keep pagination exact --
// filtering a page after fetching it would return short pages instead.
//
// Deliberately NOT applied to Pi and Claude: their index reads a bounded 8 KiB
// head window and never the whole file (lib/sessions.mjs matchOrderPi), so
// counting turns there would break a ratified constraint, not just cost I/O.
// Claude has no subagent sessions at all -- every session in the store reports
// isSidechain false, because Claude keeps delegated work inside the parent
// session. Pi's subagent sessions live in nested directories the index already
// skips (it only reads *.jsonl at depth 1).
//
// Run (from repo root):
//   node --test idupi-server/test/session-noise.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

function fnBody(name) {
    const at = source.indexOf(`function ${name}(`);
    assert.notEqual(at, -1, `no se encontró ${name}`);
    return source.slice(at, source.indexOf("\n}", at));
}

test("a subagent session is excluded by default", () => {
    const clause = fnBody("buildOpenCodeNoiseClause");
    assert.match(
        clause,
        /parent_id IS NULL/,
        "sin este filtro, 99 de 108 sesiones de la lista son de subagentes",
    );
    assert.match(fnBody("fetchOpenCodePage"), /buildOpenCodeNoiseClause\(includeAll\)/);
    assert.match(fnBody("fetchOpenCodePage"), /\$\{noiseClause\}/, "y la consulta debe aplicarla");
});

test("a one-exchange session is excluded by default", () => {
    const clause = fnBody("buildOpenCodeNoiseClause");
    assert.match(clause, /COUNT\(\*\) FROM message[\s\S]*?> 2/, "una sola ida y vuelta no es una sesión que abrir");
});

test("the switch brings both kinds back", () => {
    // With includeAll the clause has to DISAPPEAR, not be negated into "only
    // noise": the switch shows everything, it does not invert the list.
    const clause = fnBody("buildOpenCodeNoiseClause");
    assert.match(clause, /includeAll[\s\S]*?\?[\s\S]*?""/, "activado, no debe agregar ninguna condición");
});

test("the count matches what the list shows", () => {
    // A count that ignores the filter reports 108 next to a list of 9.
    const body = fnBody("countOpenCodeSessions");
    assert.match(body, /buildOpenCodeNoiseClause\(includeAll\)/, "el conteo debe usar la MISMA cláusula que la lista");
    assert.match(body, /\$\{noiseClause\}/, "y aplicarla en su consulta");
});

test("the filter is built by one function, not duplicated per query", () => {
    // The listing and the count have to agree; two hand-written copies of the
    // same clause are two chances for them to drift apart.
    assert.match(source, /function buildOpenCodeNoiseClause\(/);
    const uses = source.match(/buildOpenCodeNoiseClause\(/g) || [];
    assert.ok(uses.length >= 3, `se esperaba definición + dos usos, hay ${uses.length}`);
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
    const body = fnBody("fetchEnginePageResult");
    assert.ok(
        !/parent_id|msg_count/.test(body),
        "no se puede filtrar por turnos lo que nunca se leyó",
    );
});
