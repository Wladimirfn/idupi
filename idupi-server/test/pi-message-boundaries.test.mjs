// ============================================================================
// idupi-server/test/pi-message-boundaries.test.mjs
//
// One Pi turn holds several assistant messages: the short preamble it writes
// before reaching for tools, then the real answer after them. The server
// accumulated all of them into a single buffer and published ONE message_end
// at agent_end, so the preamble never arrived as its own chat message and the
// one that did arrive overwrote what was on screen.
//
// A retry made it worse: clearing the shared buffer to drop the failed
// attempt also dropped the preamble Pi had already finished.
//
// Run (from repo root):
//   node --test idupi-server/test/pi-message-boundaries.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

/** The body of the `if` block handling one Pi RPC event type. */
function handlerFor(pattern) {
    const at = source.search(pattern);
    if (at === -1) return null;
    const open = source.indexOf("{", at);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        if (source[i] === "{") depth++;
        else if (source[i] === "}") {
            depth--;
            if (depth === 0) return source.slice(open, i + 1);
        }
    }
    return null;
}

test("each assistant message Pi closes is published as its own chat message", () => {
    const handler = handlerFor(/if \(event\.type === "message_end" && event\.message\?\.role === "assistant"\)/);
    assert.ok(handler, "no se encontró el manejo de message_end");
    assert.match(
        handler,
        /publishChatEvent\(CHAT_EVENTS\.MESSAGE_END/,
        "message_end no publica el mensaje: el preámbulo nunca llega al chat",
    );
});

test("closing a message clears the buffer so the next one starts empty", () => {
    const handler = handlerFor(/if \(event\.type === "message_end" && event\.message\?\.role === "assistant"\)/);
    assert.match(
        handler,
        /this\.currentOutput = ""/,
        "sin limpiar, el mensaje siguiente arrastra el texto del anterior",
    );
});

test("a closed message is not discarded by a later retry", () => {
    // The regression this file exists for: the retry cleared the one buffer
    // the whole turn shared, so it took the preamble with it.
    const handler = handlerFor(/if \(event\.type === "auto_retry_start"\)/);
    assert.ok(handler, "no hay manejo de auto_retry_start");
    assert.match(handler, /this\.currentOutput = ""/, "el reintento debe descartar su intento fallido");
    assert.ok(
        !/this\.lastAnswer\s*=\s*""/.test(handler),
        "el reintento no puede borrar mensajes que Pi ya cerró",
    );
});

test("the turn's end publishes leftover text and only the guarded fallback", () => {
    const handler = handlerFor(/if \(event\.type === "agent_end" && event\.willRetry !== true && this\.pendingResolve\)/);
    assert.ok(handler, "no se encontró el cierre del turno");
    assert.match(handler, /leftover/, "agent_end debe publicar solo lo que quedó sin cerrar");
    const publishes = handler.match(/publishChatEvent\(CHAT_EVENTS\.MESSAGE_END/g) || [];
    // TWO guarded branches, at most ONE fires at runtime: the fallback is
    // gated on `!publishedLeftover`, so agent_end never re-emits a message
    // that a message_end already published. The count was updated from 1 when
    // the "always publish via SSE" fix (100% of Pi's output) added the
    // guarded fallback branch — the invariant under test is the guard, not a
    // literal single call site.
    assert.equal(publishes.length, 2, "leftover + fallback (mutuamente excluyentes en runtime)");
    assert.match(handler, /!publishedLeftover/, "el fallback no puede reemitir un mensaje ya publicado");
});

test("the POST answers with the last message, not with the filler", () => {
    const handler = handlerFor(/if \(event\.type === "agent_end" && event\.willRetry !== true && this\.pendingResolve\)/);
    assert.match(
        handler,
        /this\.lastAnswer \|\| "Respuesta procesada correctamente por Pi CLI\."/,
        "el relleno debe ser el último recurso, detrás del último mensaje real",
    );
});

test("lastAnswer is reset when a new message starts, not carried over", () => {
    assert.match(
        source,
        /this\.currentOutput = "";\s*\n\s*this\.lastAnswer = "";\s*\n\s*return new Promise/,
        "sin reiniciarlo, un turno podría responder con la respuesta del turno anterior",
    );
});
