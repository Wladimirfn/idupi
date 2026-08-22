// ============================================================================
// idupi-server/test/claude-message-order.test.mjs
//
// "el mensaje de respuesta queda arriba de las herramientas, no sigue la
// cronología". Reported on Claude, and the same missing thing Pi was missing:
// a boundary at the end of each assistant message.
//
// A Claude turn is several assistant messages -- a preamble, then the answer
// after the tools. The handler published each one as a bare text delta. The app
// appends a delta to whatever bubble is still streaming, so the first message
// opened a bubble and every later message was written back INTO it, above the
// tool and subagent cards appended below in the meantime.
//
// Run (from repo root):
//   node --test idupi-server/test/claude-message-order.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { planAssistantMessage } from "../lib/claude-stream.mjs";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

// --- Splitting one assistant message ---------------------------------------

test("a preamble plus its tool calls are one message with its tools", () => {
    const plan = planAssistantMessage([
        { type: "text", text: "Voy a lanzar dos subagentes." },
        { type: "tool_use", id: "toolu_A", name: "Task", input: { subagent_type: "Explore" } },
        { type: "tool_use", id: "toolu_B", name: "Task", input: { subagent_type: "Explore" } },
    ]);
    assert.equal(plan.text, "Voy a lanzar dos subagentes.");
    assert.deepEqual(plan.tools.map((t) => t.id), ["toolu_A", "toolu_B"]);
});

test("a message that is only tool calls closes no bubble", () => {
    const plan = planAssistantMessage([
        { type: "tool_use", id: "toolu_A", name: "Glob", input: { pattern: "**/*.kt" } },
    ]);
    assert.equal(plan.text, null, "sin texto no hay mensaje que cerrar, solo la tarjeta");
    assert.equal(plan.tools.length, 1);
});

test("the final answer is a message of its own", () => {
    const plan = planAssistantMessage([{ type: "text", text: "DOS-SUBAGENTES-OK" }]);
    assert.equal(plan.text, "DOS-SUBAGENTES-OK");
    assert.deepEqual(plan.tools, []);
});

test("several text blocks in one message stay one message", () => {
    // They were composed together; splitting them would put a boundary where
    // Claude did not put one.
    const plan = planAssistantMessage([
        { type: "text", text: "Ambos con Explore. " },
        { type: "text", text: "DOS-SUBAGENTES-OK" },
    ]);
    assert.equal(plan.text, "Ambos con Explore. DOS-SUBAGENTES-OK");
});

test("whitespace-only text does not open an empty bubble", () => {
    assert.equal(planAssistantMessage([{ type: "text", text: "\n  \n" }]).text, null);
});

test("junk content does not throw", () => {
    assert.deepEqual(planAssistantMessage(null), { text: null, tools: [] });
    assert.deepEqual(planAssistantMessage([null, 7, { type: "thinking" }]), { text: null, tools: [] });
});

test("a tool call with no id still gets one, so its card can close", () => {
    const plan = planAssistantMessage([{ type: "tool_use", name: "Read" }]);
    assert.equal(typeof plan.tools[0].id, "string");
    assert.ok(plan.tools[0].id.length > 0);
});

// --- The Claude handler must publish that boundary -------------------------

test("the Claude handler closes each assistant message", () => {
    assert.ok(
        /planAssistantMessage\(/.test(source),
        "sin límite por mensaje, cada respuesta se escribe en la burbuja de la primera",
    );
});

test("a message's text is closed before its own tool cards open", () => {
    const at = source.indexOf("planAssistantMessage(");
    assert.notEqual(at, -1);
    const block = source.slice(at, at + 1400);
    const closeAt = block.indexOf("CHAT_EVENTS.MESSAGE_END");
    const toolAt = block.indexOf("for (const tool of plan.tools)");
    assert.notEqual(closeAt, -1, "el mensaje debe cerrarse, no solo emitirse como delta");
    assert.notEqual(toolAt, -1, "las herramientas del mensaje se publican después");
    assert.ok(closeAt < toolAt, "el texto del mensaje va ARRIBA de las tarjetas que abre");
});

test("the end of the run does not republish an answer already delivered", () => {
    // The close publishes cleanResult; repeating the last message would show the
    // final answer twice now that each message is delivered on its own.
    assert.ok(
        /lastDeliveredText/.test(source),
        "hace falta recordar lo último entregado para no duplicarlo al cerrar",
    );
});
