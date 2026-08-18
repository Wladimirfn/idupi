# Proposal: Session Listing Accuracy and Performance Fix

## Intent

`GET /api/v1/sessions` merges all three CLI engines into one array, sorts by
recency globally, then slices to `limit` — so pagination competes across
engines instead of being bounded per engine. This single root produces two
symptoms: ~4-minute screen loads (full-file reads, fake pagination, dead
cache, blocking OpenCode calls) and Claude showing 2 of 8 sessions on
high-volume projects (150 global slots exhausted by other engines before
Claude's slice is reached). Both worsen as OpenCode's session volume grows.
Fix now, at the shared root, not per symptom.

## Scope

### In Scope
- **Batch A** (`idupi-server`): per-engine bounded scan, header-only reads,
  real pagination at the source, 15-second TTL in-memory cache, per-engine slot
  allocation. Closes H1, H2, H3, and the 8→2 undercount — one root. Ships
  with a new `node:test` suite (zero new deps) for matching/pagination/
  allocation logic.
- **Batch B** (`idupi-server`): move the OpenCode query off `execSync` to a
  non-blocking call. Closes H6.
- **Batch C** (`idupi-server`): delete/repair the dead Pi no-`cwd` fallback
  match; add structured logging to the silent Claude/OpenCode `catch {}`
  scan blocks.
- **Batch D** (`app/`): collapse `SessionsViewModel.init{}` and
  `SessionsScreen`'s `LaunchedEffect(Unit)` into one initial-load trigger;
  keep the manual refresh button.

### Out of Scope
- Grouping Pi's `parentSession`-chained per-hop files into one logical
  conversation — a product/UX decision, not a bug fix; tracked as an open
  question for a separate future change.
- The 1170/1268 per-project totals — confirmed genuine (matches server log
  and manual counts exactly), no fix needed.
- A persisted SQLite/JSON session index — rejected per exploration; grows
  the system for a problem the in-memory TTL approach already solves at
  current scale.

## Capabilities

### New Capabilities
- `session-listing-pagination`: per-engine bounded scan, header-only reads,
  real source-level pagination, 15-second TTL cache for `GET /api/v1/sessions`
- `session-listing-nonblocking-scan`: non-blocking OpenCode query
- `session-listing-robustness`: dead-code removal and failure visibility for
  the three engine scans
- `sessions-screen-single-refresh`: single initial-load trigger on the
  Android Sessions screen

### Modified Capabilities
None — no prior specs exist for this feature area.

## Approach

Endorse the exploration's recommendation: bound and allocate scan/parse work
per engine (never merge-then-slice globally), read only what is needed to
match and preview instead of whole files, and cache scan results for 15
seconds. The owner explicitly ratifies this TTL behavior: listings and counts
may be stale by up to 15 seconds, and indexes rebuild periodically after TTL
expiry even when source files have not changed. Each rebuild uses bounded units;
its measured cold cost is 527 ms, an 88x reduction from the 46,392 ms baseline.
Batches are ordered by dependency but each is independently revertible
("parte por parte"); B and C may reorder freely.
With an 800-line review budget, A and B could be reviewed together if a
reviewer prefers fewer, larger slices.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `idupi-server/index.mjs:833-1047` | Modified | Per-engine scan/pagination/cache rewrite (A); async OpenCode call (B); dead-code removal + logging (C) |
| `idupi-server/` (new test files) | New | `node:test` suite for matching/pagination/allocation |
| `app/.../viewmodel/SessionsViewModel.kt` | Modified | Remove duplicate trigger (D) |
| `app/.../ui/screens/SessionsScreen.kt` | Modified | Remove duplicate trigger (D) |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| TTL cache serves recently changed data | Low | State the up-to-15-second freshness window explicitly; periodic bounded rebuilds limit exposure |
| Per-engine allocation changes result ordering vs. today's global recency sort | Med | Define the allocation rule (e.g., proportional/round-robin) in spec/design before implementation |
| Non-blocking OpenCode call changes error/timeout semantics | Low | Cover with `node:test`; preserve the existing timeout value |
| No git baseline to diff against | N/A (repo fact) | See Rollback Plan |

## Rollback Plan

The repo has zero commits — no pre-existing tracked baseline exists to
revert to. Each batch (A-D) lands as its own small, self-contained commit(s)
per `work-unit-commits`; the commit itself becomes the revertible unit the
moment it exists, via `git revert`. No feature flags are introduced (would
grow the system, failing the `systemic-issue-triage` over-engineering test).
If a batch regresses, revert only that batch's commit(s) — batches touch
disjoint code paths/files by design.

## Dependencies

None external. Batch A should land before or with B for full resolution of
the performance root cluster, but B is independently shippable.

## Success Criteria

- [ ] Claude shows all its sessions (not 2 of 8) on high-OpenCode-volume
      projects, verified against real per-engine counts
- [ ] Sessions screen load time drops materially from ~4 min (target:
      sub-second for a bounded first page)
- [ ] `node:test` suite exists and passes for idupi-server session-listing
      logic
- [ ] `./gradlew :app:testDebugUnitTest` still passes (83 tests) after Batch D
- [ ] Exactly 1 request fires per Sessions screen open (no duplicate
      `[Sessions DB] Cargadas` log lines)
