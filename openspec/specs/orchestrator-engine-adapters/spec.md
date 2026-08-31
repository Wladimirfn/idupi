# Orchestrator Engine Adapters Specification

## Purpose

Three thin adapters bind each engine to its native config source via the canonical contract: per-phase model binding read/write with safe apply. Adapters never invoke gentle-ai and never teach engines new config formats.

## Requirements

### Requirement: Pi adapter — native subagents.json

The Pi adapter MUST read and write `~/.pi/subagents.json` → `model_profiles.*`, translating canonical phase names to Pi names at its boundary (e.g. `sdd-propose` → `sdd-proposal`). It MUST NOT read or write gentle-ai's `~/.pi/gentle-ai/models.json`.

#### Scenario: Status reads Pi bindings

- GIVEN `model_profiles["sdd-proposal"]` exists in `subagents.json`
- WHEN engine=pi status is built
- THEN canonical `sdd-propose` carries the binding from that entry

#### Scenario: models/update writes Pi natively

- GIVEN models/update with engine=pi and phase=sdd-propose
- WHEN applied
- THEN `subagents.json` `model_profiles["sdd-proposal"]` reflects the new binding

#### Scenario: gentle-ai file untouched

- GIVEN any engine=pi read or write
- WHEN executed
- THEN `~/.pi/gentle-ai/models.json` is neither read nor modified

### Requirement: Safe apply — read-merge-write with backup

Every adapter write MUST be read-merge-write preserving unknown or foreign keys, and MUST create a `.bak` backup of the target file before its first write in a process lifetime. Failure MUST be isolated per engine and reported, never silent.

#### Scenario: Unknown keys preserved

- GIVEN `subagents.json` contains non-SDD keys
- WHEN a Pi binding is applied
- THEN non-SDD keys are preserved in the rewritten file

#### Scenario: Backup before first write

- GIVEN no `.bak` exists for the target file
- WHEN the adapter first writes
- THEN a `.bak` copy is created before the write

### Requirement: Pi adapter skips phases without Pi equivalents

Phases with no Pi equivalent (e.g. review agents) MUST be skipped by the Pi adapter — never written under a guessed name — and the skipped set MUST be reported in the apply or status result.

#### Scenario: Review agent skipped

- GIVEN profile apply includes `review-risk`
- WHEN applied to engine=pi
- THEN no Pi entry is written for it and it appears in the reported skipped set

### Requirement: OpenCode and Claude adapters

The OpenCode adapter MUST read/write inline subagent `"model"` entries in `~/.config/opencode/opencode.json`. The Claude adapter MUST read/write `claude_phase_assignments` in `~/.gentle-ai/state.json`. Existing try/catch wrapping of gentle-ai calls MUST remain.

#### Scenario: OpenCode inline model written

- GIVEN models/update with engine=opencode
- WHEN applied
- THEN the subagent entry's `"model"` in `opencode.json` reflects the binding

#### Scenario: Claude assignment written

- GIVEN models/update with engine=claude
- WHEN applied
- THEN `claude_phase_assignments` in `state.json` reflects the binding

### Requirement: Profile presets reach all engines

`SDD_PROFILE_PRESETS` MUST define bindings for all three engines, and profile apply MUST route each engine's portion through its own adapter.

#### Scenario: Preset applied to three engines

- GIVEN a profile preset is applied
- WHEN it completes
- THEN pi, opencode, and claude each received their preset bindings via their adapters

### Requirement: Base mode without gentle-ai; optional gentle-ai surfaces

All adapter reads, writes, and profile apply MUST succeed with NO gentle-ai installed. gentle-ai remains optional: the Android Orchestrator UI MUST show a detection banner distinguishing installed (`gentle-ai: detectado (modo 2)`) from base mode (`gentle-ai: no instalado (modo base)`), and MAY show the gentle-ai tool tab only when detected.

#### Scenario: Profile apply in base mode

- GIVEN gentle-ai is not installed
- WHEN a profile is applied
- THEN all three engines receive their bindings and no gentle-ai error surfaces

#### Scenario: Banner reflects detection

- GIVEN gentle-ai is absent
- WHEN the Orchestrator screen renders
- THEN the base-mode banner shows; WHEN present, the detected banner and tool tab appear

### Requirement: Android Pi parity

`Orchestrator.kt` MUST gain a Pi representation (e.g. `PiPhaseConfig`) parsed into the same engine-agnostic `OrchestratorStatus` shape, and the Orchestrator UI MUST expose engine selection [Pi | OpenCode | Claude] with function tabs [Fases | Modelos | Perfiles | Herramientas] showing Pi assignments at parity.

#### Scenario: Pi assignments visible

- GIVEN status returns Pi assignments
- WHEN the Orchestrator screen renders
- THEN Pi assignments display under the Pi engine selection with the same status shape as other engines
