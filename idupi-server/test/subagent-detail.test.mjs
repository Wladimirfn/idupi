// ============================================================================
// idupi-server/test/subagent-detail.test.mjs
//
// Regression guard: the chat stopped showing the prompt the model sends to a
// Pi subagent. describeToolInput only looked at path/file_path/command/pattern/
// query/url, and the `subagent` tool from pi-subagents uses neither -- its
// parameters are `agent` (the role) and `task` (the prompt). With no match the
// card fell back to generic text and the real instruction was lost.
//
// Run (from repo root):
//   node --test idupi-server/test/subagent-detail.test.mjs
// ============================================================================

// Static `import` statements are hoisted and run before any module-level code,
// so a plain import would bind the socket before this line ever executed --
// EADDRINUSE against a real server already on 8788. The dynamic import below
// runs after it, which is why sessions-runtime.test.mjs uses the same seam.
process.env.IDUPI_NO_LISTEN = "1";

import test from "node:test";
import assert from "node:assert/strict";

const { describeToolInput, describeSubagentName } = await import("../index.mjs");

test("the subagent task is what the card shows", () => {
    const detail = describeToolInput({
        toolName: "subagent",
        input: { agent: "scout", task: "Contá cuántos archivos .kt hay bajo app/src/main/" },
    });
    assert.equal(detail, "Contá cuántos archivos .kt hay bajo app/src/main/");
});

test("existing tools keep resolving to the field they always used", () => {
    assert.equal(describeToolInput({ input: { command: "ls -la" } }), "ls -la");
    assert.equal(describeToolInput({ input: { file_path: "/tmp/x.txt" } }), "/tmp/x.txt");
    assert.equal(describeToolInput({ input: { pattern: "TODO" } }), "TODO");
});

test("a concrete target still wins over the generic task text", () => {
    // `task` is the broadest field, so it must not shadow the specific one a
    // normal tool provides.
    const detail = describeToolInput({ input: { command: "grep -r x", task: "find things" } });
    assert.equal(detail, "grep -r x");
});

test("a fan-out lists what each child was actually asked to do", () => {
    const detail = describeToolInput({
        toolName: "subagent",
        input: {
            children: [
                { agent: "scout", task: "count kotlin files" },
                { agent: "researcher", task: "explain terminalize" },
            ],
        },
    });
    assert.match(detail, /scout: count kotlin files/);
    assert.match(detail, /researcher: explain terminalize/);
});

test("long tasks are bounded like every other detail", () => {
    const detail = describeToolInput({ input: { task: "x".repeat(500) } });
    assert.ok(detail.length <= 120, `expected <=120 chars, got ${detail.length}`);
    assert.ok(detail.endsWith("..."));
});

test("no recognisable input yields null rather than invented text", () => {
    assert.equal(describeToolInput({ input: { unrelated: 1 } }), null);
    assert.equal(describeToolInput({}), null);
});

test("the card is named after the role, not after the tool", () => {
    // Four cards all labelled "subagent" say nothing; "scout" and "researcher" do.
    assert.equal(describeSubagentName("subagent", { agent: "scout" }), "scout");
    assert.equal(describeSubagentName("subagent", { children: [{ agent: "scout" }, { agent: "researcher" }] }), "scout, researcher");
    // A tool that is its own subagent name keeps it.
    assert.equal(describeSubagentName("sdd-apply", {}), "sdd-apply");
    assert.equal(describeSubagentName("subagent", {}), "subagent");
});
