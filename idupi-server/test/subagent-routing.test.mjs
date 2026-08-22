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
    const explore = real.find((e) => e.content.includes("gentle-ai-explore"));
    const notice = parseSubagentNotify(explore.content);
    const pending = new Map([["card-a", "gentle-ai-explore"]]);
    const picked = resolveNoticeCard(pending, notice);
    assert.equal(picked.id, "card-a");
    const summary = summarizeSubagentResult(notice.output);
    assert.match(summary, /Revisi[oó]n exploratoria IDUPI/);
    assert.ok(!/async run is detached/i.test(summary), "el acuse de despacho no puede ser el resumen");
});
