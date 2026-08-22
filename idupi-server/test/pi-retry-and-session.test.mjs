// ============================================================================
// idupi-server/test/pi-retry-and-session.test.mjs
//
// Two defects seen together in one live Pi run.
//
// 1. Pi emits `agent_end` before an automatic retry too, carrying
//    `willRetry: true` (dist/core/agent-session.d.ts). The handler resolved on
//    any agent_end, so a failed first attempt -- which had produced no text --
//    closed the turn with the "Respuesta procesada correctamente por Pi CLI."
//    filler. The real answer streamed a moment later into a turn nobody was
//    waiting on. The server log showed the model working; the app showed the
//    filler.
//
// 2. `currentSessionPath` is only ever assigned by resumeSession, so a chat
//    that started its own Pi session leaves it null. Opening that same session
//    in the list resolved a real path, compared unequal to null, and SIGTERMed
//    a child that was mid-answer -- `Pi RPC se cerró con código null`, a 500,
//    and the work gone, for the sole act of opening a session.
//
// The manager is not exported, so these drive the same lexical invariants the
// live run violated, the way module-scope.test.mjs does.
//
// Run (from repo root):
//   node --test idupi-server/test/pi-retry-and-session.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

test("a turn is never closed on the agent_end that precedes a retry", () => {
    const closing = source.match(/if \(event\.type === "agent_end".*?&& this\.pendingResolve\) \{/);
    assert.ok(closing, "no se encontró el bloque que cierra el turno en agent_end");
    assert.match(
        closing[0],
        /event\.willRetry !== true/,
        "el cierre del turno no distingue el agent_end de un reintento",
    );
});

test("the filler answer is only reachable once Pi has stopped retrying", () => {
    // The filler itself is fine as a last resort; what made it visible was
    // reaching it on an attempt Pi was about to redo.
    const fillerAt = source.indexOf("Respuesta procesada correctamente por Pi CLI.");
    assert.notEqual(fillerAt, -1, "el texto de respaldo debería seguir existiendo");
    const guardAt = source.lastIndexOf('event.willRetry !== true', fillerAt);
    assert.notEqual(guardAt, -1, "el respaldo no está detrás del guard de willRetry");
});

test("a retry clears the text of the attempt it replaces", () => {
    const block = source.match(/if \(event\.type === "auto_retry_start"\) \{[\s\S]*?\n {12}\}/);
    assert.ok(block, "no hay manejo de auto_retry_start");
    assert.match(block[0], /this\.currentOutput = ""/,
        "el reintento debe descartar el texto del intento fallido, no concatenarlo");
});

test("exhausted retries fail the request instead of hanging until the backstop", () => {
    assert.match(
        source,
        /event\.type === "auto_retry_end" && event\.success === false && this\.pendingReject/,
        "un reintento agotado debe cerrar la request, no dejarla esperando el timeout",
    );
});

test("resumeSession refuses instead of killing a child that is answering", () => {
    const fn = source.match(/resumeSession\(sessionPath\) \{[\s\S]*?\n {4}\}/);
    assert.ok(fn, "no se encontró resumeSession");

    const busyAt = fn[0].indexOf("this.isBusy");
    const killAt = fn[0].indexOf('kill("SIGTERM")');
    assert.notEqual(busyAt, -1, "resumeSession no consulta si Pi está ocupado");
    assert.notEqual(killAt, -1, "resumeSession debería seguir reiniciando el hijo");
    assert.ok(busyAt < killAt, "la comprobación de ocupado debe ir ANTES de matar el proceso");
});

test("isBusy reports a message still waiting for its answer", () => {
    assert.match(
        source,
        /get isBusy\(\) \{\s*return this\.pendingResolve !== null;\s*\}/,
        "isBusy debe derivarse de la request pendiente, no de un flag aparte",
    );
});

test("the route answers a refused session change instead of reporting success", () => {
    const route = source.match(/if \(!piRpc\.resumeSession\(sessionPath\)\) \{[\s\S]*?return;\s*\}/);
    assert.ok(route, "la ruta de resume no contempla el rechazo");
    assert.match(route[0], /writeHead\(409/, "un cambio rechazado no puede responder 200");
});
