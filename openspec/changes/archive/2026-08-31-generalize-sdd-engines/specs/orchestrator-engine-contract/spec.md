# Orchestrator Engine Contract Specification

## Purpose

Define the single canonical SDD contract every engine adapter implements: the canonical phase namespace, the engine-agnostic `ModelBinding` shape, phase-name normalization (`PHASE_ALIASES`), and the `resolveEngine(engineId)` entry point. The contract lives in `idupi-server/lib/orchestrator/contract.mjs`; `index.mjs` keeps HTTP routing and request parsing only.

## Requirements

### Requirement: Canonical phase namespace

The contract MUST define one canonical SDD phase set, using OpenCode's phase names as the superset. Every adapter MUST translate between canonical names and engine-native names at its own boundary. Engines MUST NOT be taught new config formats.

#### Scenario: Canonical set is the reference

- GIVEN any adapter
- WHEN it lists or writes phases
- THEN every phase name resolves to a member of the canonical set

### Requirement: Phase alias normalization (PHASE_ALIASES)

The contract MUST export a single `PHASE_ALIASES` table normalizing Pi naming to canonical: `sdd-proposal` ↔ `sdd-propose` bidirectionally, and Pi-only `sdd-status` and `sdd-sync` to deterministic canonical equivalents defined in the table. Normalization MUST be idempotent and round-trip safe where the target phase exists.

#### Scenario: Pi proposal name normalizes

- GIVEN Pi reports `sdd-proposal`
- WHEN normalized
- THEN the result is canonical `sdd-propose`, and translating back yields `sdd-proposal`

#### Scenario: Pi-only phase maps deterministically

- GIVEN Pi reports `sdd-status` or `sdd-sync`
- WHEN normalized
- THEN the result is the deterministic canonical phase from `PHASE_ALIASES` — not an error, not passthrough

### Requirement: Engine-agnostic ModelBinding

The contract MUST define one `ModelBinding` shape with optional fields (`providerId`, `modelId`, `effort`, `model`); each adapter consumes only the fields its engine needs. Status responses MUST expose per-engine assignments under one engine-agnostic shape.

#### Scenario: Binding round-trips per engine

- GIVEN a `ModelBinding` for any engine
- WHEN written via that engine's adapter and read back
- THEN the binding's engine-relevant fields are preserved

### Requirement: resolveEngine(engineId)

The contract MUST expose `resolveEngine(engineId)` returning the adapter bound to that engine's native config. The engine id domain is exactly `pi`, `opencode`, `claude`. The contract MAY accept documented aliases (e.g. `pi-cli`) by normalizing to canonical, and MUST reject unknown ids with an explicit error — never silently falling through to OpenCode.

#### Scenario: Each engine resolves

- WHEN `resolveEngine` is called with `pi`, `opencode`, or `claude`
- THEN the corresponding adapter is returned

#### Scenario: Unknown engine rejected

- GIVEN `engineId` is `copilot`
- WHEN `resolveEngine` is called
- THEN an explicit unknown-engine error is returned and no config file is touched

### Requirement: index.mjs routing boundary

`index.mjs` MUST keep HTTP routing and request parsing only; engine and assignment logic MUST delegate through `resolveEngine`. The net line count of `index.mjs` MUST NOT grow.

#### Scenario: Routing delegates

- GIVEN a models/update or profile-apply request
- WHEN handled
- THEN `index.mjs` parses and delegates, with no engine-specific assignment read/write logic inline
