// ============================================================================
// idupi-server/lib/orchestrator/routes.mjs
//
// Thin HTTP-route helpers for the four orchestrator endpoints that need to
// touch engine config files (opencode.json, subagents.json, state.json).
// Index.mjs only owns routing + request parsing; this module owns the
// per-engine delegation policy declared in design.md:
//
//   - engines resolve through resolveEngine(id); unknown ids throw and the
//     caller surfaces 400 (never a silent OpenCode fallthrough)
//   - per-engine failure isolation: one engine's write failing does NOT block
//     the others; per-engine {ok, errors[], skipped[]} reported in the response
//   - gentle-ai sync is best-effort and stays at the route layer, exactly as
//     before (never moved into the adapters)
//   - read-merge-write + .bak policy belongs to each adapter, not here
//
// Endpoints covered:
//   - status:           /api/v1/orchestrator/status          (GET)
//   - profiles/apply:   /api/v1/orchestrator/profiles/apply  (POST)
//   - profiles/save:    /api/v1/orchestrator/profiles/save   (POST)
//   - models/update:    /api/v1/orchestrator/models/update   (POST)
//
// Endpoints NOT covered (kept inline in index.mjs):
//   - providers/{id}/models: pure opencode CLI exec, no engine config
//   - profiles/delete:       single-file delete, no engine config
// ============================================================================

import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

import { resolveEngine, UnknownEngineError } from "./contract.mjs";

/** Best-effort buffer cap, kept consistent with index.mjs EXEC_MAX_BUFFER. */
const EXEC_MAX_BUFFER = 64 * 1024 * 1024;

/** Engines the orchestrator knows about. Order = status merge order. */
export const ORCHESTRATOR_ENGINES = Object.freeze(["opencode", "claude", "pi"]);

/** Adapter aliases the orchestrator engine field accepts. */
const ENGINE_ALIASES = Object.freeze({
    pi: "pi",
    "pi-cli": "pi",
    opencode: "opencode",
    claude: "claude",
});

/**
 * Normalize the engine id from a request payload. Returns the canonical
 * orchestrator value or `null` when the id is unknown (caller MUST surface
 * 400 and MUST NOT fall through to a default engine).
 *
 * @param {unknown} raw
 * @returns {"opencode"|"claude"|"pi"|null}
 */
export function normalizeEngineId(raw) {
    const id = String(raw ?? "").trim().toLowerCase();
    if (id === "pi" || id === "pi-cli") return "pi";
    if (id === "opencode") return "opencode";
    if (id === "claude") return "claude";
    return null;
}

/**
 * Read all three engine adapters defensively and merge their canonical
 * bindings into the existing OrchestratorStatus envelope. ONE failing engine
 * MUST NOT blank the others — per design decision "per-engine with isolation".
 *
 * The returned shape extends the existing payload with:
 *   - modelAssignments       (OpenCode inline + state.json model_assignments,
 *                             same shape the Android client already consumes)
 *   - claudePhaseAssignments (state.json claude_phase_assignments)
 *   - piPhaseAssignments     (subagents.json model_profiles, canonicalized)
 *   - engineSkipped          { [engine]: string[] } — phases reported as
 *                             skipped because the engine has no equivalent
 *
 * @returns {Promise<{
 *   modelAssignments: Record<string, { provider_id: string, model_id: string, effort: string|null }>,
 *   claudePhaseAssignments: Record<string, { model: string }>,
 *   piPhaseAssignments: Record<string, { provider_id?: string, model_id?: string, effort?: string }>,
 *   engineSkipped: Record<string, string[]>,
 *   gentleAiDetected: boolean,
 * }>}
 */
export async function readOrchestratorStatus() {
    const out = {
        modelAssignments: {},
        claudePhaseAssignments: {},
        piPhaseAssignments: {},
        engineSkipped: {},
        gentleAiDetected: false,
    };

    const statePath = join(homedir(), ".gentle-ai", "state.json");
    let stateData = {};
    if (existsSync(statePath)) {
        try {
            stateData = JSON.parse(readFileSync(statePath, "utf8"));
            out.gentleAiDetected = true;
        } catch {
            stateData = {};
        }
    }
    if (stateData && stateData.claude_phase_assignments && typeof stateData.claude_phase_assignments === "object") {
        out.claudePhaseAssignments = stateData.claude_phase_assignments;
    }

    for (const engineId of ORCHESTRATOR_ENGINES) {
        try {
            const adapter = await resolveEngine(engineId);
            const result = await adapter.readEngine();
            if (engineId === "opencode") {
                // Merge OpenCode inline agents on top of state.json model_assignments,
                // preserving the existing wire shape Android already consumes.
                const fromState = (stateData && stateData.model_assignments && typeof stateData.model_assignments === "object")
                    ? stateData.model_assignments
                    : {};
                const merged = { ...fromState };
                for (const [phase, b] of Object.entries(result.bindings)) {
                    if (!b) continue;
                    const existing = merged[phase] || {};
                    merged[phase] = {
                        provider_id: b.providerId ?? existing.provider_id ?? "opencode-go",
                        model_id: b.modelId ?? existing.model_id ?? "",
                        effort: b.effort ?? existing.effort ?? null,
                    };
                }
                out.modelAssignments = merged;
            } else if (engineId === "claude") {
                // claudePhaseAssignments already pulled from state.json above;
                // surface adapter-reported skipped[] too.
                if (result.skipped && result.skipped.length > 0) {
                    out.engineSkipped.claude = result.skipped;
                }
            } else if (engineId === "pi") {
                const piMap = {};
                for (const [phase, b] of Object.entries(result.bindings)) {
                    if (!b) continue;
                    piMap[phase] = {
                        provider_id: b.providerId ?? "opencode-go",
                        model_id: b.modelId ?? "",
                        effort: b.effort ?? undefined,
                    };
                }
                out.piPhaseAssignments = piMap;
                if (result.skipped && result.skipped.length > 0) {
                    out.engineSkipped.pi = result.skipped;
                }
            }
        } catch (err) {
            // One engine MUST NOT blank the others. Capture the failure as
            // a synthetic skip list and continue.
            const reason = err instanceof Error ? err.message : String(err);
            out.engineSkipped[engineId] = [`read-failed: ${reason}`];
        }
    }

    return out;
}

/**
 * Apply one engine's slice of a preset to its adapter. Failures are captured
 * per engine; per design decision "Profile apply granularity = per-engine with
 * isolation".
 *
 * @param {"opencode"|"claude"|"pi"} engineId
 * @param {Record<string, { provider_id?: string, modelId?: string, model_id?: string, effort?: string, model?: string }>} assignments
 * @returns {Promise<{ ok: boolean, errors: string[], skipped: string[] }>}
 */
export async function applyEngineAssignments(engineId, assignments) {
    const result = { ok: true, errors: [], skipped: [] };
    if (!assignments || typeof assignments !== "object") return result;
    let adapter;
    try {
        adapter = await resolveEngine(engineId);
    } catch (err) {
        result.ok = false;
        result.errors.push(err instanceof Error ? err.message : String(err));
        return result;
    }
    for (const [phase, raw] of Object.entries(assignments)) {
        if (!raw || typeof raw !== "object") continue;
        // Accept both snake_case (preset shape) and camelCase (wire shape).
        const binding = {
            providerId: raw.provider_id ?? raw.providerId,
            modelId: raw.model_id ?? raw.modelId ?? raw.model,
            effort: raw.effort,
        };
        try {
            const w = await adapter.writeEngine(phase, binding);
            if (w && Array.isArray(w.skipped) && w.skipped.length > 0) {
                result.skipped.push(...w.skipped);
            }
            if (w && w.ok === false) {
                result.ok = false;
                result.errors.push(`[${engineId}/${phase}] ${w.error ?? "write failed"}`);
            }
        } catch (err) {
            result.ok = false;
            result.errors.push(`[${engineId}/${phase}] ${err instanceof Error ? err.message : String(err)}`);
        }
    }
    return result;
}

/**
 * Apply a full profile preset to all three engines. Returns the per-engine
 * result envelope and never throws — callers can serialize it directly.
 *
 * @param {{
 *   modelAssignments?: Record<string, object>,
 *   claudeAssignments?: Record<string, object>,
 *   piAssignments?: Record<string, object>,
 * }} preset
 * @returns {Promise<{
 *   status: "ok" | "partial" | "error",
 *   engines: {
 *     opencode: { ok: boolean, errors: string[], skipped: string[] },
 *     claude:   { ok: boolean, errors: string[], skipped: string[] },
 *     pi:       { ok: boolean, errors: string[], skipped: string[] },
 *   },
 * }>}
 */
export async function applyProfilePreset(preset) {
    const enginesOut = {
        opencode: await applyEngineAssignments("opencode", preset.modelAssignments),
        claude:   await applyEngineAssignments("claude",   preset.claudeAssignments),
        pi:       await applyEngineAssignments("pi",       preset.piAssignments),
    };
    const allOk = enginesOut.opencode.ok && enginesOut.claude.ok && enginesOut.pi.ok;
    const anyOk = enginesOut.opencode.ok || enginesOut.claude.ok || enginesOut.pi.ok;
    const status = allOk ? "ok" : (anyOk ? "partial" : "error");
    return { status, engines: enginesOut };
}

/**
 * Full route handler for GET /api/v1/orchestrator/status.
 *
 * Owns: gentle-ai sdd-status exec + parse, per-engine adapter reads with
 * isolation, and the response payload assembly. Returns the HTTP-ready body.
 *
 * @param {string} activeProjectPath — cwd used by `gentle-ai sdd-status`
 * @param {() => string[]} [getProviders] — injected from index.mjs (calls
 *   getDetectedProviders() which lives in the monolith)
 * @param {() => object[]} [getCustomProfiles] — injected from index.mjs
 *   (calls getCustomSddProfiles() which lives in the monolith)
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function handleStatusRoute(activeProjectPath, getProviders, getCustomProfiles) {
    const sddInfo = {
        changeName: null,
        applyState: "idle",
        nextRecommended: "sdd-new",
        taskProgress: { total: 0, completed: 0, pending: 0, allComplete: false },
        blockedReasons: [],
    };
    try {
        const sddRaw = execSync("gentle-ai sdd-status", {
            cwd: activeProjectPath,
            encoding: "utf8",
            timeout: 4000,
            maxBuffer: EXEC_MAX_BUFFER,
        });
        const jsonMatch = sddRaw.match(/```json\s*([\s\S]*?)\s*```/);
        if (jsonMatch && jsonMatch[1]) {
            const parsedSdd = JSON.parse(jsonMatch[1]);
            sddInfo.changeName = parsedSdd.changeName || null;
            sddInfo.applyState = parsedSdd.applyState || "idle";
            sddInfo.nextRecommended = parsedSdd.nextRecommended || "sdd-new";
            sddInfo.taskProgress = parsedSdd.taskProgress || sddInfo.taskProgress;
            sddInfo.blockedReasons = parsedSdd.blockedReasons || [];
        }
    } catch (sddErr) {
        // best-effort — sddInfo keeps its defaults
    }

    const orchStatus = await readOrchestratorStatus();
    const statePath = join(homedir(), ".gentle-ai", "state.json");
    let stateData = {};
    if (existsSync(statePath)) {
        try {
            stateData = JSON.parse(readFileSync(statePath, "utf8"));
        } catch (e) {}
    }

    return {
        status: 200,
        body: {
            persona: stateData.persona || "gentleman",
            preset: stateData.preset || "full-gentleman",
            installedAgents: stateData.installed_agents || ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"],
            components: stateData.components || ["engram", "sdd", "skills", "gga", "codegraph", "context7", "permissions"],
            rddMode: stateData.rdd_mode || "on",
            sddStatus: sddInfo,
            claudePhaseAssignments: orchStatus.claudePhaseAssignments,
            modelAssignments: orchStatus.modelAssignments,
            piPhaseAssignments: orchStatus.piPhaseAssignments,
            engineSkipped: orchStatus.engineSkipped,
            gentleAiDetected: orchStatus.gentleAiDetected,
            providers: getProviders ? getProviders() : [],
            sddProfiles: getCustomProfiles ? getCustomProfiles() : [],
            activeProfile: stateData.active_profile || null,
        },
    };
}

/**
 * Update a single phase/binding on one engine. Rejects unknown engine ids.
 *
 * @param {string} engineRaw
 * @param {string} phase
 * @param {{ providerId?: string, modelId?: string, effort?: string, model?: string }} binding
 * @returns {Promise<{ ok: boolean, engine: string, phase: string, error?: string, skipped?: string[] }>}
 */
export async function updateEngineModel(engineRaw, phase, binding) {
    const normalized = normalizeEngineId(engineRaw);
    if (!normalized) {
        return {
            ok: false,
            engine: String(engineRaw ?? ""),
            phase,
            error: `Unknown engine id: ${JSON.stringify(engineRaw)}. Known: pi, opencode, claude.`,
        };
    }
    try {
        const adapter = await resolveEngine(normalized);
        const w = await adapter.writeEngine(phase, binding);
        return {
            ok: w.ok !== false,
            engine: normalized,
            phase,
            skipped: w.skipped ?? [],
            error: w.error,
        };
    } catch (err) {
        // resolveEngine throws UnknownEngineError only for ids normalizeEngineId
        // already rejects; anything else here is an adapter/runtime failure.
        if (err instanceof UnknownEngineError) {
            return { ok: false, engine: normalized, phase, error: err.message };
        }
        return {
            ok: false,
            engine: normalized,
            phase,
            error: err instanceof Error ? err.message : String(err),
        };
    }
}

/**
 * Full route handler for POST /api/v1/orchestrator/profiles/apply.
 *
 * Owns:
 *   - parsing profileId and the per-engine assignment maps
 *   - looking up built-in presets or custom profiles on disk
 *   - mirroring the chosen preset into ~/.gentle-ai/state.json so the
 *     gentle-ai sync step stays authoritative
 *   - fanning out to all three engine adapters with isolation
 *   - best-effort gentle-ai sync
 *
 * Returns the HTTP-ready response body so index.mjs only writes the status
 * line and ends the request — keeps the monolith from growing.
 *
 * @param {{ profileId?: string }} body
 * @param {{ warn?: (msg: string) => void }} [log]
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function handleProfileApply(body, log) {
    const profileId = body && body.profileId;
    if (!profileId) {
        return { status: 400, body: { error: "profileId es obligatorio" } };
    }

    let targetModels = null;
    let targetClaude = null;
    let targetPi = null;

    const customPath = join(homedir(), ".config", "opencode", "profiles", `${profileId}.json`);
    if (existsSync(customPath)) {
        try {
            const customData = JSON.parse(readFileSync(customPath, "utf8"));
            targetModels = customData.modelAssignments || customData.model_assignments;
            targetClaude = customData.claudeAssignments || customData.claude_phase_assignments;
            targetPi = customData.piAssignments || customData.pi_phase_assignments;
        } catch (e) {
            if (log && log.warn) log.warn(`[Gentle-AI Profiles Warn] ${e.message}`);
        }
    }

    if (!targetModels) {
        // Built-in presets live in index.mjs (SDD_PROFILE_PRESETS). The caller
        // is expected to have already substituted that map before calling this
        // helper — see handleProfileApplyWithPresets below for the canonical
        // entry point.
        return { status: 404, body: { error: `Perfil '${profileId}' no encontrado` } };
    }

    return finalizeProfileApply(profileId, targetModels, targetClaude, targetPi, log);
}

/**
 * Canonical entry point for POST /api/v1/orchestrator/profiles/apply. Receives
 * the built-in preset map (owned by index.mjs) AND checks the custom profile
 * directory so callers don't have to know about the override order.
 *
 * @param {Record<string, { modelAssignments?: object, claudeAssignments?: object, piAssignments?: object }>} builtInPresets
 * @param {{ profileId?: string }} body
 * @param {{ warn?: (msg: string) => void }} [log]
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function handleProfileApplyWithPresets(builtInPresets, body, log) {
    const profileId = body && body.profileId;
    if (!profileId) {
        return { status: 400, body: { error: "profileId es obligatorio" } };
    }
    const customPath = join(homedir(), ".config", "opencode", "profiles", `${profileId}.json`);
    if (existsSync(customPath)) {
        try {
            const customData = JSON.parse(readFileSync(customPath, "utf8"));
            return finalizeProfileApply(
                profileId,
                customData.modelAssignments || customData.model_assignments,
                customData.claudeAssignments || customData.claude_phase_assignments,
                customData.piAssignments || customData.pi_phase_assignments,
                log,
            );
        } catch (e) {
            if (log && log.warn) log.warn(`[Gentle-AI Profiles Warn] ${e.message}`);
        }
    }
    const preset = builtInPresets && builtInPresets[profileId];
    if (preset) {
        return finalizeProfileApply(
            profileId,
            preset.modelAssignments,
            preset.claudeAssignments,
            preset.piAssignments,
            log,
        );
    }
    return { status: 404, body: { error: `Perfil '${profileId}' no encontrado` } };
}

/** Shared tail for both handleProfileApply paths. */
async function finalizeProfileApply(profileId, targetModels, targetClaude, targetPi, log) {
    if (!targetModels) {
        return { status: 404, body: { error: `Perfil '${profileId}' no encontrado` } };
    }
    // Mirror into ~/.gentle-ai/state.json so gentle-ai sync stays authoritative.
    const statePath = join(homedir(), ".gentle-ai", "state.json");
    let stateData = {};
    if (existsSync(statePath)) {
        try {
            stateData = JSON.parse(readFileSync(statePath, "utf8"));
        } catch (e) {}
    }
    stateData.installed_agents = ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"];
    stateData.model_assignments = targetModels;
    if (targetClaude) stateData.claude_phase_assignments = targetClaude;
    if (targetPi) stateData.pi_phase_assignments = targetPi;
    stateData.active_profile = profileId;
    writeFileSync(statePath, JSON.stringify(stateData, null, 2), "utf8");
    if (log && log.log) log.log(`[Gentle-AI Orchestrator] Perfil SDD '${profileId}' aplicado.`);

    // Per-engine adapter fan-out with isolation.
    const applyResult = await applyProfilePreset({
        modelAssignments: targetModels,
        claudeAssignments: targetClaude,
        piAssignments: targetPi,
    });

    // gentle-ai sync is best-effort, never throws.
    gentleAiSync(log);

    return {
        status: 200,
        body: {
            status: applyResult.status,
            profileId,
            activeProfile: profileId,
            engines: applyResult.engines,
        },
    };
}

/**
 * Full route handler for POST /api/v1/orchestrator/models/update.
 *
 * Owns: engine validation, per-adapter write, state.json mirroring, and the
 * gentle-ai sync step. Returns the HTTP-ready response body.
 *
 * @param {{ engine?: string, phase?: string, modelId?: string, providerId?: string, effort?: string }} body
 * @param {{ warn?: (msg: string) => void }} [log]
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function handleModelsUpdate(body, log) {
    const { engine, phase, modelId, providerId, effort } = body || {};
    if (!engine || !phase || !modelId) {
        return { status: 400, body: { error: "engine, phase y modelId son obligatorios" } };
    }
    const result = await updateEngineModel(engine, phase, { providerId, modelId, effort });
    if (!result.ok) {
        return {
            status: 400,
            body: { status: "error", error: result.error, engine: result.engine, phase: result.phase },
        };
    }
    // Mirror into state.json so gentle-ai sync stays authoritative.
    try {
        const statePath = join(homedir(), ".gentle-ai", "state.json");
        let stateData = {};
        if (existsSync(statePath)) {
            stateData = JSON.parse(readFileSync(statePath, "utf8"));
        }
        stateData.installed_agents = ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"];
        if (result.engine === "claude") {
            if (!stateData.claude_phase_assignments) stateData.claude_phase_assignments = {};
            stateData.claude_phase_assignments[phase] = { model: modelId };
        } else {
            if (!stateData.model_assignments) stateData.model_assignments = {};
            stateData.model_assignments[phase] = {
                provider_id: providerId || "opencode-go",
                model_id: modelId,
                ...(effort ? { effort } : {}),
            };
        }
        writeFileSync(statePath, JSON.stringify(stateData, null, 2), "utf8");
    } catch (stateErr) {
        if (log && log.warn) log.warn(`[Gentle-AI State Mirror Warn]: ${stateErr.message}`);
    }
    if (log && log.log) {
        log.log(`[Gentle-AI Orchestrator] Actualizado ${result.engine} [${phase}] -> ${modelId}`);
    }
    gentleAiSync(log);
    return {
        status: 200,
        body: {
            status: "ok",
            engine: result.engine,
            phase: result.phase,
            modelId,
            skipped: result.skipped ?? [],
        },
    };
}

/**
 * Best-effort `gentle-ai sync` wrapper. Mirrors the existing inline try/catch
 * at the route layer — failures never throw; they only log.
 *
 * @param {{ log?: (msg: string) => void, warn?: (msg: string) => void }} [log]
 */
export function gentleAiSync(log) {
    try {
        execSync("gentle-ai sync", {
            encoding: "utf8",
            timeout: 10000,
            maxBuffer: EXEC_MAX_BUFFER,
        });
        if (log && log.log) log.log("[Gentle-AI Sync] Sincronización automática completada.");
    } catch (err) {
        if (log && log.warn) log.warn(`[Gentle-AI Sync] Aviso en auto-sync: ${err.message}`);
    }
}

export const __testing = Object.freeze({
    ENGINE_ALIASES,
    normalizeEngineId,
});