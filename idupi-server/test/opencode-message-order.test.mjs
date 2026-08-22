// ============================================================================
// idupi-server/test/opencode-message-order.test.mjs
//
// "la respuesta siempre me la muestran arriba de la cronología del uso de
// herramientas o sub agentes" -- reported on OpenCode after the same thing was
// fixed on Pi and on Claude. Same class, third engine.
//
// The OpenCode handler published every `text` part as a bare delta and then, at
// close, published the WHOLE accumulated output as one message_end. The app
// writes a delta into whichever bubble is still streaming, so the first text
// part opened a bubble, the tool and subagent cards were appended below it, and
// the rest of the answer -- including the final message_end -- was written back
// into that bubble above them.
//
// OpenCode's stream has no message-end event to key on, so the boundary is
// derived from what the stream does show: a model that stops writing to call a
// tool has finished what it was saying.
//
// Run (from repo root):
//   node --test idupi-server/test/opencode-message-order.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { MessageBoundary } from "../lib/message-boundary.mjs";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

// --- Deriving the boundary -------------------------------------------------

test("a message is the text written since the last tool call", () => {
    const b = new MessageBoundary();
    b.append("Voy a revisar ");
    b.append("el archivo.");
    assert.equal(b.take(), "Voy a revisar el archivo.");
});

test("text after a tool call is a NEW message, not more of the old one", () => {
    const b = new MessageBoundary();
    b.append("Voy a revisar el archivo.");
    assert.equal(b.take(), "Voy a revisar el archivo.");
    b.append("Listo: terminalize es idempotente.");
    assert.equal(b.take(), "Listo: terminalize es idempotente.");
});

test("two tools in a row do not close an empty bubble between them", () => {
    const b = new MessageBoundary();
    b.append("Busco en dos lugares.");
    assert.equal(b.take(), "Busco en dos lugares.");
    assert.equal(b.take(), null, "sin texto nuevo no hay mensaje que cerrar");
});

test("whitespace between tools is not a message", () => {
    const b = new MessageBoundary();
    b.append("\n\n  ");
    assert.equal(b.take(), null);
    assert.equal(b.pending, false);
});

test("pending reports whether there is a message still open", () => {
    const b = new MessageBoundary();
    assert.equal(b.pending, false);
    b.append("escribiendo");
    assert.equal(b.pending, true);
    b.take();
    assert.equal(b.pending, false);
});

test("a non-string fragment is ignored rather than stringified into the answer", () => {
    const b = new MessageBoundary();
    b.append(undefined);
    b.append(null);
    assert.equal(b.take(), null);
});

// --- The OpenCode handler must use it --------------------------------------

function openCodeHandler() {
    const at = source.indexOf("function runOpenCodeCli(");
    assert.notEqual(at, -1, "no se encontró el manejador de OpenCode");
    return source.slice(at, source.indexOf("\n}", at));
}

test("the OpenCode handler derives a message boundary", () => {
    assert.ok(
        /new MessageBoundary\(\)/.test(openCodeHandler()),
        "sin límite, toda la respuesta se escribe en la burbuja que abrió el primer fragmento",
    );
});

test("the open message closes BEFORE its tool card opens", () => {
    const handler = openCodeHandler();
    const toolBranch = handler.indexOf('event.type === "tool_use"');
    assert.notEqual(toolBranch, -1);
    // The whole branch, not a fixed window: what matters is the order of the
    // two calls inside it, wherever the branch happens to end.
    const branchEnd = handler.indexOf("} catch (e) {", toolBranch);
    assert.notEqual(branchEnd, -1, "no se encontró el final de la rama de herramienta");
    const block = handler.slice(toolBranch, branchEnd);
    const takeAt = block.indexOf("boundary.take()");
    const startAt = block.search(/CHAT_EVENTS\.(SUBAGENT_START|TOOL_START)/);
    assert.notEqual(takeAt, -1, "la herramienta es el final del mensaje anterior");
    assert.notEqual(startAt, -1);
    assert.ok(takeAt < startAt, "el texto debe cerrarse ARRIBA de la tarjeta que abre");
});

test("the end of an OpenCode run does not republish what was already delivered", () => {
    const handler = openCodeHandler();
    assert.ok(
        /lastDeliveredText/.test(handler),
        "cada mensaje ya se entrega al cerrarse; repetirlo al final lo muestra dos veces",
    );
});
