# Tasks: Session Listing Accuracy and Performance Fix

## Review Workload Forecast

| Field | Value |
|---|---|
| Estimated changed lines | PR1 ~300, PR2 ~350, PR3 ~570, PR4 ~235, PR5 ~120, PR6 ~150 (≈1725 total) |
| 400-line budget risk | High (PR3 alone exceeds 400) |
| Chained PRs recommended | Yes |
| Suggested split | PR1→PR2→PR3 (server), PR4→PR5→PR6 (app) — stacked-to-main |
| Delivery strategy | auto-chain |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

Note: session context sets `review_budget_lines: 800`; against that override only PR3 (~570) is Medium-High, others Low. See Risks.

### Suggested Work Units

| Unit | Goal | PR | Focused test | Runtime harness | Rollback boundary |
|---|---|---|---|---|---|
| 1 | SQL escaping + cursor + k-way merge, `lib/sessions.mjs` | PR1 | `node --test idupi-server/test/sessions.test.mjs` | N/A — pure module, not yet wired | delete `lib/sessions.mjs` + test file |
| 2 | Claude/Pi match+order + `resolveOpenCodeExePath` + index build | PR2 | same | N/A — still unwired | revert PR2 commit; PR1 unaffected |
| 3 | Wire `index.mjs` scan/route/`/counts`, async execFile, logging | PR3 | `node --check idupi-server/index.mjs`; test cmd above | `scratch/` manual: list sessions+counts per engine, real project | revert PR3 commit |
| 4 | `IduPiClient`/`SessionItem`/`SessionsPage`/`RealIduPiClient`/`FakeIduPiClient`/`SessionsViewModelTest` | PR4 | `./gradlew :app:testDebugUnitTest` | N/A — unit-tested | revert PR4 commit |
| 5 | `SessionsViewModel` counts+selectEngine+loadMore | PR5 | same | N/A | revert PR5 commit |
| 6 | `SessionsScreen` wiring + `SessionCard` cleanup | PR6 | same | Manual: open Sessions screen, verify 1 request; chips fetch per engine | revert PR6 commit |

## Phase 1: Server — Cursor/Merge/SQL Core (PR1, base: main)

- [x] 1.1 `lib/sessions.mjs`: `escapeSqlValue()`/`validateNumeric()` (quote-double, `;`-reject, NUL-reject, numeric type/range) + RED test first (Threat Matrix: SQL string construction)
- [x] 1.2 `encodeCursor()`/`decodeCursor()` + tests: round-trip, combined per-engine object, `"done"` sentinel, frozen wire keys `pi-cli`/`opencode`/`claude`, `(ts,id)` tiebreaker
- [x] 1.3 `mergePage()`: 4-case exhaustion rule in priority order (fail→unchanged; `n<limit&&m==n`→done; `m≥1`→last-emitted watermark; `m==0`→unchanged) + property test, 3+ pages, no-gaps/no-dupes incl. 9-Claude-vs-183-OpenCode `n=9,m=2` counterexample

## Phase 2: Server — Match/Order/Index (PR2, base: PR1)

- [x] 2.1 `orderClaude()` — `statSync(file).mtimeMs`, zero content + test asserting no `readFileSync` call
- [x] 2.2 `matchOrderPi()` — wrapper-strip dir pre-filter + 8KB head read, `Date.parse` ISO→epoch ms w/ `NaN`→`stat.mtime` fallback, per-file escalation log on miss + tests
- [x] 2.3 `resolveOpenCodeExePath()` — cache, lazy re-resolve, regex-parse shim + RED test against verbatim captured shim content (Threat Matrix: external shim/binary resolution)
- [x] 2.4 `buildIndex()` per `(project,engine)`: `{id,timestamp,filePath}`, freshness token (`max(mtimeMs)`/`MAX(time_updated)`)
- [x] 2.5 Update `openspec/config.yaml` `node_server.test_command` → `node --test idupi-server/test/sessions.test.mjs`

## Phase 3: Server — Wire index.mjs (PR3, base: PR2)

- [x] 3.1 Replace `getProjectSessions` (`index.mjs:829-1047`) with per-engine scan via `lib/sessions.mjs`; remove dead `sessionsCacheMap`
- [x] 3.2 RED test/manual check: argv-array, no shell, no `execSync` (Threat Matrix: shell command construction + process blocking) — GREEN: `execFile(resolveOpenCodeExePath(), argv, {timeout:4000})`
- [x] 3.3 Add structured `console.error("[<Engine> Sessions Scan Error]", ...)` to silent Claude/Pi/OpenCode `catch{}` blocks
- [x] 3.4 Update `/api/v1/sessions` route (`index.mjs:1856-1868`): `engine`/`cursor`/`limit`, `offset=0` first-page alias only, one envelope (`sessions`,`nextCursor`,`partial`,`failures`), 502 on per-engine failure
- [x] 3.5 Add `GET /api/v1/sessions/counts` route — one shape (`counts`,`partial`,`failures`), omit failed-engine keys, never `0`
- [x] 3.6 Manual smoke script in `scratch/`: list sessions+counts per engine for a real project; `node --check idupi-server/index.mjs`

## Phase 4: Android — Domain + Client (PR4, base: main)

- [x] 4.1 RED: update `SessionsViewModelTest.kt` `sampleSession()`/`fake.sessionsToReturn` to `SessionsPage` shape + `messageCount: Int?`
- [x] 4.2 Create `SessionsPage.kt`: `SessionsPage`, `SessionEngineFailure`, `SessionCountsResponse`, `SessionCounts` (`@SerialName("pi-cli")`, all fields nullable)
- [x] 4.3 Modify `SessionItem.kt`: `messageCount: Int` → `Int?`
- [x] 4.4 Modify `IduPiClient.kt:38`: `getSessions(engine="all", cursor=null, limit=30): SessionsPage`; add `getSessionCounts(): SessionCountsResponse`
- [x] 4.5 GREEN: update `FakeIduPiClient.kt:113` to new signature/return type + counts fake
- [x] 4.6 GREEN: `RealIduPiClient.kt:214-215` — build query string, deserialize `SessionsPage`, implement `getSessionCounts()` against `/api/v1/sessions/counts`
- [x] 4.7 Run `./gradlew :app:testDebugUnitTest` — confirm existing 83 tests still compile/pass

## Phase 5: Android — ViewModel (PR5, base: PR4)

- [x] 5.1 RED: `SessionsViewModel` tests for `counts` StateFlow, `selectEngine()`, `loadMore()`
- [x] 5.2 GREEN `SessionsViewModel.kt`: add `counts` StateFlow, network-based `selectEngine()`/`loadMore()`; keep `init{}` as sole trigger

## Phase 6: Android — Screen Wiring (PR6, base: PR5)

- [x] 6.1 Modify `SessionsScreen.kt`: remove duplicate `LaunchedEffect(Unit)`; chips call `selectEngine()`; chip badges read `counts` (omit badge for absent/failed engine key); scroll-triggered `loadMore()`; local `SessionCard` badge renders only when `messageCount != null`
- [x] 6.2 Modify `SessionCard.kt:91`: apply same null-omits-badge rule to the dead-code duplicate composable
- [x] 6.3 Run `./gradlew :app:testDebugUnitTest` + manual: open Sessions screen once → exactly 1 `[Sessions DB] Cargadas` line; manual refresh → exactly 1 more request

## PR6 Independent-Gate Correction (post-PR6, this apply batch)

- [x] C1 Stale counts resurrection: guard `loadCounts` success commit (`_counts`/`_countsPartial`) with `requestId == firstPageRequestId` so an older counts success cannot overwrite a newer failure-cleared (null/unknown) state.
- [x] C2 Obsolete refresh after engine selection: capture `engine` synchronously in `refreshSessions()` and re-check `requestId == firstPageRequestId` before `fetchFirstPage`; a superseded refresh issues NO `getSessions` request.
- [x] C3 Deterministic RED tests added to `SessionsViewModelTest.kt` (deferred `countsHandlers` added to `FakeIduPiClient`) covering C1 (older counts success after newer counts failure) and C2 (delayed counts + request history after `selectEngine` supersede).
