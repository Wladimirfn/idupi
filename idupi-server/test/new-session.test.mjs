// ============================================================================
// idupi-server/test/new-session.test.mjs
//
// "poder crear una sesión nueva en el cli que deje configurado".
//
// /api/v1/sessions/new existed, but it only cleared the Claude and OpenCode
// session ids. Pi keeps its session as a live RPC child started with
// `--session <path>`, and that path was never cleared -- so asking for a new
// session on Pi silently kept the old one. Nothing in the app called the
// endpoint either.
//
// It also has to refuse while a turn is running, for the reason resumeSession
// already refuses: killing a working Pi child ends the turn with
// "Pi RPC se cerró con código null" and loses the answer in flight.
//
// Run (from repo root):
//   node --test idupi-server/test/new-session.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

function routeBody() {
    const at = source.indexOf('pathname === "/api/v1/sessions/new"');
    assert.notEqual(at, -1, "no se encontró la ruta de sesión nueva");
    return source.slice(at, at + 2200);
}

test("a new session clears Pi's session too, not only Claude and OpenCode", () => {
    const body = routeBody();
    assert.match(body, /activeClaudeSessionId = null/);
    assert.match(body, /activeOpenCodeSessionId = null/);
    assert.ok(
        /piRpc\.startNewSession\(/.test(body),
        "sin limpiar currentSessionPath, Pi sigue reanudando la sesión anterior con --session",
    );
});

test("Pi's new session refuses while a turn is running", () => {
    const at = source.indexOf("startNewSession(");
    assert.notEqual(at, -1, "no existe startNewSession en PiRpcManager");
    const fn = source.slice(at, at + 700);
    assert.match(fn, /this\.isBusy/, "matar un hijo que está trabajando pierde la respuesta en curso");
    assert.match(fn, /return false/, "debe poder rechazar, no solo avisar");
});

test("the route reports the refusal instead of claiming success", () => {
    const body = routeBody();
    assert.match(body, /409/, "una sesión que no se creó no puede responder 200");
});

test("a fresh Pi session keeps the configured model", () => {
    // ensureStarted() rebuilds the child from currentProvider/currentModelId, so
    // clearing only the session path is what leaves it configured. Clearing the
    // model here would hand the user a session on Pi's default model instead of
    // the one they picked.
    const at = source.indexOf("startNewSession(");
    const fn = source.slice(at, at + 700);
    assert.ok(
        !/currentModelId\s*=/.test(fn) && !/currentProvider\s*=/.test(fn),
        "la sesión nueva no debe descartar el modelo elegido",
    );
    assert.match(fn, /this\.currentSessionPath = null/);
});

test("the answer says what was configured, so the app can show it", () => {
    const body = routeBody();
    assert.match(body, /activeEngine/, "debe informar el motor de la sesión nueva");
    assert.match(body, /operatingAi/, "y el modelo con el que queda configurada");
});
