// ============================================================================
// idupi-server/test/chat-result-transport.test.mjs
//
// Two defects the per-message delivery uncovered.
//
// 1. The answer arrived TWICE on OpenCode: once as the messages the SSE stream
//    delivered, then again as one blob at the bottom. The blob is the app's own
//    doing -- RealIduPiClient.sendMessage emits a second MessageEnded with the
//    body of POST /chat/message -- so the answer has always had two transports.
//    It stayed invisible because the last SSE message used to carry that same
//    whole-turn blob, and the app's dedup matched it. Delivering each message on
//    its own broke the match, and the blob became visible.
//
//    Pi never had the bug: it resolves `this.lastAnswer`, the LAST message,
//    which is exactly what the chat last showed. Claude and OpenCode resolved
//    the whole turn concatenated.
//
// 2. Claude's rate_limit_event announced an exhausted quota on every occurrence
//    AND assigned that warning over fullOutput, destroying the answer.
//
// Run (from repo root):
//   node --test idupi-server/test/chat-result-transport.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { describeRateLimit } from "../lib/rate-limit-notice.mjs";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

function bodyOf(fnName) {
    const at = source.indexOf(`function ${fnName}(`);
    assert.notEqual(at, -1, `no se encontró ${fnName}`);
    return source.slice(at, source.indexOf("\n}", at));
}

// --- 1. One answer, not two ------------------------------------------------

test("Pi resolves the last message, which is why Pi never duplicated", () => {
    // The control: the engine that does NOT have the bug, and the shape the
    // other two have to match.
    assert.ok(
        /const resultText = this\.lastAnswer \|\|/.test(source),
        "Pi entrega como resultado HTTP el último mensaje, no el turno entero",
    );
});

test("OpenCode resolves what the chat last showed, not the whole turn", () => {
    const body = bodyOf("runOpenCodeCli");
    assert.ok(
        /resolve\(lastDeliveredText \|\| cleanResult\)/.test(body),
        "devolver el turno concatenado lo hace aparecer otra vez como bloque al final",
    );
});

test("Claude resolves what the chat last showed, not the whole turn", () => {
    const body = bodyOf("runClaudeCli");
    assert.ok(
        /resolve\(lastDeliveredText \|\| cleanResult\)/.test(body),
        "devolver el turno concatenado lo hace aparecer otra vez como bloque al final",
    );
});

test("a turn that delivered nothing still answers the POST", () => {
    // `lastDeliveredText || cleanResult` -- a timeout notice or an exit code has
    // to reach the app, which is the only thing that request will ever get.
    for (const fn of ["runOpenCodeCli", "runClaudeCli"]) {
        assert.ok(/\|\| cleanResult\)/.test(bodyOf(fn)), `${fn} debe conservar el respaldo`);
    }
});

// --- 2. A rate-limit notice that is not a false alarm ----------------------

test("an allowed request raises no warning", () => {
    assert.equal(describeRateLimit({ status: "allowed", resetsAt: 1_800_000_000 }), null);
});

test("a payload with no status raises no warning", () => {
    // The shape was never observed. Guessing produced the false alarm.
    assert.equal(describeRateLimit({ resetsAt: 1_800_000_000 }), null);
    assert.equal(describeRateLimit({}), null);
    assert.equal(describeRateLimit(null), null);
    assert.equal(describeRateLimit(undefined), null);
});

test("a rejected request does raise the warning", () => {
    const notice = describeRateLimit({ status: "rejected", resetsAt: 1_800_000_000 });
    assert.ok(notice, "un límite realmente alcanzado sí debe avisarse");
    assert.match(notice, /Límite de sesión de Claude/);
    assert.match(notice, /rejected/);
});

test("a warning status is reported without a reset time it does not have", () => {
    const notice = describeRateLimit({ status: "allowed_warning" });
    assert.ok(notice);
    assert.match(notice, /próximamente/, "sin resetsAt no se inventa una hora");
});

test("the rate-limit notice never overwrites the model's answer", () => {
    const body = bodyOf("runClaudeCli");
    const at = body.indexOf('event.type === "rate_limit_event"');
    assert.notEqual(at, -1);
    const block = body.slice(at, at + 800);
    assert.ok(
        !/fullOutput\s*=/.test(block),
        "asignar el aviso sobre fullOutput reemplaza la respuesta por una advertencia",
    );
    assert.ok(
        !/CHAT_EVENTS\.TEXT_DELTA/.test(block),
        "emitirlo como texto lo escribe dentro del mensaje que se está redactando",
    );
    assert.ok(/describeRateLimit\(/.test(block), "debe decidir con la señal, no con la ocurrencia");
});
