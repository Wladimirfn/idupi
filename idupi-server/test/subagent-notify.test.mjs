import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseSubagentNotify } from "../lib/subagent-notify.mjs";

// Captured verbatim from a real Pi session
// (~/.pi/agent/sessions/.../*.jsonl, entries with customType
// "subagent-notify"). Both are truncated by pi-subagents' 1000-char preview
// cap, which is exactly why a JSON.parse-only reader is not enough.
const REAL = JSON.parse(readFileSync(new URL("./fixtures/subagent-notify.json", import.meta.url), "utf8"));

test("every captured notice is genuinely truncated JSON", () => {
    assert.ok(REAL.length >= 2, "fixture debe traer al menos dos capturas reales");
    for (const entry of REAL) {
        const fragment = entry.content.slice(entry.content.indexOf("Return: ") + 8);
        assert.throws(() => JSON.parse(fragment.trim()));
    }
});

test("reads the agent and the answer out of a truncated notice", () => {
    const explore = REAL.find((e) => e.content.includes("gentle-ai-explore"));
    assert.ok(explore, "la captura del run gentle-ai-explore debe existir");
    const parsed = parseSubagentNotify(explore.content);
    assert.equal(parsed.agent, "gentle-ai-explore");
    assert.equal(parsed.ok, true);
    assert.match(parsed.output, /Revisi[oó]n exploratoria IDUPI/);
});

test("decodes escapes instead of leaking them into the card", () => {
    const explore = REAL.find((e) => e.content.includes("gentle-ai-explore"));
    const parsed = parseSubagentNotify(explore.content);
    const BACKSLASH_N = String.fromCharCode(92) + "n";
    assert.ok(!parsed.output.includes(BACKSLASH_N), "los escapes deben quedar decodificados");
    assert.ok(parsed.output.includes("\n"), "el salto de línea real debe estar presente");
    assert.ok(!/ Trace: \d+ event\(s\)\.$/.test(parsed.output), "la cola que agrega pi no es parte de la respuesta");
});

test("reads a short answer from the delegate run", () => {
    const pong = REAL.find((e) => e.content.includes('"agent": "delegate"'));
    assert.ok(pong, "la captura del run delegate debe existir");
    const parsed = parseSubagentNotify(pong.content);
    assert.equal(parsed.agent, "delegate");
    assert.equal(parsed.output, "PONG");
    assert.equal(parsed.runId, "816e1da0-bd31-445b-a8a5-33b3ed994b41");
});

test("parses a complete, untruncated notice through JSON", () => {
    const content = [
        "Background task completed: **workflow**",
        "",
        `Workflow completed with 1 child run(s). Return: ${JSON.stringify({
            key: "main", ok: true, agent: "scout", runId: "abc", output: "todo listo",
        })} Trace: 2 event(s).`,
    ].join("\n");
    const parsed = parseSubagentNotify(content);
    assert.equal(parsed.agent, "scout");
    assert.equal(parsed.output, "todo listo");
    assert.equal(parsed.ok, true);
});

test("reports a failed run as failed", () => {
    const content = 'Background task failed: **worker**\n\nReturn: {"ok": false, "agent": "worker", "output": "no pude"';
    const parsed = parseSubagentNotify(content);
    assert.equal(parsed.ok, false);
    assert.equal(parsed.agent, "worker");
    assert.equal(parsed.output, "no pude");
});

test("a truncation landing on a lone backslash does not throw", () => {
    const content = 'Background task completed: **w**\n\nReturn: {"agent": "w", "output": "ruta C:' + String.fromCharCode(92);
    const parsed = parseSubagentNotify(content);
    assert.equal(parsed.agent, "w");
    assert.equal(typeof parsed.output, "string");
});

test("text that is not a completion notice is not read as one", () => {
    assert.equal(parseSubagentNotify("hola, todo bien"), null);
    assert.equal(parseSubagentNotify(""), null);
    assert.equal(parseSubagentNotify(null), null);
});
