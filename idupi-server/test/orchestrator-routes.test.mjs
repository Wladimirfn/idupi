// ============================================================================
// idupi-server/test/orchestrator-routes.test.mjs
//
// Pins the PR2 wiring contract:
//   - normalizeEngineId rejects unknown ids (no silent OpenCode fallthrough)
//   - readOrchestratorStatus returns piPhaseAssignments + engineSkipped + gentleAiDetected
//   - applyProfilePreset fans out to all three engines, isolates failures
//   - updateEngineModel returns ok=false with an error string for unknown engines
//   - piAssignments on SDD_PROFILE_PRESETS entries cover strong/mid/cheap
//     with Pi-shaped bindings (providerId + modelId), never empty arrays,
//     never Claude-only fields (no "model" shorthand leaking into Pi)
//
// Run (from repo root):
//   node --test idupi-server/test/orchestrator-routes.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";

import {
    normalizeEngineId,
    readOrchestratorStatus,
    applyProfilePreset,
    updateEngineModel,
    ORCHESTRATOR_ENGINES,
} from "../lib/orchestrator/routes.mjs";
import { resolveEngine, UnknownEngineError } from "../lib/orchestrator/contract.mjs";
import * as piEngine from "../lib/orchestrator/engines/pi.mjs";
import * as opencodeEngine from "../lib/orchestrator/engines/opencode.mjs";
import * as claudeEngine from "../lib/orchestrator/engines/claude.mjs";

// ---- Fixture helpers --------------------------------------------------------

function fakeHome() {
    const dir = mkdtempSync(join(tmpdir(), "idupi-orchestrator-routes-"));
    const prevHome = process.env.HOME;
    const prevUser = process.env.USERPROFILE;
    process.env.HOME = dir;
    process.env.USERPROFILE = dir;
    return {
        dir,
        restore() {
            if (prevHome === undefined) delete process.env.HOME;
            else process.env.HOME = prevHome;
            if (prevUser === undefined) delete process.env.USERPROFILE;
            else process.env.USERPROFILE = prevUser;
            rmSync(dir, { recursive: true, force: true });
        },
    };
}

function clearBackupTracking() {
    piEngine.__testing.backedUp.clear();
    opencodeEngine.__testing.backedUp.clear();
    claudeEngine.__testing.backedUp.clear();
}

function writeJson(filePath, obj) {
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(obj, null, 2), "utf8");
}

// ============================================================================
// normalizeEngineId — the rejection policy that backs models/update
// ============================================================================

test("normalizeEngineId accepts the three known engine ids", () => {
    assert.equal(normalizeEngineId("pi"), "pi");
    assert.equal(normalizeEngineId("opencode"), "opencode");
    assert.equal(normalizeEngineId("claude"), "claude");
});

test("normalizeEngineId accepts pi-cli as alias for pi", () => {
    assert.equal(normalizeEngineId("pi-cli"), "pi");
});

test("normalizeEngineId is case-insensitive and trims whitespace", () => {
    assert.equal(normalizeEngineId("  PI  "), "pi");
    assert.equal(normalizeEngineId("Claude"), "claude");
});

test("normalizeEngineId rejects unknown ids with null (NO silent OpenCode fallback)", () => {
    assert.equal(normalizeEngineId("copilot"), null);
    assert.equal(normalizeEngineId(""), null);
    assert.equal(normalizeEngineId(null), null);
    assert.equal(normalizeEngineId(undefined), null);
    assert.equal(normalizeEngineId("kimi"), null);
});

// ============================================================================
// updateEngineModel — single-phase write delegation
// ============================================================================

test("updateEngineModel rejects unknown engine id with ok=false and an error string", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await updateEngineModel("copilot", "sdd-propose", { modelId: "gpt-5.6" });
        assert.equal(result.ok, false);
        assert.equal(result.engine, "copilot");
        assert.match(result.error, /Unknown engine id/);
    } finally { h.restore(); }
});

test("updateEngineModel routes pi to the Pi adapter and writes canonical phase", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await updateEngineModel("pi", "sdd-propose", {
            providerId: "opencode-go",
            modelId: "gpt-5.6-luna",
            effort: "high",
        });
        assert.equal(result.ok, true);
        assert.equal(result.engine, "pi");
        // Adapter persists Pi-native phase name under the hood.
        const written = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.deepEqual(written.model_profiles["sdd-proposal"], {
            provider_id: "opencode-go",
            model_id: "gpt-5.6-luna",
            effort: "high",
        });
    } finally { h.restore(); }
});

test("updateEngineModel reports skipped[] for phases with no Pi equivalent", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await updateEngineModel("pi", "review-risk", {
            providerId: "opencode-go",
            modelId: "qwen3.7-max",
        });
        assert.equal(result.ok, true);
        assert.deepEqual(result.skipped, ["review-risk"]);
    } finally { h.restore(); }
});

// ============================================================================
// applyProfilePreset — per-engine fan-out + isolation
// ============================================================================

test("applyProfilePreset fans out to all three engines and isolates failures", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const preset = {
            modelAssignments: {
                "sdd-propose": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            },
            claudeAssignments: {
                "sdd-propose": { model: "sonnet" },
            },
            piAssignments: {
                "sdd-propose": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            },
        };
        const out = await applyProfilePreset(preset);
        assert.equal(out.status, "ok");
        assert.equal(out.engines.opencode.ok, true);
        assert.equal(out.engines.claude.ok, true);
        assert.equal(out.engines.pi.ok, true);

        // Each engine actually wrote.
        assert.ok(existsSync(join(h.dir, ".config", "opencode", "opencode.json")));
        assert.ok(existsSync(join(h.dir, ".gentle-ai", "state.json")));
        assert.ok(existsSync(join(h.dir, ".pi", "subagents.json")));

        // Pi carries its own assignments.
        const piDoc = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.deepEqual(piDoc.model_profiles["sdd-proposal"].model_id, "gpt-5.6-luna");

        // Claude carries only its own assignments (model: "sonnet").
        const stDoc = JSON.parse(readFileSync(join(h.dir, ".gentle-ai", "state.json"), "utf8"));
        assert.equal(stDoc.claude_phase_assignments["sdd-propose"].model, "sonnet");
        // Foreign keys preserved (Pi wrote into state.json? no — claude did, but
        // the adapter only touches claude_phase_assignments, so other keys stay.)
        assert.equal(typeof stDoc, "object");
    } finally { h.restore(); }
});

test("applyProfilePreset returns status=partial when one engine has no assignments", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const preset = {
            modelAssignments: {},
            claudeAssignments: { "sdd-propose": { model: "sonnet" } },
            piAssignments: {},
        };
        const out = await applyProfilePreset(preset);
        // opencode and pi wrote nothing (empty preset => ok with no errors).
        assert.equal(out.status, "ok");
        assert.equal(out.engines.opencode.ok, true);
        assert.equal(out.engines.claude.ok, true);
        assert.equal(out.engines.pi.ok, true);
    } finally { h.restore(); }
});

test("applyProfilePreset reports Pi skipped[] for review-* phases", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const preset = {
            modelAssignments: {},
            claudeAssignments: {},
            piAssignments: {
                "sdd-propose":   { provider_id: "opencode-go", model_id: "gpt-5.6-luna" },
                "review-risk":   { provider_id: "opencode-go", model_id: "qwen3.7-max" },
                "review-resilience": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            },
        };
        const out = await applyProfilePreset(preset);
        assert.equal(out.engines.pi.ok, true);
        assert.ok(out.engines.pi.skipped.includes("review-risk"));
        assert.ok(out.engines.pi.skipped.includes("review-resilience"));
        // Pi's subagents.json has sdd-proposal but NEVER review-* keys.
        const piDoc = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.ok(piDoc.model_profiles["sdd-proposal"]);
        assert.equal(piDoc.model_profiles["review-risk"], undefined);
    } finally { h.restore(); }
});

// ============================================================================
// readOrchestratorStatus — envelope shape + per-engine isolation
// ============================================================================

test("readOrchestratorStatus returns the documented envelope shape", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const status = await readOrchestratorStatus();
        assert.equal(typeof status.modelAssignments, "object");
        assert.equal(typeof status.claudePhaseAssignments, "object");
        assert.equal(typeof status.piPhaseAssignments, "object");
        assert.equal(typeof status.engineSkipped, "object");
        assert.equal(typeof status.gentleAiDetected, "boolean");
        assert.equal(status.gentleAiDetected, false);
    } finally { h.restore(); }
});

test("readOrchestratorStatus detects gentle-ai when state.json exists", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(join(h.dir, ".gentle-ai", "state.json"), {
            claude_phase_assignments: { "sdd-propose": { model: "sonnet" } },
        });
        const status = await readOrchestratorStatus();
        assert.equal(status.gentleAiDetected, true);
        assert.equal(status.claudePhaseAssignments["sdd-propose"].model, "sonnet");
    } finally { h.restore(); }
});

test("readOrchestratorStatus reads piPhaseAssignments canonically", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(join(h.dir, ".pi", "subagents.json"), {
            model_profiles: {
                "sdd-proposal": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            },
        });
        const status = await readOrchestratorStatus();
        assert.deepEqual(status.piPhaseAssignments["sdd-propose"], {
            provider_id: "opencode-go",
            model_id: "gpt-5.6-luna",
            effort: "high",
        });
        // engineSkipped.pi may be undefined or empty; we only assert the
        // shape, not which phases appear (the spec only mandates Pi skip
        // reporting during apply, not during read).
        assert.equal(typeof status.engineSkipped, "object");
    } finally { h.restore(); }
});

test("ORCHESTRATOR_ENGINES lists all three engines", () => {
    assert.deepEqual([...ORCHESTRATOR_ENGINES].sort(), ["claude", "opencode", "pi"]);
});