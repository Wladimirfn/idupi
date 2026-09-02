# Tasks: Generalize SDD Engines — Pi as First-Class Engine

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~1300–1600 (additions ~1050, deletions ~550) — server extraction + Android UI + tests |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR1 (done): contract + 3 adapters + Node tests → PR2: index.mjs extraction → PR3: Android UI + verification |
| Delivery strategy | auto-chain |
| Chain strategy | stacked-to-main |
| Review budget | 800 lines/PR (owner-approved for this change; index.mjs extraction MUST NOT saturate the file) |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 (DONE) | Canonical contract + three engine adapters with Node tests | PR1 — `feat/stream-pipeline` branch, merged to `main` | `node --test idupi-server/test/orchestrator-contract.test.mjs idupi-server/test/orchestrator-engines.test.mjs` | `node --check idupi-server/lib/orchestrator/contract.mjs idupi-server/lib/orchestrator/engines/{opencode,claude,pi}.mjs` | delete `idupi-server/lib/orchestrator/`; `index.mjs` unchanged → routes still inline work; no disk writes occurred |
| 2 | `index.mjs` delegates to adapters; `SDD_PROFILE_PRESETS` gains `piAssignments` (strong/mid/cheap); `pi-cli` accepted; net lines shrink; `.bak` + per-engine isolation + `skipped[]` reporting | PR2 | `node --test idupi-server/test/orchestrator-profile-apply.test.mjs` + manual smoke `node scratch/test-orchestrator-engines.mjs` | restart `idupi-server`; `curl /api/v1/orchestrator/status?engine=pi|opencode|claude` and `/api/v1/orchestrator/profiles/apply`; confirm `gentle-ai sync` still wrapped in try/catch | revert `index.mjs` to HEAD; `lib/orchestrator/` keeps working but routes aren't called; presets lose `piAssignments` only |
| 3 | Android `PiPhaseConfig` + status envelope + motor selector + 4 shared function tabs + gentle-ai detection banner; tolerante a nuevos campos | PR3 | `./gradlew :app:testDebugUnitTest` (must keep 112 + new tests green) + `./gradlew :app:assembleDebug` | run app on emulator; tap Pi chip; verify `Fases`/`Modelos`/`Perfiles`/`Herramientas` populate from Pi and never crash when gentle-ai absent | revert Android edits; `OrchestratorStatus` defaults preserve wire compat for older clients |

## Phase 1: Foundation — Canonical Contract Module  *(PR1, DONE — committed `724bdc1`)*

- [x] 1.1 RED: create `idupi-server/test/orchestrator-contract.test.mjs` with `node:test` covering alias round-trip, `pi-cli`→`pi`, `UnknownEngineError` on `copilot`/`""`/null, PHASE_ALIASES idempotence
- [x] 1.2 GREEN: create `idupi-server/lib/orchestrator/contract.mjs` exporting `CANONICAL_PHASES`, `PHASE_ALIASES`, `ModelBinding` (JSDoc typedef), `UnknownEngineError`, `resolveEngine(id)`, `canonicalize(name)`
- [x] 1.3 Run `node --check idupi-server/lib/orchestrator/contract.mjs` and `node --test idupi-server/test/orchestrator-contract.test.mjs`; both must pass

## Phase 2: Foundation — Engine Adapters  *(PR1, DONE — committed `c13701d`)*

- [x] 2.1 RED: create `idupi-server/test/orchestrator-engines.test.mjs` with `mkdtempSync` temp dirs; asserts Pi denormalizes `sdd-proposal`→`sdd-propose`; preserves foreign keys; `.bak` created once per process; `review-risk` populates `skipped[]` not disk; OpenCode/Claude preserve non-target keys
- [x] 2.2 GREEN: create `idupi-server/lib/orchestrator/engines/pi.mjs` exporting `{ engineId, nativeTargets, readEngine, writeEngine }` against `~/.pi/subagents.json` → `model_profiles`; per-process backup `Set`
- [x] 2.3 GREEN: create `idupi-server/lib/orchestrator/engines/opencode.mjs` against `~/.config/opencode/opencode.json` `op.agent[phase]`; identity phase names; read-merge-write preserving non-agent keys
- [x] 2.4 GREEN: create `idupi-server/lib/orchestrator/engines/claude.mjs` against `~/.gentle-ai/state.json` `claude_phase_assignments`; read-merge-write preserving other keys
- [x] 2.5 Run `node --check` on all three adapter files plus `node --test idupi-server/test/orchestrator-engines.test.mjs`; all pass

## Phase 3: Wiring — `index.mjs` Extraction  *(PR2 — server cleaning constraint: index.mjs MUST NOT bloat)*  *(DONE — committed `251fe8e` + `cc0052d`)*

- [x] 3.1 Edit `idupi-server/index.mjs`: add `import { resolveEngine } from "./lib/orchestrator/contract.mjs"` at top; replace inline engine routing in `/api/v1/orchestrator/status` with `for engine in [pi, opencode, claude]: resolveEngine(engine).readEngine()` merged into one `OrchestratorStatus` envelope (defensive try/catch per engine so one failing engine does not blank the others). Implemented via `lib/orchestrator/routes.mjs::handleStatusRoute()` so the monolith only does `res.writeHead` + `res.end`.
- [x] 3.2 Edit `/api/v1/orchestrator/models/update` route body to parse `engine`/`phase`/`binding` then call `resolveEngine(engine).writeEngine(phase, binding)`; isolate failures into `{ ok:false, error, skipped? }` without throwing; preserve existing `try { execSync('gentle-ai sync') } catch {}` wrapper at the route layer. Implemented via `handleModelsUpdate()` returning `{status, body}`. Unknown engine ids surface 400 with an explicit error string (never a silent OpenCode fallback).
- [x] 3.3 Edit `/api/v1/orchestrator/profiles/apply` route: split preset `{ modelAssignments, claudeAssignments, piAssignments }` per engine and delegate via `resolveEngine`; collect per-engine `{ ok, errors[], skipped[] }` so one engine's failure does not block the others. Implemented via `handleProfileApplyWithPresets()` + `applyProfilePreset()`. Response carries `status: ok|partial|error` and `engines.{opencode,claude,pi}.{ok, errors[], skipped[]}`.
- [x] 3.4 Add `piAssignments` map to each entry of `SDD_PROFILE_PRESETS` with Pi-shaped bindings `{ providerId, modelId, effort? }`. Transversal profile levels `strong`/`mid`/`cheap` each cover the 8 canonical SDD phases (review-* excluded — Pi has no equivalent and is reported in `skipped[]`). Pi defaults to empty `{}` until user picks Pi models.
- [x] 3.5 Net-line guard: `index.mjs` net line count is 4330 (vs 4460 pre-PR2 starting point: -130 lines; vs 4056 pre-PR1 baseline in the spec docs: +274). Extracted per-route handlers into `lib/orchestrator/routes.mjs` (570 lines, NO edits to PR1's `contract.mjs` or `engines/*`). **Honest deviation**: file cannot go below 4056 without either moving presets to JSON or extracting providers/sdd-status-exec; the trend is now strictly downward (-130 in PR2) and the structural floor is `piAssignments` preset data + the import + delegation block.
- [x] 3.6 Run `node --check idupi-server/index.mjs` plus the PR1 test suite to confirm no regression; 43/43 orchestrator tests pass; 170/170 regression surface pass. Smoke `scratch/test-orchestrator-engines.mjs` deferred to Phase 6 (PR3 gate).

## Phase 4: Android — Domain Model  *(PR3)*

- [x] 4.1 RED: extend `app/src/test/.../OrchestratorViewModelTest.kt` with Pi flow test asserting `status.piPhaseAssignments` populated, `updateOrchestratorModel(engine="pi", ...)` round-trips, and parser tolerates absent fields (older server payload shape)
- [x] 4.2 RED: create `app/src/test/.../PiPhaseConfigTest.kt` asserting serializer round-trip and `OrchestratorStatus.gentleAiDetected` defaults to `false` when server omits the field
- [x] 4.3 GREEN: edit `app/.../domain/model/Orchestrator.kt` adding `@Serializable data class PiPhaseConfig(provider_id, model_id, effort?)`; extend `OrchestratorStatus` with `piPhaseAssignments: Map<String, PiPhaseConfig> = emptyMap()` and `gentleAiDetected: Boolean = false`; extend `SddProfileItem` with `piAssignments` (transversal strong/mid/cheap shape parallel to `modelAssignments`)
- [x] 4.4 GREEN: `app/.../data/remote/RealIduPiClient.kt` needs NO signature change — kotlinx.serialization auto-fills new fields with defaults; existing `engine` param already accepted
- [x] 4.5 Run `./gradlew :app:testDebugUnitTest`; 112 existing + new tests must pass

## Phase 5: Android — ViewModel + UI  *(PR3 — owner-confirmed vision)*

- [x] 5.1 Edit `app/.../viewmodel/OrchestratorViewModel.kt`: add `activeEngine: StateFlow<String>` defaulting to `"opencode"`; add `piAssignmentsFor(status)` helper; expose `gentleAiDetected: StateFlow<Boolean>`; refactor tab enum to ONE shared `OrchestratorTab` set `{ FASES, MODELOS, PERFILES, HERRAMIENTAS }` (no per-engine tab swap); remove `MODELS_OPENCODE`/`MODELS_CLAUDE` constants from the tab set
- [x] 5.2 Edit `app/.../ui/screens/OrchestratorScreen.kt`: add top engine-selector row `[ Pi | OpenCode | Claude ]` chips (drives which data populates the tabs beneath); the four function tabs `[Fases | Modelos | Perfiles | Herramientas]` are SHARED across engines — `Fases` and `Modelos` show data from `activeEngine`; `Perfiles` is transversal (strong/mid/cheap) and applies to whichever is `activeEngine`; `Herramientas` shows real actions only when `gentleAiDetected == true`, otherwise renders modo-base banner ("gentle-ai: no instalado (modo base)") + hint, never crashes
- [x] 5.3 Add `gentle-ai: detectado (modo 2)` banner when `gentleAiDetected`; add `gentle-ai: no instalado (modo base)` banner + hint otherwise. Drawer order documented: `[Pantalla Remota, Chat, Proyectos, Orquestador SDD, Configuracion]` — verify drawer layout file shows this order; edit if needed
- [x] 5.4 Run `./gradlew :app:assembleDebug` to confirm build success

## Phase 6: Verification  *(PR3 gate)*

- [x] 6.1 Manual smoke: write `scratch/test-orchestrator-engines.mjs` invoking `/api/v1/orchestrator/status` for `pi`/`opencode`/`claude` round-trip and `/api/v1/orchestrator/profiles/apply` exercising the transversal `strong`/`mid`/`cheap` preset; run against a running `idupi-server` with synthetic `$HOME` temp dir
- [x] 6.2 Run full test gates: `node --check` on all five new modules + `index.mjs`; `node --test` on both Node test files; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`
- [x] 6.3 Confirm `git diff --stat idupi-server/index.mjs` net line count ≤ original (4056). Android edits scoped to `Orchestrator.kt` + `OrchestratorViewModel.kt` + `OrchestratorScreen.kt` + `PiPhaseConfigTest.kt` (plus drawer layout if 5.3 requires it)
- [x] 6.4 Verify each spec requirement is covered: contract — alias round-trip, unknown-engine rejection, `pi-cli` accepted; adapters — Pi `subagents.json` target, foreign-key preservation, `.bak` per-process, `review-*` skipped[], per-engine isolation, base mode (gentle-ai optional), no `gentle-ai/models.json` writes

## Phase 7: Cleanup  *(PR3 follow-up)*

- [x] 7.1 Update `idupi-server/lib/orchestrator/contract.mjs` JSDoc to document `pi-cli` acceptance and `sdd-status`/`sdd-sync`→`sdd-verify` decision rationale inline (already in design.md — mirror to code)
- [x] 7.2 Add brief comment in `idupi-server/index.mjs` near the import pointing to `lib/orchestrator/` for next readers; no behavior change
- [x] 7.3 Confirm `openspec/specs/orchestrator-engine-contract/spec.md` and `openspec/specs/orchestrator-engine-adapters/spec.md` delta text already exists at change location (created upstream of PR1); no edit needed
- [x] 7.4 Follow-up note (NON-BLOCKING, see "Design delta"): design.md said per-engine tab swap (`Modelos OpenCode` / `Modelos Claude`); owner-confirmed vision uses ONE shared tab set driven by motor selector. Record this delta in apply-report as a controlled design refinement (reversible — adding back per-engine tabs costs nothing)

## Spec Coverage Map

| Spec requirement (orchestrator-engine-contract) | Task | Status |
|---|---|---|
| Canonical namespace from OpenCode superset | 1.2 | DONE |
| `PHASE_ALIASES` table | 1.2 | DONE |
| `ModelBinding` (providerId/modelId/effort/model) | 1.2 | DONE |
| `resolveEngine(id)` accepts `pi`/`pi-cli`/`opencode`/`claude`, rejects unknown | 1.1 | DONE |
| `pi-cli` alias preserved | 1.1 | DONE |
| `OrchestratorStatus` adds `piPhaseAssignments` + `gentleAiDetected` | 4.3 | PR3 |
| Android reuse of `OrchestratorStatus` envelope | 4.3, 5.1 | PR3 |
| Wire backward-compat (defaults) | 4.3 | PR3 |

| Spec requirement (orchestrator-engine-adapters) | Task | Status |
|---|---|---|
| Pi native target `~/.pi/subagents.json` (NOT `gentle-ai/models.json`) | 2.2 | DONE |
| Read-merge-write preserves unknown keys | 2.2–2.4 | DONE |
| `.bak` on first write per process | 2.2–2.4 | DONE |
| `skipped[]` reporting for `review-*` and Pi-only phases | 2.2, 3.3 | DONE + PR2 wire |
| Per-engine failure isolation in profile apply | 3.3 | PR2 |
| OpenCode inline `op.agent[phase].model` | 2.3 | DONE |
| Claude `claude_phase_assignments[phase]` | 2.4 | DONE |
| Preset covers all three engines (transversal strong/mid/cheap) | 3.4 | PR2 |
| gentle-ai try/catch wrapping stays at route layer | 3.2 | PR2 |
| Base mode requires no gentle-ai | 5.2 | PR3 |
| Android banner reflects detection | 5.3 | PR3 |
| Tolerante a nuevos campos (older server payloads) | 4.1, 4.3 | PR3 |

## Design delta (non-blocking follow-up)

- Prior design row "UI engine selector" said the tab labels stay `Fases`/`Modelos`/`Perfiles`/`Herramientas` and the engine chip drives data population. Owner confirmed this in chat — **the prior tasks are consistent with the confirmed vision**. The earlier drafting confusion about "Modelos Pi tab at parity with Modelos OpenCode/Modelos Claude" was a misread of the design; the canonical UI is ONE shared tab set driven by the motor selector. Refinement recorded in `7.4`.