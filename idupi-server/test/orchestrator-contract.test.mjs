// ============================================================================
// idupi-server/test/orchestrator-contract.test.mjs
//
// Pin the canonical SDD contract every engine adapter implements. The
// contract is the boundary that lets index.mjs keep HTTP routing only and
// delegate engine logic through resolveEngine(engineId).
//
//   - PHASE_ALIASES is idempotent: canonicalize(canonicalize(x)) === canonicalize(x)
//   - sdd-proposal ↔ sdd-propose round-trips
//   - pi-cli is accepted as an alias for pi
//   - resolveEngine rejects unknown engine ids explicitly
//
// Run (from repo root):
//   node --test idupi-server/test/orchestrator-contract.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";

import {
    CANONICAL_PHASES,
    PHASE_ALIASES,
    UnknownEngineError,
    canonicalize,
    resolveEngine,
} from "../lib/orchestrator/contract.mjs";

test("CANONICAL_PHASES is frozen and contains the superset", () => {
    assert.ok(Object.isFrozen(CANONICAL_PHASES));
    // OpenCode is the superset; pin a few anchors so a refactor cannot silently
    // drop a phase.
    assert.ok(CANONICAL_PHASES.includes("sdd-propose"));
    assert.ok(CANONICAL_PHASES.includes("sdd-apply"));
    assert.ok(CANONICAL_PHASES.includes("sdd-verify"));
    assert.ok(CANONICAL_PHASES.includes("review-risk"));
    assert.ok(CANONICAL_PHASES.includes("gentle-orchestrator"));
});

test("PHASE_ALIASES is frozen and bidirectional for sdd-proposal", () => {
    assert.ok(Object.isFrozen(PHASE_ALIASES));
    assert.equal(PHASE_ALIASES["sdd-proposal"], "sdd-propose");
});

test("PHASE_ALIASES maps sdd-status and sdd-sync deterministically", () => {
    // Both Pi-only phases collapse to the verify/reporting canonical phase.
    // The spec requires a deterministic sink -- never an error, never passthrough.
    assert.equal(PHASE_ALIASES["sdd-status"], "sdd-verify");
    assert.equal(PHASE_ALIASES["sdd-sync"], "sdd-verify");
});

test("canonicalize normalizes Pi proposal naming to sdd-propose", () => {
    assert.equal(canonicalize("sdd-proposal"), "sdd-propose");
});

test("canonicalize maps sdd-status and sdd-sync to sdd-verify", () => {
    assert.equal(canonicalize("sdd-status"), "sdd-verify");
    assert.equal(canonicalize("sdd-sync"), "sdd-verify");
});

test("canonicalize is a no-op on already-canonical names", () => {
    for (const phase of CANONICAL_PHASES) {
        assert.equal(canonicalize(phase), phase);
    }
});

test("canonicalize is idempotent under repeated application", () => {
    // canonicalize(canonicalize(x)) === canonicalize(x)
    const names = ["sdd-proposal", "sdd-status", "sdd-sync", "sdd-apply", "review-risk"];
    for (const name of names) {
        const once = canonicalize(name);
        const twice = canonicalize(once);
        assert.equal(twice, once, `idempotence broken for ${name}`);
    }
});

test("UnknownEngineError has the documented code and message", () => {
    const err = new UnknownEngineError("copilot");
    assert.equal(err.code, "EUNKNOWN_ENGINE");
    assert.match(err.message, /copilot/);
    assert.ok(err instanceof Error);
});

test("resolveEngine accepts pi, opencode, and claude", async () => {
    const pi = await resolveEngine("pi");
    const opencode = await resolveEngine("opencode");
    const claude = await resolveEngine("claude");
    assert.equal(pi.engineId, "pi");
    assert.equal(opencode.engineId, "opencode");
    assert.equal(claude.engineId, "claude");
});

test("resolveEngine accepts pi-cli as an alias for pi", async () => {
    // EngineLabel.kt and sessions.mjs both treat pi-cli as the chat-side alias
    // for pi. Rejecting pi-cli here would silently break the existing chat path.
    const adapter = await resolveEngine("pi-cli");
    assert.equal(adapter.engineId, "pi");
});

test("resolveEngine normalizes whitespace and case", async () => {
    const adapter = await resolveEngine("  PI  ");
    assert.equal(adapter.engineId, "pi");
});

test("resolveEngine rejects unknown engine ids explicitly", async () => {
    await assert.rejects(() => resolveEngine("copilot"), UnknownEngineError);
    await assert.rejects(() => resolveEngine(""), UnknownEngineError);
    await assert.rejects(() => resolveEngine(null), UnknownEngineError);
    await assert.rejects(() => resolveEngine(undefined), UnknownEngineError);
});

test("resolveEngine never falls through silently", async () => {
    // The spec says "never silently falling through to OpenCode". An unknown id
    // must surface as a thrown UnknownEngineError, not a default adapter.
    try {
        await resolveEngine("kimi");
        assert.fail("expected UnknownEngineError to be thrown");
    } catch (err) {
        assert.ok(err instanceof UnknownEngineError);
        assert.equal(err.code, "EUNKNOWN_ENGINE");
    }
});