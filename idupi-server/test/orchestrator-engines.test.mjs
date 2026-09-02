// ============================================================================
// idupi-server/test/orchestrator-engines.test.mjs
//
// Pin per-engine adapter behavior against synthetic config files. The adapters
// must:
//   - read-merge-write preserving non-target keys
//   - create a .bak on first write per process, and NOT on subsequent writes
//   - skip phases with no Pi equivalent, reporting them in skipped[] (not disk)
//   - never touch ~/.pi/gentle-ai/models.json (PiAdapter)
//
// These tests DO NOT mutate the real $HOME. They build a temp dir and let the
// adapters resolve paths relative to it via injected override hooks.
//
// Run (from repo root):
//   node --test idupi-server/test/orchestrator-engines.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";

import * as piEngine from "../lib/orchestrator/engines/pi.mjs";
import * as opencodeEngine from "../lib/orchestrator/engines/opencode.mjs";
import * as claudeEngine from "../lib/orchestrator/engines/claude.mjs";

// ---- Fixture helper ----------------------------------------------------------

/**
 * Set HOME (and USERPROFILE for Windows parity) to a fresh temp dir so the
 * adapters resolve their native config paths there. Returns the temp dir and a
 * restore hook.
 */
function fakeHome() {
    const dir = mkdtempSync(join(tmpdir(), "idupi-orchestrator-engines-"));
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

/** Per-test reset for the in-process backup Sets in each adapter. */
function clearBackupTracking() {
    piEngine.__testing.backedUp.clear();
    opencodeEngine.__testing.backedUp.clear();
    claudeEngine.__testing.backedUp.clear();
}

/** Write a file, creating parents as needed. */
function writeJson(filePath, obj) {
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(obj, null, 2), "utf8");
}

// ============================================================================
// Pi adapter
// ============================================================================

test("pi: read returns empty bindings + skipped[] when subagents.json is missing", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await piEngine.readEngine();
        assert.deepEqual(result.bindings, {});
        assert.deepEqual(result.skipped, []);
    } finally { h.restore(); }
});

test("pi: read denormalizes model_profiles['sdd-proposal'] into canonical sdd-propose", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(
            join(h.dir, ".pi", "subagents.json"),
            {
                model_profiles: {
                    "sdd-proposal": { provider_id: "openai", model_id: "gpt-5.6", effort: "high" },
                    "sdd-apply":    { provider_id: "anthropic", model_id: "sonnet" },
                },
                unrelated_key: { keep: "me" },
            },
        );
        const result = await piEngine.readEngine();
        assert.deepEqual(result.bindings["sdd-propose"], {
            providerId: "openai", modelId: "gpt-5.6", effort: "high",
        });
        assert.deepEqual(result.bindings["sdd-apply"], {
            providerId: "anthropic", modelId: "sonnet",
        });
        assert.deepEqual(result.skipped, []);
    } finally { h.restore(); }
});

test("pi: write canonical sdd-propose stores under native 'sdd-proposal'", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await piEngine.writeEngine("sdd-propose", {
            providerId: "openai", modelId: "gpt-5.6", effort: "high",
        });
        assert.equal(result.ok, true);
        const doc = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.deepEqual(doc.model_profiles["sdd-proposal"], {
            provider_id: "openai", model_id: "gpt-5.6", effort: "high",
        });
    } finally { h.restore(); }
});

test("pi: write preserves foreign keys (read-merge-write)", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const initial = {
            model_profiles: {},
            not_a_binding: { hello: "world" },
            array_key: [1, 2, 3],
            version: 7,
        };
        writeJson(join(h.dir, ".pi", "subagents.json"), initial);
        const result = await piEngine.writeEngine("sdd-apply", {
            providerId: "openai", modelId: "gpt-5.6",
        });
        assert.equal(result.ok, true);
        const doc = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.deepEqual(doc.not_a_binding, { hello: "world" });
        assert.deepEqual(doc.array_key, [1, 2, 3]);
        assert.equal(doc.version, 7);
        assert.deepEqual(doc.model_profiles["sdd-apply"], {
            provider_id: "openai", model_id: "gpt-5.6",
        });
    } finally { h.restore(); }
});

test("pi: first write creates a .bak, second write in same process does not", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        // Seed an existing file so .bak has something to copy from.
        writeJson(join(h.dir, ".pi", "subagents.json"),
            { model_profiles: { existing: { model_id: "x" } } });
        const filePath = join(h.dir, ".pi", "subagents.json");

        await piEngine.writeEngine("sdd-propose", { providerId: "p", modelId: "m" });
        assert.ok(existsSync(`${filePath}.bak`), "first write should create .bak");

        // Remove .bak to detect a redundant second backup attempt.
        rmSync(`${filePath}.bak`);
        await piEngine.writeEngine("sdd-apply", { providerId: "p", modelId: "m2" });
        assert.ok(!existsSync(`${filePath}.bak`), "second write in same process must NOT re-back-up");
    } finally { h.restore(); }
});

test("pi: review-risk and review-resilience populate skipped[] not disk", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result1 = await piEngine.writeEngine("review-risk", {
            providerId: "openai", modelId: "gpt-5.6",
        });
        const result2 = await piEngine.writeEngine("review-resilience", {
            providerId: "openai", modelId: "gpt-5.6",
        });
        assert.deepEqual(result1.skipped, ["review-risk"]);
        assert.equal(result1.ok, true);
        assert.deepEqual(result2.skipped, ["review-resilience"]);
        // No disk side effect from a skipped write.
        assert.ok(!existsSync(join(h.dir, ".pi", "subagents.json")),
            "skipped phases must NOT create the config file");
    } finally { h.restore(); }
});

test("pi: round-trip canonicalize/decanonicalize is stable for sdd-propose", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        await piEngine.writeEngine("sdd-propose", { providerId: "openai", modelId: "gpt-5.6" });
        const read = await piEngine.readEngine();
        assert.deepEqual(read.bindings["sdd-propose"], { providerId: "openai", modelId: "gpt-5.6" });
    } finally { h.restore(); }
});

test("pi: write failure is captured, not thrown", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        // Force a write failure by replacing subagents.json with a directory of
        // the same name -- the adapter's writeFileSync will then fail with EISDIR.
        const path = join(h.dir, ".pi", "subagents.json");
        writeJson(path, { model_profiles: {} });
        rmSync(path, { force: true });
        mkdirSync(path, { recursive: true });
        const result = await piEngine.writeEngine("sdd-propose", { modelId: "x" });
        assert.equal(result.ok, false);
        assert.ok(typeof result.error === "string");
    } finally { h.restore(); }
});

// ============================================================================
// OpenCode adapter
// ============================================================================

test("opencode: read returns empty bindings when opencode.json is missing", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await opencodeEngine.readEngine();
        assert.deepEqual(result.bindings, {});
    } finally { h.restore(); }
});

test("opencode: read parses 'provider/model' strings into providerId/modelId", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(
            join(h.dir, ".config", "opencode", "opencode.json"),
            {
                agent: {
                    "sdd-apply": { model: "opencode-go/gpt-5.6-luna", variant: "high" },
                    "sdd-verify": { model: "sonnet" },
                },
                theme: "dark",
            },
        );
        const result = await opencodeEngine.readEngine();
        assert.deepEqual(result.bindings["sdd-apply"], {
            providerId: "opencode-go", modelId: "gpt-5.6-luna", effort: "high",
        });
        assert.deepEqual(result.bindings["sdd-verify"], {
            providerId: "opencode-go", modelId: "sonnet",
        });
    } finally { h.restore(); }
});

test("opencode: write preserves non-agent keys and other agents", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const initial = {
            agent: {
                "existing-agent": { model: "opencode-go/gpt-5.6-luna" },
            },
            theme: "dark",
            plugins: ["a", "b"],
        };
        writeJson(join(h.dir, ".config", "opencode", "opencode.json"), initial);
        const result = await opencodeEngine.writeEngine("sdd-apply", {
            providerId: "opencode-go", modelId: "hy3", effort: "low",
        });
        assert.equal(result.ok, true);
        const doc = JSON.parse(readFileSync(
            join(h.dir, ".config", "opencode", "opencode.json"), "utf8"));
        assert.deepEqual(doc.agent["existing-agent"], { model: "opencode-go/gpt-5.6-luna" });
        assert.deepEqual(doc.agent["sdd-apply"], {
            model: "opencode-go/hy3", variant: "low",
        });
        assert.equal(doc.theme, "dark");
        assert.deepEqual(doc.plugins, ["a", "b"]);
    } finally { h.restore(); }
});

test("opencode: clearing effort on write removes the variant key", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(join(h.dir, ".config", "opencode", "opencode.json"),
            { agent: { "sdd-apply": { model: "opencode-go/x", variant: "high" } } });
        const result = await opencodeEngine.writeEngine("sdd-apply", {
            providerId: "opencode-go", modelId: "x",
        });
        assert.equal(result.ok, true);
        const doc = JSON.parse(readFileSync(
            join(h.dir, ".config", "opencode", "opencode.json"), "utf8"));
        assert.equal(doc.agent["sdd-apply"].variant, undefined);
        assert.equal(doc.agent["sdd-apply"].model, "opencode-go/x");
    } finally { h.restore(); }
});

// ============================================================================
// Claude adapter
// ============================================================================

test("claude: read returns empty bindings when state.json is missing", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const result = await claudeEngine.readEngine();
        assert.deepEqual(result.bindings, {});
    } finally { h.restore(); }
});

test("claude: read parses claude_phase_assignments model strings", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        writeJson(
            join(h.dir, ".gentle-ai", "state.json"),
            {
                claude_phase_assignments: {
                    "sdd-apply": { model: "sonnet" },
                    "sdd-archive": { model: "haiku" },
                },
                installed_agents: ["opencode", "claude-code", "pi"],
                model_assignments: { "sdd-apply": { provider_id: "opencode-go", model_id: "x" } },
            },
        );
        const result = await claudeEngine.readEngine();
        assert.deepEqual(result.bindings["sdd-apply"], { modelId: "sonnet" });
        assert.deepEqual(result.bindings["sdd-archive"], { modelId: "haiku" });
    } finally { h.restore(); }
});

test("claude: write preserves model_assignments and other top-level keys", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        const initial = {
            model_assignments: { "sdd-apply": { provider_id: "opencode-go", model_id: "gpt-5.6-luna" } },
            persona: "gentleman",
            preset: "full-gentleman",
        };
        writeJson(join(h.dir, ".gentle-ai", "state.json"), initial);
        const result = await claudeEngine.writeEngine("sdd-apply", { modelId: "haiku" });
        assert.equal(result.ok, true);
        const doc = JSON.parse(readFileSync(
            join(h.dir, ".gentle-ai", "state.json"), "utf8"));
        assert.deepEqual(doc.claude_phase_assignments, {
            "sdd-apply": { model: "haiku" },
        });
        assert.deepEqual(doc.model_assignments, {
            "sdd-apply": { provider_id: "opencode-go", model_id: "gpt-5.6-luna" },
        });
        assert.equal(doc.persona, "gentleman");
        assert.equal(doc.preset, "full-gentleman");
    } finally { h.restore(); }
});

// ============================================================================
// Cross-engine isolation
// ============================================================================

test("per-engine: one engine's write failure does not affect another engine's adapter", async () => {
    const h = fakeHome();
    try {
        clearBackupTracking();
        // Force a Claude write failure by replacing state.json with a directory.
        const stateFile = join(h.dir, ".gentle-ai", "state.json");
        writeJson(stateFile, { claude_phase_assignments: {} });
        rmSync(stateFile);
        mkdirSync(stateFile, { recursive: true });

        const claudeResult = await claudeEngine.writeEngine("sdd-apply", { modelId: "haiku" });
        assert.equal(claudeResult.ok, false);

        // Pi write should still succeed — adapters are isolated.
        const piResult = await piEngine.writeEngine("sdd-propose", {
            providerId: "openai", modelId: "gpt-5.6",
        });
        assert.equal(piResult.ok, true);
        const piDoc = JSON.parse(readFileSync(join(h.dir, ".pi", "subagents.json"), "utf8"));
        assert.deepEqual(piDoc.model_profiles["sdd-proposal"], {
            provider_id: "openai", model_id: "gpt-5.6",
        });
    } finally { h.restore(); }
});