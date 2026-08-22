import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseSubagentNotify } from "../lib/subagent-notify.mjs";

// Every notice captured from real Pi sessions (~/.pi/agent/sessions/.../*.jsonl,
// entries with customType "subagent-notify"). The only edit is the home
// directory, replaced with a neutral name; nothing this parser reads was
// touched. They are truncated by pi-subagents' 1000-char preview cap and they
// disagree with each other about shape -- which together are the whole reason
// a JSON.parse-and-read-`output` reader was never going to hold.
const REAL = JSON.parse(readFileSync(new URL("./fixtures/subagent-notify.json", import.meta.url), "utf8"));

test("the captured notices really do come in incompatible shapes", () => {
    // Nine notices from real sessions: a fan-out keyed to objects, the same
    // fan-out keyed to plain strings, a single child describing itself, a
    // Return of `null`, a Return of a bare sentence, and a failed workflow with
    // no Return block at all. Anything keyed to one shape breaks on the others.
    assert.ok(REAL.length >= 5, "hacen falta varias capturas reales para que esto pruebe algo");
    const shapes = new Set(REAL.map((e) => {
        const at = e.content.indexOf("Return: ");
        if (at === -1) return "sin-return";
        const fragment = e.content.slice(at + 8).trim();
        if (fragment.startsWith("{")) return fragment.includes('"output"') ? "objetos" : "strings";
        return "primitivo";
    }));
    assert.ok(shapes.size >= 3, `se esperaban varias formas, hay: ${[...shapes].join(", ")}`);
});

test("every captured notice yields something a card can close on", () => {
    // Finishing and being readable are different facts. A notice must always
    // report the first one, whatever shape it arrived in.
    for (const entry of REAL) {
        const notice = parseSubagentNotify(entry.content);
        assert.notEqual(notice, null, `no se reconoció: ${entry.content.slice(0, 60)}`);
        assert.equal(typeof notice.ok, "boolean");
        assert.ok(notice.childCount >= 1);
    }
});

test("reads the agent and the answer out of a truncated notice", () => {
    const explore = REAL.find((e) => e.content.includes("Revisión exploratoria IDUPI"));
    assert.ok(explore, "la captura del run gentle-ai-explore debe existir");
    const parsed = parseSubagentNotify(explore.content);
    assert.equal(parsed.agent, "gentle-ai-explore");
    assert.equal(parsed.ok, true);
    assert.match(parsed.output, /Revisi[oó]n exploratoria IDUPI/);
});

test("decodes escapes instead of leaking them into the card", () => {
    const explore = REAL.find((e) => e.content.includes("Revisión exploratoria IDUPI"));
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
