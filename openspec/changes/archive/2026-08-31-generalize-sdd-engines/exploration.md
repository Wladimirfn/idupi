# Exploration: Generalize SDD Engines

## Problem Statement

IDUPI wraps three AI coding engines (OpenCode, Claude, Pi) and exposes their SDD phases and model assignments. Today, SDD orchestration is split into two hardcoded engine maps — `modelAssignments` (OpenCode format) and `claudeAssignments` (Claude format) — with no Pi representation at all. Profile apply writes only to OpenCode config files; Pi's model bindings in `~/.pi/gentle-ai/models.json` are never touched by IDUPI. The goal is to make all three engines interchangeable under a single SDD contract, so profiles apply uniformly and new engines can be added without server code changes.

## Current Split (Evidence)

### Server: Two hardcoded engine maps, no Pi

- `SDD_PROFILE_PRESETS` (index.mjs:3198-3288): Three presets (strong/mid/cheap) each define `modelAssignments` (OpenCode `{ provider_id, model_id, effort }`) and `claudeAssignments` (Claude `{ model: "sonnet"|"haiku" }`). No Pi map exists.
- Profile apply (index.mjs:3436-3514): Writes `model_assignments` and `claude_phase_assignments` to `~/.gentle-ai/state.json`, then syncs `~/.config/opencode/opencode.json`. Does NOT write to `~/.pi/gentle-ai/models.json`.
- Models/update (index.mjs:3602-3673): Routes by `engine` param — `engine === "claude"` writes to `claude_phase_assignments`; everything else (including any future engine) falls through to `model_assignments` + `opencode.json`. Pi-specific routing is absent.
- `gentle-ai sync` (index.mjs:3500, 3659): Called after profile/model changes; likely syncs OpenCode profiles only.

### OpenCode binding source

- `~/.config/opencode/opencode.json`: Each subagent entry carries `"model": "<provider>/<id>"` and `"mode": "subagent"`. The server reads this at status time (index.mjs:3388-3406) and merges with `state.json` assignments.

### Pi binding source

- `~/.pi/gentle-ai/models.json`: Flat catalog `{ "sdd-apply": { "model": "opencode-go/..." }, ... }`. Agent definitions live in `~/.pi/agents/sdd-*.md`. There is NO server-side code that reads or writes this file.
- Pi uses `sdd-proposal` (not `sdd-propose`) plus `sdd-status` and `sdd-sync` — naming diverges from OpenCode.

### Claude binding source

- Claude has no local config file; model selection is a simple `{ model: "sonnet"|"haiku" }` map stored in `state.json` as `claude_phase_assignments`.

### Android client models

- `Orchestrator.kt` (Orchestrator.kt:22-71): Two parallel data classes — `ClaudePhaseConfig` and `OpenCodeModelAssignment` — with no Pi equivalent.
- `OrchestratorStatus` (line 59-71): Carries `claudePhaseAssignments` and `modelAssignments` as separate fields.
- `updateOrchestratorModel` (RealIduPiClient.kt:638-655): Sends `{ engine, phase, modelId, providerId, effort }` — the `engine` field exists but the server treats only `"claude"` as special; everything else is OpenCode.

## Affected Areas

| File | Why |
|------|-----|
| `idupi-server/index.mjs` | SDD_PROFILE_PRESETS, profile apply, models/update, status endpoint — all need engine-agnostic routing |
| `app/.../domain/model/Orchestrator.kt` | Data classes assume two engines; needs Pi representation or generic model type |
| `app/.../data/remote/RealIduPiClient.kt` | API calls carry engine param but server ignores it for non-Claude |
| `~/.pi/gentle-ai/models.json` | Pi's binding source — server currently never writes here |
| `~/.config/opencode/opencode.json` | OpenCode's binding source — already read/written by server |

## Approaches

### A) Canonical SDD Contract + One Adapter Per Engine

Define a single `EngineAdapter` interface that every engine implements. The server owns the SDD contract (phase names, model assignment shape) and each adapter translates between the canonical form and the engine's native config format.

```
Interface:
  readAssignments() → Map<phase, ModelBinding>
  writeAssignments(Map<phase, ModelBinding>) → void
  listPhases() → List<phase>
```

Adapters: `OpencodeAdapter` (reads/writes opencode.json), `ClaudeAdapter` (reads/writes state.json claude_section), `PiAdapter` (reads/writes ~/.pi/gentle-ai/models.json).

| Dimension | Assessment |
|-----------|------------|
| Maintenance cost | Medium — one adapter per engine, but adapter logic is thin (config format translation) |
| Atomicity | High — profile apply calls each adapter; failure in one can be isolated |
| Risk | Medium — must handle Pi's naming divergence (`sdd-proposal` vs `sdd-propose`) and ensure `gentle-ai sync` still works |
| Adding a new engine | Low effort — implement adapter, register it |
| Effort | Medium |

### B) Leave Engines Native; IDUPI Only Surfaces What Each Exposes

No unification. IDUPI reads each engine's native config and presents both (or three) views to the user. Profiles apply only to the engine the user selects, not atomically across all three.

| Dimension | Assessment |
|-----------|------------|
| Maintenance cost | Low — no new abstraction, just more conditional reads |
| Atomicity | None — each profile apply is engine-local; user must apply to each engine separately |
| Risk | Low — minimal code changes, but the UX is fragmented and error-prone |
| Adding a new engine | Low effort — add a reader, add a UI tab |
| Effort | Low |

### C) Unified "Engine Registry" JSON Owned by IDUPI

IDUPI writes a single `engine-registry.json` (e.g. in `~/.gentle-ai/`) that all engines are configured to read from. The server writes once; each engine consumes from the same source.

| Dimension | Assessment |
|-----------|------------|
| Maintenance cost | Low for IDUPI (single write), but HIGH for engines — requires teaching Pi and Claude to read from a shared location |
| Atomicity | High — single source of truth, atomic writes |
| Risk | High — Pi and Claude are third-party CLIs that may not support reading from an external registry; `gentle-ai sync` may not propagate this format |
| Adding a new engine | Low for IDUPI, high for the engine (must implement reader) |
| Effort | High |

## Recommendation

**Approach A (Canonical Contract + Adapters)** is the recommended path.

Reasons:
1. The server already has the `engine` parameter in the models/update API (index.mjs:3608) — the routing infrastructure exists, just needs to be extended.
2. Pi's model binding (`~/.pi/gentle-ai/models.json`) is a simple flat JSON — trivial to read/write.
3. Claude's binding is even simpler (a model name per phase).
4. The Android client already distinguishes engines via `engineLabel()` (EngineLabel.kt:16-24) — it just needs a third model assignment type.
5. Approach C requires third-party CLI cooperation that may not exist. Approach B pushes complexity to the user.

The implementation would:
1. Define a `ModelBinding` data class in the server (provider_id, model_id, effort, model — all optional, engine picks what it needs).
2. Create adapter functions: `readOpenCodeAssignments()`, `readClaudeAssignments()`, `readPiAssignments()` and corresponding write functions.
3. Add Pi assignments to `SDD_PROFILE_PRESETS` (a `piAssignments` map alongside `modelAssignments` and `claudeAssignments`).
4. Extend the profile apply endpoint to call all adapters.
5. Add `PiModelAssignment` to the Android client's `Orchestrator.kt`.
6. Handle naming divergence: the adapter maps between canonical phase names and Pi's names (`sdd-proposal` ↔ `sdd-propose`).

## Open Questions

1. **Should Pi phases be canonicalized?** The server could normalize `sdd-proposal` → `sdd-propose` so all engines share one phase namespace, or keep engine-local names. Canonicalization simplifies the contract but changes Pi's existing behavior.
2. **Does `gentle-ai sync` propagate to Pi?** If it already writes Pi's models.json, the adapter may be redundant for sync scenarios. Need to verify.
3. **Should profiles apply atomically across all engines or per-engine?** Approach A supports both — the question is what the UX should be.
4. **What about the review agents** (`review-risk`, `review-resilience`, etc.) that exist in `modelAssignments` but have no Pi equivalent? Should the Pi adapter silently skip them, or should Pi define its own review agents?
5. **Pi backup artifacts** (`.bak-models-2026-05-14` in `~/.pi/agents/`): The model binding was migrated from `.md` files to `models.json` in May 2026. Is `models.json` stable enough to become the adapter's write target, or should we expect further format changes?

## Key Learnings

1. SDD_PROFILE_PRESETS contains only two engine maps (OpenCode and Claude); Pi has no representation in the server's preset system.
2. The models/update endpoint routes `engine === "claude"` specially but treats all other engines identically as OpenCode.
3. Pi's model binding lives in `~/.pi/gentle-ai/models.json` (flat JSON) and was migrated from `.md` files in May 2026.
4. Pi uses `sdd-proposal` while OpenCode uses `sdd-propose`; both share most other SDD phase names.
5. The Android client's `OrchestratorStatus` carries two separate assignment maps with no generic engine field.
