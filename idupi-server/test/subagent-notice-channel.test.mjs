// ============================================================================
// idupi-server/test/subagent-notice-channel.test.mjs
//
// The delegation card stayed open forever even though the subagent had
// finished and the orchestrator had already reported its answer.
//
// The handler waited on `entry_appended`, a channel picked by reading Pi's
// AgentSessionEvent union rather than by observing a run. Pi injects the
// completion notice with sendCustomMessage(), which appends the entry and then
// emits message_start/message_end with role "custom"
// (dist/core/agent-session.js:1096). `entry_appended` is emitted only by
// appendEntry() (dist/core/agent-session.js:1868), a different API writing a
// `custom` entry. The event the handler waited for was never coming.
//
// Marking entry_appended as "mapped" on the same assumption silenced the
// unmapped-event log, which is what hid it.
//
// Run (from repo root):
//   node --test idupi-server/test/subagent-notice-channel.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

test("the notice is read from the custom message Pi actually emits", () => {
    assert.match(
        source,
        /event\.type === "message_end" && event\.message\?\.role === "custom"\s*\n\s*&& event\.message\.customType === "subagent-notify"/,
        "el aviso llega como message_end con role custom, no como entry_appended",
    );
});

test("no handler waits on entry_appended for the notice", () => {
    const stale = source.match(/entry_appended[\s\S]{0,200}?subagent-notify/);
    assert.equal(stale, null, "quedó un handler esperando un evento que Pi no emite para esto");
});

test("entry_appended is not claimed as mapped without having been seen", () => {
    const mapped = source.match(/const MAPPED_RPC_EVENTS = new Set\(\[[\s\S]*?\]\);/);
    assert.ok(mapped, "no se encontró MAPPED_RPC_EVENTS");
    assert.ok(
        !/"entry_appended"/.test(mapped[0]),
        "marcarlo como mapeado apaga el aviso que delataría que nunca llega",
    );
});

test("a custom message's array form is read, not silently ignored", () => {
    // CustomMessageEntry.content is `string | (TextContent | ImageContent)[]`.
    assert.match(source, /function extractCustomMessageText\(content\)/);
    const fn = source.match(/function extractCustomMessageText\(content\) \{[\s\S]*?\n\}/)[0];
    assert.match(fn, /typeof content === "string"/, "debe aceptar la forma string");
    assert.match(fn, /Array\.isArray\(content\)/, "y también la forma array");
});

test("an assistant message is still not mistaken for a completion notice", () => {
    // The chat text handler filters on role assistant; the two must not overlap.
    assert.match(
        source,
        /event\.type === "message_end" && event\.message\?\.role === "assistant"/,
        "el cierre de mensajes del asistente debe seguir filtrando por su propio rol",
    );
});
