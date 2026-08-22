// ============================================================================
// idupi-server/test/claude-parallel-subagents.test.mjs
//
// "el orquestador dice que terminaron los sub agentes pero un sub agente quedó
// cargando" -- on Claude this time, after the same symptom was fixed on Pi.
//
// Different engine, same class of defect: the Claude stream handler held the
// open delegation in ONE pair of scalars (activeSubagentId/activeSubagentName).
// A parallel launch emits two `tool_use` blocks before either result comes
// back, so the second overwrote the first. When the first subagent's
// `tool_result` arrived its id matched nothing, it was routed to the ordinary
// tool branch, and its card never closed -- always the first card, which is
// exactly what was reported.
//
// Run (from repo root):
//   node --test idupi-server/test/claude-parallel-subagents.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { SubagentCards } from "../lib/subagent-cards.mjs";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

// --- The tracker itself ----------------------------------------------------

test("two delegations launched in parallel both stay open", () => {
    const cards = new SubagentCards();
    cards.open("toolu_A", "scout");
    cards.open("toolu_B", "researcher");
    assert.equal(cards.size, 2, "el segundo lanzamiento no puede borrar al primero");
});

test("each card closes on its own id, in any order", () => {
    const cards = new SubagentCards();
    cards.open("toolu_A", "scout");
    cards.open("toolu_B", "researcher");

    // The first result arriving last is the ordinary case and the one that broke.
    assert.deepEqual(cards.close("toolu_B"), { id: "toolu_B", name: "researcher" });
    assert.deepEqual(cards.close("toolu_A"), { id: "toolu_A", name: "scout" });
    assert.equal(cards.size, 0);
});

test("an ordinary tool's result is not mistaken for a delegation", () => {
    const cards = new SubagentCards();
    cards.open("toolu_A", "scout");
    assert.equal(cards.close("toolu_Read"), null, "sin tarjeta abierta no hay cierre de subagente");
    assert.equal(cards.size, 1, "y no debe tocar las tarjetas que sí están abiertas");
});

test("closing the same card twice does not reopen or duplicate it", () => {
    const cards = new SubagentCards();
    cards.open("toolu_A", "scout");
    assert.ok(cards.close("toolu_A"));
    assert.equal(cards.close("toolu_A"), null);
});

test("a run that ends early drains every card, not just one", () => {
    const cards = new SubagentCards();
    cards.open("toolu_A", "scout");
    cards.open("toolu_B", "researcher");
    cards.open("toolu_C", "delegate");
    const left = cards.drain();
    assert.equal(left.length, 3, "cerrar una sola al terminar es el mismo error de un solo hueco");
    assert.deepEqual(left.map((c) => c.name), ["scout", "researcher", "delegate"]);
    assert.equal(cards.size, 0);
});

// --- The Claude stream handler must use it ---------------------------------

test("the Claude path no longer tracks the open delegation in a scalar", () => {
    assert.ok(
        !/activeSubagentId/.test(source),
        "un solo hueco no puede representar varias tarjetas abiertas a la vez",
    );
});

test("a tool_result is matched against every open card", () => {
    assert.ok(
        /subagentCards\.close\(toolId\)/.test(source),
        "el cierre debe buscar la tarjeta por su propio id, no comparar contra la última",
    );
});

test("the end of a Claude run closes every card left open", () => {
    const at = source.indexOf("subagentCards.drain()");
    assert.notEqual(at, -1, "al cerrar el proceso deben cerrarse TODAS las tarjetas pendientes");
    const block = source.slice(at, at + 600);
    assert.match(block, /CHAT_EVENTS\.SUBAGENT_END/, "y cada una debe publicar su cierre");
});
