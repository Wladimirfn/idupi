// ============================================================================
// idupi-server/test/tool-frame-contract.test.mjs
//
// Regression guard for a contract drift the user could see: the tool card had
// no description on Claude and OpenCode, but did on Pi.
//
// chat-events.mjs declares tool_start as `{ id, name, detail? }` and the
// Android client decodes exactly that (`ToolStartPayload(... detail ...)`,
// read as `p.detail ?: ""`). Pi published `detail`; the Claude and OpenCode
// paths published `message`. kotlinx.serialization runs with
// `ignoreUnknownKeys = true`, so the wrong field was dropped in silence and
// the card rendered an empty description with no error anywhere.
//
// Three publishers of one frame is how this happened, so the guard covers all
// of them at once, and checks the Kotlin side too: a field name is only
// correct relative to what the other half reads.
//
// Run (from repo root):
//   node --test idupi-server/test/tool-frame-contract.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = join(HERE, "..", "index.mjs");
const PARSER_FILE = join(HERE, "..", "..", "app", "src", "main", "java", "com",
    "example", "idupi", "data", "remote", "SseFrameParser.kt");

const source = readFileSync(SERVER_FILE, "utf8");

/**
 * Every object literal passed to `publishChatEvent(CHAT_EVENTS.<name>, {...})`,
 * as the list of its top-level keys. Brace-counted rather than regexed so a
 * nested object cannot end the block early.
 */
function publishedFrames(eventName) {
    const marker = `publishChatEvent(CHAT_EVENTS.${eventName}, {`;
    const frames = [];
    let from = 0;
    while (true) {
        const at = source.indexOf(marker, from);
        if (at === -1) return frames;
        let depth = 0;
        let end = at + marker.length - 1; // the opening brace
        for (let i = end; i < source.length; i++) {
            if (source[i] === "{") depth++;
            else if (source[i] === "}") {
                depth--;
                if (depth === 0) { end = i; break; }
            }
        }
        const body = source.slice(at + marker.length, end);
        const keys = [];
        let depthInBody = 0;
        for (const line of body.split("\n")) {
            const trimmed = line.trim();
            const m = depthInBody === 0 && trimmed.match(/^([A-Za-z_$][\w$]*)\s*:/);
            if (m) keys.push(m[1]);
            for (const ch of line) {
                if (ch === "{" || ch === "[") depthInBody++;
                else if (ch === "}" || ch === "]") depthInBody--;
            }
        }
        frames.push({ line: source.slice(0, at).split("\n").length, keys });
        from = end;
    }
}

test("every engine publishes tool_start with the same field names", () => {
    const frames = publishedFrames("TOOL_START");
    assert.ok(frames.length >= 3, `se esperaban los 3 motores, hay ${frames.length}`);
    for (const frame of frames) {
        assert.ok(
            frame.keys.includes("detail"),
            `tool_start en la línea ${frame.line} no manda 'detail': ${frame.keys.join(", ")}`,
        );
        assert.ok(
            !frame.keys.includes("message"),
            `tool_start en la línea ${frame.line} manda 'message', que el cliente descarta`,
        );
    }
});

test("no tool frame carries a field the declared contract does not name", () => {
    const allowed = {
        TOOL_START: new Set(["id", "name", "detail"]),
        TOOL_END: new Set(["id", "name", "ok", "detail"]),
    };
    for (const [eventName, names] of Object.entries(allowed)) {
        for (const frame of publishedFrames(eventName)) {
            for (const key of frame.keys) {
                assert.ok(
                    names.has(key),
                    `${eventName} en la línea ${frame.line} manda '${key}', fuera del contrato`,
                );
            }
        }
    }
});

test("the Android decoder reads the field the server writes", () => {
    const kotlin = readFileSync(PARSER_FILE, "utf8");
    const declared = kotlin.match(/data class ToolStartPayload\(([^)]*)\)/);
    assert.ok(declared, "no se encontró ToolStartPayload en SseFrameParser.kt");

    const fields = [...declared[1].matchAll(/val\s+([A-Za-z_][\w]*)\s*:/g)].map((m) => m[1]);
    // Optional fields default in Kotlin, so absence is silent -- which is why
    // the mismatch never raised anything. Compare the names directly instead.
    for (const frame of publishedFrames("TOOL_START")) {
        for (const key of frame.keys) {
            assert.ok(
                fields.includes(key),
                `el servidor manda '${key}' en tool_start y el cliente no lo declara: ${fields.join(", ")}`,
            );
        }
    }
});
