// ============================================================================
// idupi-server/test/async-subagent.test.mjs
//
// A subagent dispatched asynchronously returns immediately, and what comes back
// from tool_execution_end is a dispatch receipt, not an answer:
//
//   Run fan-out: 0/64 used, 64 remaining Async workflow
//   [e18a63cf-...]
//   The async run is detached and running in the background.
//   You are in an interactive session. By default, return control to the user...
//
// The card printed that under "Respuesta / Salida en Vivo" and labelled it
// "Completado", while the real answer (PONG) arrived later through
// tool_execution_update -- an event this server does not map at all.
//
// Showing a receipt as an answer is worse than showing nothing: it reads as if
// the subagent replied with instructions meant for the model.
//
// Run (from repo root):
//   node --test idupi-server/test/async-subagent.test.mjs
// ============================================================================

process.env.IDUPI_NO_LISTEN = "1";

import test from "node:test";
import assert from "node:assert/strict";

const { isAsyncDispatchReceipt, summarizeSubagentResult } = await import("../index.mjs");

test("the real receipt observed in a live run is recognised", () => {
    const captured = [
        "Run fan-out: 0/64 used, 64 remaining Async workflow",
        "[e18a63cf-1809-45bc-b923-360afc8ebdd0]",
        "The async run is detached and running in the background.",
        "You are in an interactive session. By default, return control to the user now;",
        "Pi will wake you on completion when the run finishes or needs attention.",
    ].join("\n");
    assert.equal(isAsyncDispatchReceipt(captured), true);
});

test("a genuine answer is never mistaken for a receipt", () => {
    assert.equal(isAsyncDispatchReceipt("PONG"), false);
    assert.equal(isAsyncDispatchReceipt("Total: 60 archivos .kt"), false);
    assert.equal(isAsyncDispatchReceipt(""), false);
    assert.equal(isAsyncDispatchReceipt(null), false);
    // Prose that merely mentions background work is not a receipt.
    assert.equal(
        isAsyncDispatchReceipt("El proceso corre en segundo plano según la documentación."),
        false,
    );
});

test("a receipt is reported as dispatched, not as the subagent's answer", () => {
    const receipt = "Run fan-out: 0/64 used, 64 remaining Async workflow\nThe async run is detached and running in the background.";
    const summary = summarizeSubagentResult(receipt);

    assert.doesNotMatch(summary, /async run is detached/);
    assert.doesNotMatch(summary, /return control to the user/);
    assert.match(summary, /segundo plano/i);
});

test("a real answer passes through untouched", () => {
    assert.equal(summarizeSubagentResult("PONG"), "PONG");
});

test("no result at all still says so plainly", () => {
    assert.match(summarizeSubagentResult(null), /Subagente/);
    assert.match(summarizeSubagentResult(""), /Subagente/);
});

test("a long answer stays bounded", () => {
    const summary = summarizeSubagentResult("y".repeat(1000));
    assert.ok(summary.length <= 300, `got ${summary.length}`);
});
