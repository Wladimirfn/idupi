// ============================================================================
// idupi-server/lib/orchestrator/contract.mjs
//
// Canonical SDD contract every engine adapter implements. Single source of
// truth for phase names, phase aliasing, and engine resolution. Per-engine
// adapters (./engines/{pi,opencode,claude}.mjs) translate canonical names to
// engine-native names at their own boundary; this module holds the alias
// table so neither adapter invents its own normalization.
//
// Why OpenCode's phase set is canonical:
//   OpenCode is the only engine with the full SDD set, so we adopt it as the
//   superset. Pi only diverges by spelling (`sdd-proposal` vs `sdd-propose`)
//   plus two extras (`sdd-status`, `sdd-sync`) that both *report / refresh*
//   state and therefore map to `sdd-verify` deterministically. Claude uses the
//   canonical names directly. Keeping the superset canonical means OpenCode
//   adapters are identity, and Pi/Claude adapters translate at the boundary.
//
// Aliasing direction in storage:
//   Canonical keys are used in status payloads and OrchestratorStatus on the
//   wire. Engine-native names appear only on disk inside each engine's native
//   config (subagents.json, opencode.json, state.json). The adapter does the
//   name flip at write time; this module holds the alias table.
// ============================================================================

/**
 * Engine-agnostic model binding. Each adapter consumes only the fields its
 * engine needs:
 *   - opencode → providerId / modelId / effort
 *   - claude   → modelId only (claude CLI takes a model shorthand)
 *   - pi       → providerId / modelId (effort optional, ignored if unset)
 *
 * @typedef {Object} ModelBinding
 * @property {string} [providerId]  e.g. "opencode-go", "openai", "anthropic"
 * @property {string} [modelId]     e.g. "gpt-5.6-luna", "sonnet", "haiku"
 * @property {string} [effort]      "low" | "medium" | "high" | "max"
 * @property {string} [model]       shorthand "<provider>/<id>" or "sonnet"
 */

/** Canonical SDD phase set — OpenCode is the superset. */
export const CANONICAL_PHASES = Object.freeze([
    "gentle-orchestrator",
    "sdd-init", "sdd-onboard", "sdd-explore",
    "sdd-propose", "sdd-spec", "sdd-design",
    "sdd-tasks", "sdd-apply", "sdd-verify", "sdd-archive",
    "review-risk", "review-resilience",
    "review-readability", "review-reliability",
]);

/**
 * Bidirectional alias table. Direction is held by the contract module so the
 * Pi adapter never invents its own normalization.
 *
 *   canonicalize(name)              → canonical
 *   decanonicalize(canonical, eng)  → engine-native (per-engine adapter owns)
 *
 *   - sdd-proposal ⇄ sdd-propose
 *   - sdd-status, sdd-sync → sdd-verify (deterministic sink; both phases
 *     report/refresh state, which semantically belongs with the verify/reporting
 *     phase. The spec requires a deterministic canonical phase — not an error,
 *     not passthrough.)
 *
 * @type {Readonly<Record<string, string>>}
 */
export const PHASE_ALIASES = Object.freeze({
    "sdd-proposal": "sdd-propose",
    "sdd-status":   "sdd-verify",
    "sdd-sync":     "sdd-verify",
});

/** Result of resolveEngine for an unknown id — caller MUST reject. */
export class UnknownEngineError extends Error {
    constructor(id) {
        super(`Unknown engine id: ${JSON.stringify(id)}. Known: pi, opencode, claude.`);
        this.name = "UnknownEngineError";
        this.code = "EUNKNOWN_ENGINE";
    }
}

/** Canonical phase name → engine-native name lookup, seeded with the Pi direction. */
const PI_PHASE_DENORMALIZE = Object.freeze({
    "sdd-propose":  "sdd-proposal",
    "sdd-apply":    "sdd-apply",
    "sdd-verify":   "sdd-verify",
    "sdd-explore":  "sdd-explore",
    "sdd-spec":     "sdd-spec",
    "sdd-design":   "sdd-design",
    "sdd-tasks":    "sdd-tasks",
    "sdd-archive":  "sdd-archive",
    "sdd-init":     "sdd-init",
    "sdd-onboard":  "sdd-onboard",
    "gentle-orchestrator": "gentle-orchestrator",
});

/** Phases with no Pi equivalent — never written to subagents.json. */
const PI_SKIPPED_PHASES = Object.freeze([
    "review-risk", "review-resilience",
    "review-readability", "review-reliability",
]);

/**
 * Resolve engine id to its adapter module.
 *
 * Accepted ids (case-insensitive, trimmed): pi | pi-cli (alias) | opencode | claude.
 * Unknown ids throw UnknownEngineError — never fall through to a default engine.
 *
 * @param {string|undefined|null} engineId
 * @returns {Promise<{ engineId: string, ... }>}
 */
export async function resolveEngine(engineId) {
    const id = String(engineId ?? "").trim().toLowerCase();
    if (id === "pi" || id === "pi-cli") {
        const mod = await import("./engines/pi.mjs");
        return mod;
    }
    if (id === "opencode") {
        const mod = await import("./engines/opencode.mjs");
        return mod;
    }
    if (id === "claude") {
        const mod = await import("./engines/claude.mjs");
        return mod;
    }
    throw new UnknownEngineError(engineId);
}

/**
 * Canonicalize a phase name (Pi-native or canonical → canonical).
 * Identity on already-canonical names. Always returns a string.
 *
 * @param {string} name
 * @returns {string}
 */
export function canonicalize(name) {
    return PHASE_ALIASES[name] ?? name;
}

/**
 * Inverse of canonicalize for a specific engine. Returns undefined when the
 * canonical phase has no native equivalent for that engine (the adapter MUST
 * surface this as a skipped phase, never write a guessed name).
 *
 * @param {string} canonicalPhase
 * @param {"pi"|"opencode"|"claude"} engineId
 * @returns {string|undefined}
 */
export function decanonicalize(canonicalPhase, engineId) {
    if (engineId === "pi") {
        if (PI_SKIPPED_PHASES.includes(canonicalPhase)) return undefined;
        return PI_PHASE_DENORMALIZE[canonicalPhase] ?? canonicalPhase;
    }
    // OpenCode and Claude share the canonical namespace directly.
    if (engineId === "opencode" || engineId === "claude") {
        return canonicalPhase;
    }
    throw new UnknownEngineError(engineId);
}

/** Phases with no Pi equivalent — exposed so the adapter and route layer
 *  can both share the source of truth. */
export const PI_NO_EQUIVALENT_PHASES = PI_SKIPPED_PHASES;