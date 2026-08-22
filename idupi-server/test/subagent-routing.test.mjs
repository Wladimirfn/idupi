import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveNoticeCard, parseSubagentNotify } from "../lib/subagent-notify.mjs";

process.env.IDUPI_NO_LISTEN = "1";
const { isSubagentTool, describeSubagentName, summarizeSubagentResult } = await import("../index.mjs");

test("OpenCode's delegation tool is recognised as one", () => {
    // The regression the user hit: OpenCode calls its delegation tool `task`,
    // the OpenCode-side predicate never listed it, and the run produced no
    // card at all -- only the orchestrator's final answer.
    assert.equal(isSubagentTool("task", {}), true);
    assert.equal(isSubagentTool("Task", { subagent_type: "Explore" }), true);
});

test("every engine's delegation tool is recognised", () => {
    for (const name of ["subagent", "invoke_subagent", "delegate", "explore", "plan", "build"]) {
        assert.equal(isSubagentTool(name, {}), true, `${name} debería contar como subagente`);
    }
    assert.equal(isSubagentTool("sdd-apply", {}), true);
    assert.equal(isSubagentTool("review-risk", {}), true);
    assert.equal(isSubagentTool("jd-judge-a", {}), true);
});

test("a role or subagent_type parameter identifies a delegation by itself", () => {
    assert.equal(isSubagentTool("cualquier_cosa", { role: "scout" }), true);
    assert.equal(isSubagentTool("cualquier_cosa", { subagent_type: "Explore" }), true);
    assert.equal(isSubagentTool("cualquier_cosa", { role: "" }), false);
});

test("an ordinary tool is not turned into a delegation card", () => {
    for (const name of ["bash", "read", "write", "grep", "engram_mem_save", "mcp__agentmail__send_email"]) {
        assert.equal(isSubagentTool(name, {}), false, `${name} no es un subagente`);
    }
    assert.equal(isSubagentTool("", {}), false);
    assert.equal(isSubagentTool(null, null), false);
});

test("the card is labelled with the role, not the tool name", () => {
    assert.equal(describeSubagentName("task", { subagent_type: "Explore" }), "Explore");
    assert.equal(describeSubagentName("task", { role: "scout" }), "scout");
    assert.equal(describeSubagentName("subagent", { agent: "gentle-ai-explore" }), "gentle-ai-explore");
    assert.equal(describeSubagentName("task", {}), "task");
});

test("a completion notice closes the card its role opened", () => {
    const pending = new Map([["card-1", "scout"], ["card-2", "gentle-ai-explore"]]);
    const picked = resolveNoticeCard(pending, { agent: "gentle-ai-explore", runId: "r1" });
    assert.deepEqual(picked, { id: "card-2", name: "gentle-ai-explore", isNew: false });
});

test("an unmatched role closes the longest-waiting card", () => {
    const pending = new Map([["card-1", "scout"], ["card-2", "worker"]]);
    const picked = resolveNoticeCard(pending, { agent: "otro", runId: "r1" });
    assert.equal(picked.id, "card-1");
    assert.equal(picked.isNew, false);
});

test("with no open card the answer still gets one instead of vanishing", () => {
    const picked = resolveNoticeCard(new Map(), { agent: "scout", runId: "run-9" });
    assert.deepEqual(picked, { id: "run-9", name: "scout", isNew: true });
});

test("the real captured notice resolves to a readable card summary", () => {
    const real = JSON.parse(readFileSync(new URL("./fixtures/subagent-notify.json", import.meta.url), "utf8"));
    const explore = real.find((e) => e.content.includes("Revisión exploratoria IDUPI"));
    const notice = parseSubagentNotify(explore.content);
    const pending = new Map([["card-a", "gentle-ai-explore"]]);
    const picked = resolveNoticeCard(pending, notice);
    assert.equal(picked.id, "card-a");
    const summary = summarizeSubagentResult(notice.output);
    assert.match(summary, /Revisi[oó]n exploratoria IDUPI/);
    assert.ok(!/async run is detached/i.test(summary), "el acuse de despacho no puede ser el resumen");
});

// --- Only a launch opens a card -------------------------------------------
// A two-subagent run produced FOUR cards. pi-subagents reuses one tool for
// launching, polling and stopping; the session shows one launch, one
// subagent_wait and two {action:"status"} polls, and every one of them opened
// a delegation card. The schema settles it: `action` is "Optional
// management/control action. Omit this field for structured single-child or
// workflowScript execution."

test("a management action is a question about a run, not a new delegation", () => {
    assert.equal(isSubagentTool("subagent", { action: "status", id: "abc" }), false);
    assert.equal(isSubagentTool("subagent", { action: "status" }), false);
    assert.equal(isSubagentTool("subagent", { action: "stop", id: "abc" }), false);
    assert.equal(isSubagentTool("subagent", { action: "schedule.create" }), false);
});

test("waiting on runs that already exist opens no card", () => {
    assert.equal(isSubagentTool("subagent_wait", { all: true }), false);
    assert.equal(isSubagentTool("subagent_wait", { id: "abc", nonBlocking: true }), false);
});

test("a launch still opens one, with or without a workflow", () => {
    assert.equal(isSubagentTool("subagent", { agent: "scout", task: "contá archivos" }), true);
    assert.equal(isSubagentTool("subagent", { async: true, workflowScript: "await runs.run(...)" }), true);
    assert.equal(isSubagentTool("invoke_subagent", { agent: "scout" }), true);
});

test("the exact call sequence of the live run yields one card, not four", () => {
    // Verbatim from the session that reported four cards.
    const calls = [
        ["subagent", { async: true, workflowScript: "const [scout, researcher] = await Promise.all([...])" }],
        ["subagent_wait", { all: true }],
        ["subagent", { action: "status", id: "fcbe855c-2ab9-47d4-b1a8-2d87ef1c535a" }],
        ["subagent", { action: "status", id: "b49b8c3d-2765-4cbb-b929-060209929530" }],
    ];
    const cards = calls.filter(([name, input]) => isSubagentTool(name, input));
    assert.equal(cards.length, 1, `abrieron ${cards.length} tarjetas: ${JSON.stringify(cards.map((c) => c[0]))}`);
});

test("a fan-out card is labelled with its roles instead of the tool name", () => {
    const script = "const [a, b] = await Promise.all([\n  runs.run('scout', { agent: 'gentle-ai-explore', task: `x` }),\n  runs.run('researcher', { agent: 'gentle-ai-research', task: `y` }),\n])";
    assert.equal(
        describeSubagentName("subagent", { async: true, workflowScript: script }),
        "gentle-ai-explore, gentle-ai-research",
    );
    // Unrecognisable script: fall back rather than invent a name.
    assert.equal(describeSubagentName("subagent", { workflowScript: "haceAlgo()" }), "subagent");
});

test("a fan-out notice reports how many children ran", () => {
    const real = JSON.parse(readFileSync(new URL("./fixtures/subagent-notify.json", import.meta.url), "utf8"));
    const fanout = real.find((e) => e.content.includes('"scout": "Total'));
    assert.ok(fanout, "la captura del fan-out de dos hijos debe existir");
    const notice = parseSubagentNotify(fanout.content);
    assert.equal(notice.childCount, 2);
    assert.equal(notice.agent, "scout");
    assert.match(notice.output, /Total .?\.kt.? files/);
});
