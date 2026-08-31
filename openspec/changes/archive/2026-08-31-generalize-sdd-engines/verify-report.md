```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:2b55ce478b8639178118fe4246424ba562b798778154d89e785e93654dbb2222
verdict: fail
blockers: 0
critical_findings: 0
requirements: 12/12
scenarios: 17/19
test_command: node --test idupi-server/test/orchestrator-contract.test.mjs idupi-server/test/orchestrator-engines.test.mjs idupi-server/test/orchestrator-routes.test.mjs
test_exit_code: 0
test_output_hash: sha256:2b55ce478b8639178118fe4246424ba562b798778154d89e785e93654dbb2222
build_command: node --check idupi-server/lib/orchestrator/contract.mjs idupi-server/lib/orchestrator/engines/opencode.mjs idupi-server/lib/orchestrator/engines/claude.mjs idupi-server/lib/orchestrator/engines/pi.mjs
build_exit_code: 0
build_output_hash: sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

## Verification Report

**Change**: generalize-sdd-engines
**Version**: N/A (delta specs under `openspec/changes/generalize-sdd-engines/specs/`)
**Mode**: Standard (Strict TDD not active)

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total (PR1+PR2+PR3a+PR3b) | 7 phases, all complete per apply-progress #5497 |
| Tasks incomplete | 0 |
| Spec requirements | 12 (contract 5 + adapters 7) |
| Spec scenarios | 19 (contract 7 + adapters 12) |

### Build & Tests Execution

**Build**: ✅ Passed (`node --check` on all 4 modules, exit 0, empty output → `E3B0C442…`)
```text
node --check idupi-server/lib/orchestrator/contract.mjs idupi-server/lib/orchestrator/engines/{opencode,claude,pi}.mjs
exit 0
```

**Tests**: ✅ 43 passed / ❌ 0 failed (Node, exit 0) — `test_output_hash=2B55CE47…`
```text
node --test orchestrator-contract.test.mjs orchestrator-engines.test.mjs orchestrator-routes.test.mjs
ℹ tests 43  ℹ pass 43  ℹ fail 0
```

**Coverage**: ➖ Not available (no coverage tooling configured; behavioral tests cover all 12 requirements).

**Android gate (CI-gated, NOT run locally)**: `./gradlew :app:testDebugUnitTest` BLOCKED — no Android SDK in this environment. PiPhaseConfigTest (8) + OrchestratorViewModelTest (5 new Pi-flow) tests are written and structurally sound but unexecuted. CI is authoritative for this gate.

### Spec Compliance Matrix

| # | Requirement (spec) | Scenario | Test | Result |
|---|--------------------|----------|------|--------|
| 1 | contract: Canonical phase namespace | Canonical set is the reference | `orchestrator-contract.test.mjs` CANONICAL_PHASES includes sdd-propose | ✅ COMPLIANT |
| 2 | contract: Phase alias normalization | Pi proposal name normalizes (sdd-proposal↔sdd-propose) | contract test canonicalize("sdd-proposal")==="sdd-propose" | ✅ COMPLIANT |
| 3 | contract: Phase alias normalization | Pi-only phase maps deterministically (sdd-status/sdd-sync→sdd-verify) | contract test PHASE_ALIASES maps both | ✅ COMPLIANT |
| 4 | contract: Engine-agnostic ModelBinding | Binding round-trips per engine | orchestrator-engines.test.mjs read/write round-trip per engine | ✅ COMPLIANT |
| 5 | contract: resolveEngine(engineId) | Each engine resolves | orchestrator-routes normalizeEngineId accepts pi/opencode/claude | ✅ COMPLIANT |
| 6 | contract: resolveEngine(engineId) | Unknown engine rejected (no silent OpenCode fallthrough) | contract test resolveEngine("copilot"/""/null) rejects; routes updateEngineModel ok=false | ✅ COMPLIANT |
| 7 | contract: index.mjs routing boundary | Routing delegates (no inline engine logic) | routes.mjs extraction; index.mjs net -130 lines; 43 tests via delegation | ✅ COMPLIANT |
| 8 | adapters: Pi native subagents.json | Status reads Pi bindings | pi.mjs readEngine denormalizes; routes readOrchestratorStatus reads piPhaseAssignments canonically | ✅ COMPLIANT |
| 9 | adapters: Pi native subagents.json | models/update writes Pi natively | routes updateEngineModel routes pi→Pi adapter, writes canonical phase | ✅ COMPLIANT |
| 10 | adapters: Pi native subagents.json | gentle-ai `models.json` untouched | pi.mjs nativeTargets = subagents.json only; writeDoc writes only subagentsPath() | ✅ COMPLIANT (inspection) |
| 11 | adapters: Safe apply read-merge-write + backup | Unknown keys preserved | orchestrator-engines opencode/claude "preserves non-agent keys / other keys" | ✅ COMPLIANT |
| 12 | adapters: Safe apply read-merge-write + backup | Backup before first write | engines test "first write creates .bak" (per-process Set) | ✅ COMPLIANT |
| 13 | adapters: Pi skips no-equivalent phases | Review agent skipped + reported | routes updateEngineModel reports skipped[]; applyProfilePreset reports Pi skipped[] for review-* | ✅ COMPLIANT |
| 14 | adapters: OpenCode & Claude adapters | OpenCode inline model written | engines test opencode write model | ✅ COMPLIANT |
| 15 | adapters: OpenCode & Claude adapters | Claude assignment written | engines test claude write claude_phase_assignments | ✅ COMPLIANT |
| 16 | adapters: Profile presets reach all engines | Preset applied to three engines | routes applyProfilePreset fans out to pi/claude/opencode; SDD_PROFILE_PRESETS has piAssignments | ✅ COMPLIANT |
| 17 | adapters: Base mode without gentle-ai | Profile apply in base mode | routes tests use temp dirs, no gentle-ai; readOrchestratorStatus works w/o state.json | ✅ COMPLIANT |
| 18 | adapters: Base mode / banner | Banner reflects detection | Android `GentleAiDetectionBanner` text "detectado (modo 2)" / "no instalado (modo base)" — TEST WRITTEN, NOT RUN (gradle blocked) | ⚠️ UNTESTED (CI gate) |
| 19 | adapters: Android Pi parity | Pi assignments visible under Pi selector | `ModelosTabView` engine-aware pulls piPhaseAssignments; OrchestratorViewModelTest "activeEngine selects pi" — TEST WRITTEN, NOT RUN (gradle blocked) | ⚠️ UNTESTED (CI gate) |

**Compliance summary**: 17/19 scenarios compliant at runtime (Node 43/43). 2 Android scenarios have covering tests written but not executed (no SDK) — CI-gated, not defects.

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| Alias normalization | ✅ Implemented | contract.mjs PHASE_ALIASES: sdd-proposal→sdd-propose, sdd-status/sdd-sync→sdd-verify; idempotent canonicalize |
| Engine rejection | ✅ Implemented | resolveEngine throws UnknownEngineError for copilot/""/null/undefined; no OpenCode fallthrough |
| pi-cli alias | ✅ Implemented | resolveEngine normalizes pi-cli→pi (EngineLabel.kt already maps both) |
| .bak rollback | ✅ Implemented | backupIfFirstWrite per-process Set; created before first write |
| skipped[] preserved | ✅ Implemented | PI_NO_EQUIVALENT_PHASES (review-*) reported in result.skipped |
| Unknown keys preserved | ✅ Implemented | read-merge-write in every adapter preserves foreign keys |
| Error isolation | ✅ Implemented | per-engine try/catch; one adapter failure returns ok:false, does not block others |
| Server net-line guard | ✅ Implemented | routes.mjs (570 lines) holds routing; index.mjs 4330 (was ~4460), net -130 vs main |
| gentle-ai try/catch stays | ✅ Implemented | routes.mjs wraps `gentle-ai sdd-status`/`sync` in try/catch at route layer; adapters never invoke gentle-ai |

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| Two-level motor selector + shared 4 tabs | ✅ Yes | OrchestratorTab = {FASES,MODELOS,PERFILES,HERRAMIENTAS}; OrchestratorEngine chip row [Pi\|OpenCode\|Claude] |
| Pi reads ~/.pi/subagents.json normalized proposal→propose | ✅ Yes | pi.mjs denormalizes sdd-proposal→sdd-propose; decanonicalize table |
| Shared tabs driven by motor selector (not per-engine swap) | ✅ Yes | Owner-confirmed (mem #5500); tab enum collapsed, no MODELS_OPENCODE/MODELS_CLAUDE |
| gentle-ai banner modo base vs detectado | ✅ Yes | Exact copy verified in GentleAiDetectionBanner |
| Drawer order | ⚠️ Partial | Documented 5-item subset [Pantalla Remota, Chat, Proyectos, Orquestador SDD, Configuracion] in correct RELATIVE order, but Alerts/Dashboard/Sessions/Console interleave between Orquestador and Configuracion. Intentional per apply-progress; see WARNING W2 |

### Issues Found

**CRITICAL**: None

**WARNING**:
- **W1 (CI gate)**: Android Kotlin test gate (`PiPhaseConfigTest` 8 + `OrchestratorViewModelTest` 5 new) not executed — no Android SDK in this env. Verify in CI before archive. Scenarios 18–19 are CI-gated, not code-defective.
- **W2 (minor, documented)**: Drawer order puts 4 extra items (Alerts/Dashboard/Sessions/Console) between `Orquestador SDD` and `Configuracion`. The owner's literal order had Configuracion at position 5. The 5 documented items keep correct relative order; deviation is intentional per apply-progress #5497. Recommend owner sign-off that the interleaving is acceptable.
- **W3 (review workload guard)**: PR1 = 1028 changed lines (1028 ins, 0 del) and PR2 = 1138 changed lines (930 ins + 208 del) on `feat/stream-pipeline`, both EXCEEDING the declared **800-line/PR** budget (tasks.md §Review Workload Forecast, also stated as the per-PR cap in apply-progress #5497). PR3a (422) and PR3b (456) are within budget. The server-extraction PRs are larger than the stated guard; reviewers should expect higher cognitive load for PR1/PR2. The CONTEXT's "800/852/422/456" split does not match actual git diff stats.

**SUGGESTION**:
- S1: Consider a follow-up doc note mirroring the `pi-cli`/`sdd-status`→`sdd-verify` rationale into index.mjs (tasks 7.2) so future readers of the route layer see the contract pointer.

### Verdict

FAIL (incomplete evidence — NOT a code defect). The Node implementation is fully verified: 43/43 tests pass, all 12 requirements mapped, 17/19 scenarios compliant at runtime. The validator requires a non-passing verdict because 2 Android UI scenarios (banner reflects detection #18, Pi assignments visible #19) have covering tests written (PiPhaseConfigTest 8 + OrchestratorViewModelTest 5 new) but were NOT executed — no Android SDK in this environment. Per skill rule, a scenario is compliant only when a covering test passed at runtime, so these count as incomplete, not compliant.

These are environmental CI gates, not defects: code matches the owner's UI drawing (motor selector top, 4 shared tabs, gentle-ai banner modo base/detectado, Pi Modelos source), the server net-line guard holds, and the Kotlin tests exist and are structurally sound. Once CI runs `./gradlew :app:testDebugUnitTest` and confirms the Android tests, this change is archive-ready (re-verify to flip to PASS). No critical or blocking findings.
