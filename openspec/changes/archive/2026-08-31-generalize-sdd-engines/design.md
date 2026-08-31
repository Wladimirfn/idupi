# Design: Generalize SDD Engines — Pi as First-Class Engine

## Technical Approach

Extract the engine/SDD surface from `idupi-server/index.mjs` (currently 4056 lines) into `idupi-server/lib/orchestrator/` modules: one canonical contract plus three thin per-engine adapters. Every adapter reads and writes only the engine's native config (`opencode.json` / `state.json` / `subagents.json`), translates canonical phase names at its own boundary, performs read-merge-write with `.bak` backup on first write, and reports per-phase failures. `index.mjs` keeps HTTP routing and request parsing; routes delegate via `resolveEngine(engineId)`. The Android client gains a `PiPhaseConfig` model and an engine selector UI while reusing the existing `OrchestratorStatus` envelope plus a third `piPhaseAssignments` map.

This directly satisfies `orchestrator-engine-contract` (canonical namespace, `PHASE_ALIASES`, `ModelBinding`, `resolveEngine`) and `orchestrator-engine-adapters` (Pi native target, safe apply, unknown-key preservation, base mode without gentle-ai, parity UI).

## Architecture Decisions

| Decision | Choice | Alternatives | Rationale |
|---|---|---|---|
| Canonical phase namespace | OpenCode's full SDD phase set (superset), e.g. `sdd-propose`, `sdd-spec`, `sdd-design`, `sdd-tasks`, `sdd-apply`, `sdd-verify`, `sdd-archive`, `sdd-explore`, `sdd-init`, `sdd-onboard`, `gentle-orchestrator`, plus `review-*` | Pi's namespace as canonical | OpenCode is the only engine with the complete set; Pi only diverges by spelling (`-proposal`) and two extras (`sdd-status`, `sdd-sync`). Keeping the superset means OpenCode adapters are identity, and Pi/Claude adapters translate at the boundary per the spec. |
| Canonical sinks for Pi-only `sdd-status` / `sdd-sync` | Both map deterministically to `sdd-verify` (the verify/reporting phase in OpenCode's set) | Map `sdd-sync` to `sdd-apply`; refuse unknown phases | `sdd-status` and `sdd-sync` both *report / refresh* state — semantically they belong with `sdd-verify`. The spec requires "deterministic canonical phase ... not an error, not passthrough". A refused phase would break Pi apply for any user already using those names. |
| Adapter interface shape | Per-engine module exports `{ engineId, readEngine, writeEngine, nativeTargets, aliasMap }`. `resolveEngine(id)` returns the module. | Class-based `EngineAdapter` hierarchy | Matches the existing `lib/` convention (plain ESM exports, no classes except where stateful). `index.mjs` is also plain ESM with imports — adopting the module-per-engine shape is idiomatic for this repo. |
| Profile apply granularity | Per-engine choice: each engine receives its portion of the preset through its own adapter. Failures isolated per engine and reported in the response | Atomic all-or-nothing | The spec requires "failure MUST be isolated per engine and reported, never silent". Atomic semantics would force Claude/Pi write failures to roll back OpenCode — adding undo logic that does not exist today and is not asked for. Per-engine with isolation is simpler, matches `subagent-notify.mjs`'s per-child reporting pattern. |
| Missing-Pi-phase reporting | Silently skipped on disk, **reported** in the apply response as `skipped: ["review-risk", "review-resilience", ...]`; Android surfaces as a non-blocking banner | Hard error; silent on disk only | The spec requires the skipped set "MUST be reported in the apply or status result". Reporting in the response honors that without forcing the failure path. Android can render a `gentle-ai: Pi sin equivalente` chip; status endpoint also returns the skipped list for parity. |
| `pi` vs `pi-cli` aliasing | `resolveEngine` accepts both `pi` and `pi-cli` (lowercased, trimmed) and normalizes to `pi` | Hard-require `pi` only | `app/.../EngineLabel.kt` already maps both (`"pi-cli" -> "Pi"`); `sessions.mjs:783` already routes both. Rejecting `pi-cli` would silently break the existing chat path. Documented alias per the contract spec. |
| Write target for Pi | `~/.pi/subagents.json` → `model_profiles.*` | `~/.pi/gentle-ai/models.json` | Per the proposal + spec ("explicitly NOT gentle-ai's `~/.pi/gentle-ai/models.json`"). `subagents.json` is Pi's native binding surface post the May 2026 migration. |
| Backup policy | `.bak` copy created on first write per (file, process lifetime). Subsequent writes in the same process do not re-back-up | Backup on every write | One backup per process is enough for human-driven `models/update` and profile apply; per-write backups would clutter the user's home. Per-process uniqueness tracked via a small `Set` in each adapter. |
| Pi aliasing direction in storage | Canonical keys stored in the response payload; Pi keys used only on disk in `subagents.json`. The contract module holds the direction. | Always store Pi keys internally | Canonical storage keeps `OrchestratorStatus.engineAssignments` engine-agnostic (single shape). The adapter does the name flip at write time; the contract module holds the alias table. |
| Android engine representation | Add `PiPhaseConfig(providerId, modelId, effort?)` parallel to existing `OpenCodeModelAssignment`; `OrchestratorStatus` gains `piPhaseAssignments: Map<String, PiPhaseConfig>` and `gentleAiDetected: Boolean` | Generic `EngineModelAssignment` sealed type | A sealed generic would be cleaner in isolation, but it forces every call site and serializer to discriminate; introducing a third concrete class (mirroring the existing two) keeps the diff small, the `@Serializable` shape stable, and existing tests (`EngineLabelTest`) untouched. Generic refactor is a future change. |
| UI engine selector | Top: row of three engine chips (`Pi | OpenCode | Claude`), state in `OrchestratorViewModel`. Function tabs beneath: `Fases | Modelos | Perfiles | Herramientas` | Hide tabs until engine selected; per-engine tab swap | Owner directive: selector on top, tabs below. Current tabs are already engine-scoped at the function level (`Modelos OpenCode` / `Modelos Claude`); the new design keeps the same tab labels but makes the engine chip drive which data populates them, and adds a `Pi` tab at parity. |

## Data Flow

    Android OrchestratorScreen
        │
        │ GET /api/v1/orchestrator/status
        ▼
    index.mjs  /api/v1/orchestrator/status   (HTTP routing only)
        │
        ├─► resolveEngine('opencode')  → engines/opencode.mjs.readEngine()
        │     → reads ~/.config/opencode/opencode.json
        ├─► resolveEngine('claude')    → engines/claude.mjs.readEngine()
        │     → reads ~/.gentle-ai/state.json :: claude_phase_assignments
        └─► resolveEngine('pi')        → engines/pi.mjs.readEngine()
              → reads ~/.pi/subagents.json :: model_profiles
                  (translates sdd-proposal ↔ sdd-propose etc.)
        │
        ▼
    OrchestratorStatus envelope (engine-agnostic shape)

    Android Pi/OpenCode/Claude chip selection
        │
        │ POST /api/v1/orchestrator/models/update { engine, phase, modelId, ... }
        ▼
    index.mjs  /api/v1/orchestrator/models/update
        │
        ├─► resolveEngine(engine)  → engines/{...}.mjs.writeEngine(phase, ModelBinding)
        │     ├─ read-merge-write preserving foreign keys
        │     ├─ .bak on first write per process
        │     └─ on write failure: capture, do NOT throw — return { ok:false, error }
        └─► if any engine wrote: optional gentle-ai sync (best-effort, try/catch)

    Apply profile (POST /profiles/apply):
        preset { modelAssignments, claudeAssignments, piAssignments }
            │
            ▼
        for each engine in preset:
            resolveEngine(engine).writeEngine(mapForEngine(preset))
            collect { ok, errors[], skipped[] }
        │
        ▼
        response { status, engines: { opencode:{ok,errors}, claude:..., pi:{ok,errors,skipped} } }

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `idupi-server/lib/orchestrator/contract.mjs` | Create | Canonical phases, `PHASE_ALIASES`, `ModelBinding`, `resolveEngine(id)`, `normalizePhase(name)`, `denormalizePhase(name)`, `UNKNOWN_ENGINE` error factory. |
| `idupi-server/lib/orchestrator/engines/opencode.mjs` | Create | `readEngine()` reads `~/.config/opencode/opencode.json` (identity phase names). `writeEngine()` does read-merge-write on `op.agent[phase]`, preserves all non-agent keys, `.bak` on first write. |
| `idupi-server/lib/orchestrator/engines/claude.mjs` | Create | `readEngine()` reads `~/.gentle-ai/state.json :: claude_phase_assignments`. `writeEngine()` does read-merge-write on the same path. Keeps the existing `gentle-ai sync` try/catch wiring at the route layer (not inside the adapter). |
| `idupi-server/lib/orchestrator/engines/pi.mjs` | Create | `readEngine()` reads `~/.pi/subagents.json :: model_profiles` and denormalizes `sdd-proposal` → `sdd-propose`. `writeEngine()` does read-merge-write on `model_profiles`, using the inverse direction. Tracks `skipped[]` for canonical phases with no Pi equivalent (`review-risk`, etc.) and reports them in the result. |
| `idupi-server/index.mjs` | Modify (net shrink) | Remove inline engine routing; `import { resolveEngine } from "./lib/orchestrator/contract.mjs"`. `/api/v1/orchestrator/status` calls `readEngine()` for each engine and merges into `OrchestratorStatus`. `/api/v1/orchestrator/models/update` parses, calls `resolveEngine(engine).writeEngine(phase, binding)`, isolates failures. `/api/v1/orchestrator/profiles/apply` splits the preset per engine and delegates. `SDD_PROFILE_PRESETS` gains `piAssignments`. |
| `app/.../domain/model/Orchestrator.kt` | Modify | Add `PiPhaseConfig(providerId, modelId, effort?)`; add `piPhaseAssignments: Map<String, PiPhaseConfig>` and `gentleAiDetected: Boolean = false` to `OrchestratorStatus`; extend `SddProfileItem` with `piAssignments`. |
| `app/.../data/remote/RealIduPiClient.kt` | Modify (no API change) | Parser already tolerant: kotlinx.serialization auto-fills missing fields with defaults. Existing `engine` param already accepted. No change to `updateOrchestratorModel` signature. |
| `app/.../viewmodel/OrchestratorViewModel.kt` | Modify | Add `activeEngine` `StateFlow<String>` defaulting to `"opencode"`; add helper `piAssignmentsFor(status)`; expose `gentleAiDetected`; new tab `PI_MODELS` in `OrchestratorTab`. |
| `app/.../ui/screens/OrchestratorScreen.kt` | Modify | Top engine selector row (`Pi \| OpenCode \| Claude`); Pi tab parity; gentle-ai detection banner (`gentle-ai: detectado (modo 2)` vs `gentle-ai: no instalado (modo base)`); preserve existing `Fases \| Modelos \| Perfiles \| Herramientas` function tabs. |
| `idupi-server/test/orchestrator-contract.test.mjs` | Create | Alias round-trip, unknown-engine rejection, `pi-cli` → `pi` alias. |
| `idupi-server/test/orchestrator-engines.test.mjs` | Create | Read-merge-write preserves foreign keys; `.bak` created once; Pi skips unknown phases; per-engine failure isolation. Uses `test/fixtures/` style (`mkdtempSync` temp dirs, no real `$HOME` mutation). |
| `app/src/test/.../OrchestratorViewModelTest.kt` | Extend | Pi flow test: status includes `piPhaseAssignments`; `updateModel(engine="pi", ...)` parses back to `subagents.json` contract (server-side test owns the round-trip). |
| `app/src/test/.../EngineLabelTest.kt` | Extend (or new `PiPhaseConfigTest`) | Verify `PiPhaseConfig` serializer round-trips and `gentleAiDetected` banner string is stable. |

## Interfaces / Contracts

```js
// idupi-server/lib/orchestrator/contract.mjs  (JSDoc, ESM)

/**
 * Engine-agnostic model binding. Each adapter consumes only the fields its
 * engine needs: opencode → providerId/modelId/effort; claude → modelId only;
 * pi → providerId/modelId (effort optional, ignored if unset on write).
 * @typedef {Object} ModelBinding
 * @property {string} [providerId]   // "opencode-go", "openai", ...
 * @property {string} [modelId]      // "gpt-5.6-luna", "sonnet", "haiku"
 * @property {string} [effort]       // "low" | "medium" | "high" | "max"
 * @property {string} [model]        // shorthand "<provider>/<id>" or "sonnet"
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
 * Bidirectional alias table.
 *   canonicalize(name)   → canonical
 *   decanonicalize(canon)→ engine-native (per-engine adapter holds the inverse)
 *   - sdd-proposal ⇄ sdd-propose
 *   - sdd-status, sdd-sync → sdd-verify (deterministic sink, see Decision row)
 * @type {Record<string,string>}
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
    this.code = "EUNKNOWN_ENGINE";
  }
}

/** Accepted ids: pi | pi-cli (alias), opencode, claude. Case-insensitive, trimmed. */
export function resolveEngine(engineId) {
  const id = String(engineId ?? "").trim().toLowerCase();
  if (id === "pi" || id === "pi-cli") return import("./engines/pi.mjs");
  if (id === "opencode")              return import("./engines/opencode.mjs");
  if (id === "claude")                return import("./engines/claude.mjs");
  throw new UnknownEngineError(id);
}

export function canonicalize(name) { return PHASE_ALIASES[name] ?? name; }
export function decanonicalize(canon, engineId) { /* delegated per-engine */ }
```

```js
// idupi-server/lib/orchestrator/engines/pi.mjs
export const engineId = "pi";
export const nativeTargets = ["~/.pi/subagents.json"]; // documented for backup UI

/** @returns {Promise<{ bindings: Map<string,ModelBinding>, skipped: string[] }>} */
export async function readEngine() { /* read subagents.json, denormalize names */ }

/** @returns {Promise<{ ok: boolean, error?: string, skipped: string[] }>} */
export async function writeEngine(phase, binding) { /* read-merge-write, .bak on first */ }
```

```kotlin
// app/.../domain/model/Orchestrator.kt  (additions)
@Serializable
data class PiPhaseConfig(
    val provider_id: String = "opencode-go",
    val model_id: String = "",
    val effort: String? = null,
)

@Serializable
data class OrchestratorStatus(
    // ... existing fields ...
    val piPhaseAssignments: Map<String, PiPhaseConfig> = emptyMap(),
    val gentleAiDetected: Boolean = false,
)
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit (Node) | `canonicalize`/`resolveEngine` round-trip; `pi-cli` alias; `UnknownEngineError` thrown on `copilot` / `""` / null; PHASE_ALIASES idempotent on canonical names. | `idupi-server/test/orchestrator-contract.test.mjs`, mirroring `agent-cmdline.test.mjs` style (`node:test` + `node:assert/strict`). |
| Unit (Node) | Pi adapter: denormalizes `model_profiles["sdd-proposal"]` → canonical `sdd-propose`; merges foreign keys unchanged; first write creates `.bak`; second write in same process does NOT re-back-up; phases with no Pi equivalent (e.g. `review-risk`) populate `skipped[]` not disk. OpenCode/Claude adapters: read-merge-write preserves non-target keys; `state.json` foreign keys intact. | `idupi-server/test/orchestrator-engines.test.mjs` with `mkdtempSync` temp dirs (no real `$HOME` mutation); `node --check` per file in CI gate. |
| Unit (Node) | Profile apply response shape: per-engine `ok/errors/skipped`; one engine's write failure does NOT block the others. | Test exercises `resolveEngine(id).writeEngine` with a fake adapter that throws. |
| Unit (Android) | `PiPhaseConfig` serializer round-trip; `OrchestratorStatus.gentleAiDetected` default `false`; `updateOrchestratorModel(engine="pi", ...)` keeps existing API. | Extend `OrchestratorViewModelTest` + new `PiPhaseConfigTest`. |
| Syntax gate | `node --check idupi-server/lib/orchestrator/contract.mjs idupi-server/lib/orchestrator/engines/{opencode,claude,pi}.mjs` | Same pattern as `exec-buffer.test.mjs` already pins for `EXEC_MAX_BUFFER`. |
| Android gate | `./gradlew :app:testDebugUnitTest` (existing 112 cases must still pass + new tests) | Per `openspec/config.yaml`. |
| Integration (manual smoke) | `/api/v1/orchestrator/status` with `engine === "pi"` round-trip; `/api/v1/orchestrator/models/update` writes Pi's `subagents.json`; profile apply reaches all three engines. | `scratch/test-orchestrator-engines.mjs` (manual smoke, documented in apply report). |

## Threat Matrix

N/A — this change touches only HTTP route bodies and engine config files. No new shell-out, subprocess, VCS/PR automation, executable-file classification, or process-integration boundary is introduced. The two existing `execSync("gentle-ai sync", ...)` calls remain wrapped in `try { } catch (syncErr) {}` at the route layer, exactly as today.

## Migration / Rollout

No data migration. The contract is additive:
- Existing `state.json` files keep `model_assignments` and `claude_phase_assignments` as-is.
- Existing `opencode.json` is untouched unless the user applies one.
- Pi's `subagents.json` is read on first status fetch; nothing is written until the user applies a profile or updates a Pi model.
- `SDD_PROFILE_PRESETS` gains a third map (`piAssignments`) with Pi-shaped bindings. Existing presets still ship OpenCode + Claude; Pi defaults to empty `{}` (Pi writes nothing on apply until the user explicitly chooses Pi models).

Backward-compatible wire: `OrchestratorStatus` adds two optional fields with defaults — Android clients older than this change deserialize them as empty/false and keep working.

Rollback: delete `idupi-server/lib/orchestrator/` and restore `index.mjs` routes from git (per proposal's rollback plan).

## Open Questions

None — the four spec open decisions are resolved above (`sdd-status`/`sdd-sync` → `sdd-verify`; per-engine with isolation; reported-but-not-fatal `skipped[]`; `pi-cli` accepted as alias). Owner approval still required at apply time if they prefer atomic apply or stricter Pi aliasing; both are reversible without redesign.