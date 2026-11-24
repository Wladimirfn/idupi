// ============================================================================
// idupi-server/test/opencode-model-passthrough.test.mjs
//
// OpenCode model passthrough + resume/history engine guards.
//
// The audit found three cross-engine contaminations:
//  1. /api/v1/model/switch fell into the Pi branch (piRpc.setModel) when the
//     active engine was opencode, so the UI showed a selected OpenCode model
//     but `opencode run` never received it (openCodeArgs built no -m flag).
//  2. Resume classification `sessionId.startsWith("ses_") || activeEngine ===
//     "opencode"` treated a Pi/Claude UUID id as OpenCode whenever the engine
//     was opencode.
//  3. History parsing called piRpc.setModel regardless of the session's engine.
//
// These tests pin the route bodies via source assertions (the same pattern as
// new-session.test.mjs), because the handlers live inside the server's HTTP
// routing and are not importable units.
//
// Run (from repo root):
//   node --test idupi-server/test/opencode-model-passthrough.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

// --- 1. /api/v1/model/switch ------------------------------------------------

function modelSwitchRoute() {
    const at = source.indexOf('pathname === "/api/v1/model/switch"');
    assert.notEqual(at, -1, "no se encontró la ruta de cambio de modelo");
    return source.slice(at, at + 4400);
}

test("model/switch stores the selection for OpenCode instead of calling piRpc.setModel", () => {
    const body = modelSwitchRoute();
    const ocAt = body.indexOf('currentStatus.activeEngine === "opencode"');
    assert.notEqual(ocAt, -1, "la ruta debe tener una rama explícita para OpenCode");
    const branchEnd = body.indexOf("} else {", ocAt);
    assert.notEqual(branchEnd, -1);
    const branch = body.slice(ocAt, branchEnd);
    assert.match(branch, /activeOpenCodeModel\s*=\s*\{\s*model:\s*normalizedOpenCodeModel/,
        "la selección debe recordarse en activeOpenCodeModel con el id normalizado");
    assert.ok(!/piRpc\.setModel/.test(branch),
        "OpenCode no puede caer en la rama Pi: la UI mostraba el modelo pero el CLI nunca lo recibía");
});

test("model/switch keeps the Pi branch for Pi", () => {
    const body = modelSwitchRoute();
    assert.match(body, /piRpc\.setModel\(modelName, providerName\)/,
        "Pi sigue aplicando el modelo vía setModel");
});

test("model/switch normalizes and validates the OpenCode model against the catalog before storing", () => {
    const body = modelSwitchRoute();
    const ocAt = body.indexOf('currentStatus.activeEngine === "opencode"');
    const branchEnd = body.indexOf("} else {", ocAt);
    const branch = body.slice(ocAt, branchEnd);
    assert.match(branch, /normalizeOpenCodeModel\(modelName, providerName\)/,
        "el id se normaliza antes de guardarse: un id que ya lleva el proveedor NUNCA se vuelve a prefijar");
    assert.match(branch, /getAvailableModels\(\)/,
        "la selección se valida contra el catálogo real de opencode antes de recordarse");
    assert.match(branch, /no existe en opencode models/,
        "un modelo inexistente falla rápido con mensaje claro, sin llegar a spawnear un hang");
});

test("model/switch no longer logs a doubled provider prefix", () => {
    const body = modelSwitchRoute();
    assert.ok(!/Aplicando modelo único: \$\{providerName \|\| 'auto'\}\/\$\{modelName\}/.test(body),
        "el log no puede imprimir provider + modelo-que-ya-lleva-provider (opencode/opencode/x)");
    assert.match(body, /displayModelId/, "el log imprime el id normalizado (sin prefijo duplicado)");
});

test("provider models use the v2 catalog prefix, never a provider positional argument", () => {
    const at = source.indexOf("function getModelsForProvider");
    assert.notEqual(at, -1, "no se encontró getModelsForProvider");
    const fn = source.slice(at, at + 1600);
    assert.match(fn, /openCodeCli\.modelsForProvider\(providerId\)/,
        "v2 `opencode models` no acepta proveedor posicional: el filtro es por prefijo en el adaptador");
    assert.ok(!/\["models",\s*providerId\]/.test(source),
        "`opencode models <provider>` falla con 'Unexpected positional argument' en v2");
    assert.match(fn, /fullId: line/,
        "el contrato {id, fullId, name, provider} que consume la app se mantiene");
    assert.match(fn, /assertSafeCliId\(providerId, "providerId"\)/,
        "un providerId inválido sigue fallando rápido (400), no como CLI enfermo");
});

test("the engine-wide catalog and the provider lists share one cached `models` call", () => {
    const engineAt = source.indexOf("function getAvailableModels");
    assert.notEqual(engineAt, -1);
    const engineFn = source.slice(engineAt, engineAt + 1800);
    assert.match(engineFn, /openCodeCli\.listAllModels\(\)/,
        "el catálogo del motor sale del adaptador cacheado");
    assert.ok(!/\["models"\]/.test(engineFn),
        "no debe quedar una invocación directa sin caché");
});

test("the removed OpenCode v2 `db` subcommand is no longer referenced", () => {
    assert.ok(!/execOpenCodeDb/.test(source), "el helper SQL de v1 tiene que estar eliminado");
    assert.ok(!/\["db",\s*sql/.test(source), "`opencode db <sql>` ya no existe en v2");
    assert.ok(!/opencode db "SELECT/.test(source), "la búsqueda de última sesión no puede usar `db`");
});

// --- 2. Resume classification ----------------------------------------------

function resumeRoute() {
    const at = source.indexOf('pathname === "/api/v1/sessions/resume"');
    assert.notEqual(at, -1, "no se encontró la ruta de reanudar sesión");
    return source.slice(at, at + 2600);
}

test("resume never classifies by activeEngine alone", () => {
    const body = resumeRoute();
    assert.ok(!/sessionId\.startsWith\("ses_"\) \|\| currentStatus\.activeEngine === "opencode"/.test(body),
        "un id de Pi/Claude (UUID) reanudado con el motor OpenCode activo caía en la rama OpenCode");
});

test("resume classifies OpenCode by id shape and guards the engine fallback with the store UUID shape", () => {
    const body = resumeRoute();
    assert.match(body, /sessionId\.startsWith\("ses_"\)/, "los ids de OpenCode son siempre ses_ (verificado con `session list`)");
    assert.match(body, /looksLikeStoreUuid/, "el fallback al motor activo debe excluir ids con forma de id de tienda (UUID)");
    assert.match(body, /!sessionPath/, "el id debe buscarse primero en la tienda de sesiones de Pi");
});

test("resume still routes Claude and Pi sessions through the session store", () => {
    const body = resumeRoute();
    assert.match(body, /sessionPath\.includes\("\.claude"\)/, "Claude se detecta por la ruta en la tienda");
    assert.match(body, /piRpc\.resumeSession\(sessionPath\)/, "Pi se reanuda por su ruta de sesión");
});

// --- 3. History parsing -----------------------------------------------------

test("history parsing only feeds piRpc.setModel for Pi sessions", () => {
    const at = source.indexOf("piRpc.setModel(detectedModel, detectedProvider)");
    assert.notEqual(at, -1, "el historial debe seguir detectando el modelo de sesiones Pi");
    const ctx = source.slice(at - 300, at + 60);
    assert.match(ctx, /\.claude/,
        "el setModel del historial debe estar guardado: una sesión Claude nunca reescribe el modelo del motor Pi");
});