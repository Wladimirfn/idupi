# Proposal: Generalize SDD Engines — Pi as First-Class Engine

**Change surfaces** (per `rules.proposal`): `idupi-server/` (primary) + `app/` (Android client). Zero new Node dependencies.

## Intent

The server special-cases `engine === "claude"` and routes every other engine — including Pi — to OpenCode config, so Pi has no first-class SDD representation. This change separates the three engines behind one canonical SDD contract with per-engine adapters, and extracts orchestrator logic out of the ~4460-line `index.mjs` into `lib/` modules so it stops growing.

## Scope

### In Scope
- Pi as first-class engine bound to its native `~/.pi/subagents.json` (`model_profiles.*`) — explicitly NOT gentle-ai's `~/.pi/gentle-ai/models.json` (avoids hidden gentle-ai coupling).
- Canonical SDD phase set, `ModelBinding` shape, and `resolveEngine(engineId)` in a new contract module.
- Per-engine adapters writing only engine-native config sources.
- Extraction of orchestrator/SDD logic from `index.mjs` into `idupi-server/lib/orchestrator/`, following the existing `lib/` pattern.
- Android: Pi engine representation in Orchestrator models.
- Base mode works with NO gentle-ai installed; existing try/catch wrapping of `gentle-ai` calls stays.

### Out of Scope
- No gentle-ai hard dependency, build-time requirement, or sync invocation from new adapters.
- No rewrite of chat/screen/session core.
- No unified registry teaching engines a new config format — engines keep native files.
- No new Pi review-agent phases in this slice (PiAdapter skips unknown phases).

## Capabilities

### New Capabilities
- `orchestrator-engine-contract`: canonical phase set, ModelBinding, phase-name normalization (`sdd-proposal` ↔ `sdd-propose`), `resolveEngine(engineId)`.
- `orchestrator-engine-adapters`: per-engine read/write of native binding sources with safe apply.

### Modified Capabilities
- None. Existing specs are sessions-only; no orchestrator capability spec exists yet.

## Approach — What Goes Where

`index.mjs` keeps HTTP routing + request parsing ONLY. Engine logic moves out:

| Module | Responsibility |
|---|---|
| `lib/orchestrator/contract.mjs` **(NEW)** | Canonical phases, ModelBinding, `PHASE_ALIASES`, `resolveEngine(id)` |
| `lib/orchestrator/engines/opencode.mjs` **(NEW)** | read/write `~/.config/opencode/opencode.json` inline subagent models |
| `lib/orchestrator/engines/claude.mjs` **(NEW)** | read/write `state.json` → `claude_phase_assignments` |
| `lib/orchestrator/engines/pi.mjs` **(NEW)** | read/write `~/.pi/subagents.json` → `model_profiles`, translating names |
| `idupi-server/index.mjs` **(EDIT)** | `SDD_PROFILE_PRESETS` gains `pi` bindings; `models/update` and profile apply delegate via `resolveEngine` |

Normalization: canonical names follow OpenCode's set (superset); each adapter translates at its boundary. Pi aliases: `sdd-proposal`→`sdd-propose`; `sdd-status`/`sdd-sync` map to canonical equivalents. Phases with no Pi equivalent are skipped by PiAdapter, never silently written.

## Affected Areas

| File | Impact | Change |
|---|---|---|
| `idupi-server/lib/orchestrator/contract.mjs` | NEW | Canonical contract + normalization |
| `idupi-server/lib/orchestrator/engines/{opencode,claude,pi}.mjs` | NEW | Three adapters |
| `idupi-server/index.mjs` | EDIT | Routing delegates to lib; presets gain `pi`; net lines shrink |
| `app/.../domain/model/Orchestrator.kt` | EDIT | Add `PiModelAssignment`; `OrchestratorStatus` carries third map |
| `app/.../data/remote/RealIduPiClient.kt` | EDIT | No API change (`engine` param already sent); parse Pi in status |
| `app/.../ui/` OrchestratorViewModel/screens | EDIT | Pi tab parity; generalize gentle-ai-centric copy |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Naming normalization bugs (`sdd-proposal` ↔ `sdd-propose`) | Med | Single alias table in contract.mjs; round-trip tests |
| `subagents.json` write stability (schema migrated 2026-05) | Med | Read-merge-write preserving unknown keys; `.bak` before first write |
| Overlap with `gentle-ai sync` for Pi | Low | Adapters never invoke gentle-ai; document precedence |
| 800-line review budget | Med | Adapters are thin; extraction is move-not-rewrite |

## Rollback Plan

Adapters are additive: delete `lib/orchestrator/` and restore `index.mjs` routes from git. Config writes are per-phase with `.bak` backups; Pi's `subagents.json` untouched unless a Pi assignment is applied.

## Dependencies

- None external. Node ESM, zero npm deps (per `config.yaml`).

## Success Criteria

- [ ] `engine === "pi"` round-trips: status reads Pi bindings from `subagents.json`; `models/update` writes them back
- [ ] Profile apply reaches all three engines via adapters; base mode works without gentle-ai
- [ ] `index.mjs` net line count does not grow
- [ ] `node --check` passes on new modules; `./gradlew :app:testDebugUnitTest` passes
- [ ] Android Orchestrator UI shows Pi assignments

## Open Questions (owner)

1. Apply profiles atomically across all three engines, or per-engine choice?
2. Should missing Pi phases (review agents) surface a UI warning?
3. Confirm `subagents.json` as stable write target and `.bak` backup policy.
4. Confirm OpenCode naming as the canonical phase namespace.
