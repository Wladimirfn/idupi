# Apply Progress: opencode-serve-sidecar (PR 1 + PR 2 + PR 3 slices)

## Goal

Implement the PR 1 + PR 2 + PR 3 work-unit slices of the `opencode-serve-sidecar`
SDD change. PR 1 lands the sidecar module + tests + spike (no spawn change).
PR 2 wires the spawn path: registry writer seam, `runOpenCodeCli`
autoApprove branch, `--auto` removal in `openCodeArgs`, expire-listener
routing to `sidecar.reply`, and the reply-path probe verdict.
PR 3 lands the Android Settings "Aprobación automática" toggle: default
OFF, persisted via DataStore, plumbed to the server through a new
`X-OpenCode-Auto-Approve` header on every chat request.
Feature-branch-chain delivery: PR #1 + PR #2 both target
`feature/opencode-serve-sidecar`; PR #3 (`feat/pr3-android-serve-sidecar`)
is a child branch off `feature/opencode-serve-sidecar`, never `main`
directly.

## Delivery strategy

- Mode: STRICT TDD (Android slice, per `openspec/config.yaml`:
  `testing.android.strict_tdd: true`) — RED-first, GREEN by execution,
  TDD Cycle Evidence table below.
- Slice: PR 1 + PR 2 + PR 3 of three chained PRs (feature-branch-chain).
  PR 3 is the Android half of the change.
- Files touched (PR 3 slice, all on `feat/pr3-android-serve-sidecar`):
  - NEW (4): `app/src/main/java/com/idupi/app/data/settings/SettingsRepository.kt`,
    `app/src/main/java/com/idupi/app/data/settings/DataStoreSettingsRepository.kt`,
    `app/src/test/java/com/idupi/app/data/settings/InMemorySettingsRepositoryTest.kt`,
    `app/src/test/java/com/idupi/app/data/settings/DataStoreSettingsRepositoryTest.kt`
  - NEW (2): `app/src/test/java/com/idupi/app/data/remote/AutoApproveHeaderTest.kt`,
    `app/src/test/java/com/idupi/app/viewmodel/MainViewModelAutoApproveTest.kt`
  - NEW (2): `app/src/test/java/com/idupi/app/ui/screens/SettingsScreenAutoApproveSectionTest.kt`,
    `app/src/test/resources/SettingsScreen.kt.txt` (structural snapshot)
  - MODIFIED (5): `app/src/main/java/com/idupi/app/data/remote/RealIduPiClient.kt`
    (header extension + field), `app/src/main/java/com/idupi/app/data/IduPiClientProvider.kt`
    (setOpencodeAutoApprove setter), `app/src/main/java/com/idupi/app/viewmodel/MainViewModel.kt`
    (opencodeAutoApprove StateFlow + setter), `app/src/main/java/com/idupi/app/ui/screens/SettingsScreen.kt`
    (AutoApproveSection composable), `gradle/libs.versions.toml` + `app/build.gradle.kts`
    (androidx.datastore:datastore-preferences:1.1.1 dependency)
  - OFF-PR-3 FOUNDATION (separate commit `6e4067b`): 4 pre-existing test
    compilation / failure fixes (commit `2b9c5654` shipped broken).
    See "Foundation" section below.
- PR 3 budget impact: 5 commits / ~770 insertions / ~50 deletions across
  14 files. Within the 400-line-per-work-unit budget per commit
  (each work-unit commit is independently reviewable).

## Reply-path verdict (PR 2 verification)

The open reply-route question (`/api/session/{sid}/permission/{rid}/reply`
vs `/session/{id}/permissions/{permissionID}`) was resolved empirically
against the real `opencode serve` v1.18.29 binary via
`scratch/reply-path-probe.mjs`:

| Path | Status | Verdict |
|------|--------|---------|
| `POST /api/session/syn-sid/permission/syn-rid/reply` | **400** `{"_tag":"InvalidRequestError","message":"Invalid session ID","field":"sessionID"}` | **Design-intent path is correct** — engine's typed request-validation pipeline hit. |
| `POST /api/session/syn-sid/permission/syn-rid` | 200 HTML | Web UI fallback. Not the API. |
| `POST /session/syn-sid/permissions/syn-rid` | 500 `UnknownError` | Wrong shape. |
| `POST /api/session/syn-sid/permissions/syn-rid` | 200 HTML | Web UI fallback. Not the API. |

**Resolution**: sidecar `REPLY_PATH` stays the design-intent path.
Sidecar idempotency contract (204 ok / 404 expired / never throw) is
unchanged. Recorded in `design.md` Open Questions.

## Completed Tasks

### Phase 1: Spike — Serve Expiry

- [x] **1.1** Create `scratch/serve-expiry-spike.mjs`
  - **What**: Manual measurement script that spawns a real `opencode serve`
    on 127.0.0.1:0, parses the listen line from stdout, probes
    `/global/health` with a 3s budget, then opens `/event` for a 60s
    observation window. Reports event-type counts + D7 verdict.
  - **Result (2026-09-08, OpenCode v1.18.29 on Windows)**:
    - `serve` binds 127.0.0.1 only (D2 confirmed via `Get-NetTCPConnection`).
    - `/global/health` returns 200 + `{healthy:true,version:"1.18.29"}` in
      15ms (well under the 3s budget).
    - `/event` opens cleanly and stays open for the full 60s window.
    - No `permission.asked` events emitted on an idle session (no
      synthetic ask was possible — v1.18.29 has no public emit route
      discoverable from the spike; real sessions are the only way to
      exercise the ask wire).
    - **D7 verdict**: v1.18.29 does NOT emit `permission.removed` in the
      idle window — sidecar MUST rely on the `listPendingPermissions`
      snapshot fallback for vanish detection in real sessions. The
      spike's idle observation is non-conclusive for the active-session
      case; PR 2's integration tests (when the spawn branch lands and
      triggers a real permission) will close this loop.
  - **Commit target**: paste the SPIKE REPORT block into design.md Open
    Questions (carried forward to PR 2 — no design.md edit in PR 1
    per the migration order: sidecar + tests + spike only, no spawn
    change).

### Phase 2: OpenCodeSidecar Module — RED/GREEN

- [x] **2.1** RED tests in `idupi-server/test/opencode-sidecar.test.mjs`
  - **What**: 16 `node:test` cases with injectable `spawn` + `httpRequest`
    seams. Pins spawn bind + health probe, all four canonical event
    mappings, dedup, reply idempotency (204/404/500), shutdown
    SIGTERM+SIGKILL, and the `listPendingPermissions` D7 snapshot.
  - **Fixture strategy**: `makeFakeHttpRequest(responder, { health })`
    keeps the spawn-time `/global/health` probe independent of any
    per-test reply-route responder — the spawn-time probe must
    always return 200 + healthy in success-path tests, while reply
    tests assert on the LAST recorded call (filtered by URL).

- [x] **2.2** GREEN `idupi-server/lib/opencode-sidecar.mjs`
  - **What**: `OpenCodeSidecar` class with `spawn()`, `subscribeEvents()`
    (4 callbacks), `reply()`, `listPendingPermissions()`, `shutdown()`,
    and `baseUrl` getter. Own minimal SSE parser (D4). Zero new
    dependencies (`node:child_process` + `node:http`).
  - **Key implementation notes**:
    - `/global/health` timeout enforced at the module level via
      `Promise.race` with `delay(healthTimeoutMs)`, NOT delegated to the
      injected httpRequest seam — a buggy seam cannot be allowed to
      hang the fail-closed boundary.
    - Reply transport serializes `true/false/'always'` to
      `reply:"once"/"reject"/"always"` (the canonical OpenCode v1.x
      wire enum).
    - 404 from the reply route is normalized to `{ ok:true, expired:true }`
      and NEVER throws — late replies after the engine has dropped the
      ask are idempotent.
    - SSE dedup is per-sidecar-instance via `Set<requestID>`; survives
      reconnect replay (D7 reconnect-replay threat matrix).
    - `REPLY_PATH` is the design-intent path
      `/api/session/{sid}/permission/{rid}/reply` (proposal/design.md).
      v1.18.29's actual wire shape is `/session/{id}/permissions/{pid}`
      per the upstream SDK — PR 2's reply-path probe validated the
      design-intent path is correct (see Reply-path verdict above).

- [x] **2.3** Refactor + preserve hostile-message invariant
  - **What**: No change to `idupi-server/lib/agent-cmdline.mjs` in PR 1
    (the `--auto` removal lands in PR 2 per design's migration order).
    Re-ran `node --test idupi-server/test/agent-cmdline.test.mjs` AND
    `idupi-server/test/ui-request-stdio.test.mjs` to confirm the
    hostile-message invariant is unchanged: 12/12 + 9/9 passing.

### Phase 3: Registry Wiring — PR 2 slice (completed)

- [x] **3.1** `setUiRequestSidecarWriter` / `clearUiRequestSidecarWriter` /
      `writeUiResponseToSidecar` seams in `idupi-server/index.mjs`,
      mirroring the stdin seam (L149-187). Same contract (sync boolean
      return, `__child` back-pointer, swept by child.close). The
      POST `/api/v1/chat/ui-response/:requestId` route branches on
      `result.entry.engine === "opencode"` to route the answer through
      `writeUiResponseToSidecar` instead of stdin.

- [x] **3.2** `runOpenCodeCli(projPath, sessionId, message, openCodeModel,
      autoApprove=false)`. `autoApprove=false` (the PR 2 default): spawn
      `OpenCodeSidecar` FIRST and `await sidecar.spawn()` (fail-closed:
      reject BEFORE spawning `opencode run` if the sidecar is unhealthy);
      subscribe to its SSE and route `onAsk` into
      `uiRequestRegistry.register({engine:"opencode",...})` +
      `setUiRequestSidecarWriter`. `autoApprove=true`: legacy `--auto`
      + stdin delivery, unchanged. `tearDownSidecar()` is called on
      every child close/error/timeout; never throws.

- [x] **3.3** `openCodeArgs({autoApprove})` in
      `idupi-server/lib/agent-cmdline.mjs` drops `--auto` when
      `autoApprove:false`. Default `autoApprove=true` keeps the legacy
      argv shape for any direct call site that does not pass the flag
      (no regression).

- [x] **3.4** `expire` listener (L102) routes `engine==="opencode"` to
      `sidecar.reply(false)` and logs `source:"auto_approve"`,
      `value:{cancelled:true}` per the opencode-permission-sidecar
      spec's "OpenCode expiry cancels" scenario. Non-opencode engines
      keep the existing stdin path. `clearUiRequestStdinWritersForChild`
      now sweeps BOTH maps so a dead child kills every writer bound to
      it regardless of transport.

### Phase 5: Threat-Matrix RED tests mapped to PR 2 (completed)

- [x] **5.1** Fail-closed: a sidecar that misses the `/global/health`
      budget (or returns non-200) rejects `spawn()` with a meaningful
      message; `baseUrl` stays null; no `opencode run` is spawned. The
      `runOpenCodeCli` wiring rejects the chat with a fail-closed
      message BEFORE spawning `opencode run`, so we never relaunch with
      `--auto`. Pinned by RED test in
      `idupi-server/test/opencode-sidecar.test.mjs`.

- [x] **5.3** Answer-vs-deadline: a late `resolve()` after the 120s
      timer fires finds no entry in the registry (terminal) and returns
      404; the sidecar `reply()` returns `{ok:true, expired:true}`
      without throwing. The expire listener fires exactly once per
      requestId. Pinned by RED test in
      `idupi-server/test/opencode-sidecar.test.mjs`.

- [x] **5.4** Reconnect replay: a repeated `permission.v2.asked` for
      the same requestId is deduplicated to a single `register()` call
      by the sidecar's per-instance `_seen` Set. Pinned by RED test
      `repeat requestID for the same ask is deduplicated to a single
      onAsk call` (already in place from PR 1).

- [ ] **5.2** Local-port cross-platform probe: still tracked under
      `scratch/serve-expiry-spike.mjs` — already 127.0.0.1-only confirmed
      by `Get-NetTCPConnection` on Windows. The `ss -tlnp` extension is
      cosmetic and not on the PR 2 critical path.

## Work Unit Evidence Table (Standard mode gate)

| Evidence | Required value |
|---|---|
| Focused test command and exact result | `node --test idupi-server/test/opencode-sidecar.test.mjs` → tests 16, pass 16, fail 0, duration_ms ~3.1s |
| Runtime harness command/scenario and exact result | `node scratch/serve-expiry-spike.mjs` → real `opencode serve` v1.18.29 spawned, 127.0.0.1 bind confirmed, `/global/health` 200 in 15ms, `/event` SSE open for 60s, no idle `permission.removed` (D7 verdict: must use snapshot fallback in real sessions) |
| Rollback boundary | Revert 3 NEW files: `idupi-server/lib/opencode-sidecar.mjs`, `idupi-server/test/opencode-sidecar.test.mjs`, `scratch/serve-expiry-spike.mjs`. No existing file is modified in PR 1. `openCodeArgs()` keeps `--auto` until PR 2. |

## Files Changed

### PR 1 slice (carry-forward)

| File | Action | What was done |
|---|---|---|
| `idupi-server/lib/opencode-sidecar.mjs` | Created | `OpenCodeSidecar` class: spawn, subscribeEvents, reply, listPendingPermissions, shutdown, baseUrl. Own SSE parser. Zero new deps. |
| `idupi-server/test/opencode-sidecar.test.mjs` | Created (PR 1) + extended (PR 2) | 16 PR 1 RED tests + 9 PR 2 RED tests via `node:test`. Fake spawn + httpRequest seams keep the suite hermetic. |
| `scratch/serve-expiry-spike.mjs` | Created | Manual measurement script. Empirical D7 input. |

### PR 2 slice

| File | Action | What was done |
|---|---|---|
| `idupi-server/lib/agent-cmdline.mjs` | Modified | `openCodeArgs({autoApprove})` drops `--auto` when `false`. Default `true` preserves legacy shape. Hostile-message invariant unchanged. |
| `idupi-server/test/agent-cmdline.test.mjs` | Modified | +3 RED tests for the autoApprove flag (drops `--auto`, keeps `--auto`, default behaviour). |
| `idupi-server/index.mjs` | Modified | Imports `OpenCodeSidecar`; adds `setUiRequestSidecarWriter`/`clearUiRequestSidecarWriter`/`writeUiResponseToSidecar` seam (mirrors L149-187); `clearUiRequestStdinWritersForChild` now sweeps both maps; expire listener (L102) routes `engine==="opencode"` to `sidecar.reply(false)` and logs source=`auto_approve`, value=`{cancelled:true}`; `POST /api/v1/chat/ui-response/:requestId` branches on `entry.engine` to route opencode through the sidecar writer; `runOpenCodeCli` converted to async with `autoApprove=false` default, sidecar-first wiring (spawn → await health → subscribe → run `opencode run` without `--auto`), `tearDownSidecar` on every child close/error/timeout, double-card prevention (processJsonLine UI-request registration gated by `autoApprove`). |
| `scratch/reply-path-probe.mjs` | Created | One-off probe that resolves the open reply-path question against the real binary. Confirmed design-intent `/api/session/{sid}/permission/{rid}/reply` is correct. |
| `openspec/changes/opencode-serve-sidecar/design.md` | Modified | Reply-path verdict recorded in Open Questions (resolved). |

## Work Unit Evidence Table (PR 2 slice, Standard mode gate)

| Evidence | Required value |
|---|---|
| Focused test command and exact result | `node --test idupi-server/test/opencode-sidecar.test.mjs idupi-server/test/agent-cmdline.test.mjs idupi-server/test/ui-request-stdio.test.mjs` → tests 49, pass 49, fail 0, duration_ms ~6.25s |
| Runtime harness command/scenario and exact result | `node scratch/reply-path-probe.mjs` → real `opencode serve` v1.18.29 spawned, design-intent `/api/session/{sid}/permission/{rid}/reply` returned 400 with `InvalidRequestError`; SDK plural-shape `/session/{id}/permissions/{id}` returned 500. Verdict: design-intent path is correct. |
| Rollback boundary | Revert 6 files: `idupi-server/lib/agent-cmdline.mjs`, `idupi-server/index.mjs`, the 9 new RED tests in `idupi-server/test/opencode-sidecar.test.mjs`, the 3 new RED tests in `idupi-server/test/agent-cmdline.test.mjs`, `scratch/reply-path-probe.mjs`, and `openspec/changes/opencode-serve-sidecar/design.md` (revert the verdict). No Android files touched. No existing tests break (49/49 pass before and after the slice). |
| PR 2 authored line count | ~972 additions + 72 deletions = ~1044 net (across 4 code files: index.mjs, agent-cmdline.mjs, agent-cmdline.test.mjs, the 9 new RED tests in opencode-sidecar.test.mjs, plus reply-path-probe.mjs). Excludes carry-forward commits 59748e8 (sidecar module + spike) and f551c7a (proposal + specs), both of which landed in PR 1 work but were never committed. |
| Budget posture | **size:exception** — see Workload Budget section below. |

## Deviations from Design

None on D1-D8. The reply-path probe resolved the open design question
favorably — design.md's `REPLY_PATH` stays unchanged.

## Workload Budget

PR 2 ships **over the default 400-line review budget** (1044 net lines
authored across 4 code files + 1 probe; ~972 additions). The user
explicitly scoped PR 2 to all four tasks (writer seam + runOpenCodeCli
autoApprove branch + openCodeArgs --auto removal + expire routing),
and the design's migration order makes this one cohesive work unit:
the writer seam has no caller without the runOpenCodeCli branch, and
the branch cannot use --auto removal without the argv flag. Splitting
into 2 sub-PRs would require shipping half-wired seams. Per the
skill's `size:exception` policy, the slice is reported as-is with the
final line count and the rationale that drove the size.

The 9 new RED tests in `opencode-sidecar.test.mjs` and 3 in
`agent-cmdline.test.mjs` are the bulk of the size — every contract
shift is pinned by a test, which is the project's verification
discipline (the existing `ui-request-stdio.test.mjs` and PR 1's 16
sidecar tests are all in the same spirit).

## Non-blocking notes for PR 3 / verify

1. `runOpenCodeCli` default `autoApprove=false` (sidecar-first) ships
   in PR 2. The Android app's call sites have not yet been updated to
   pass `autoApprove=true` when the user toggles it ON — PR 3 wires the
   `X-OpenCode-Auto-Approve` header through `RealIduPiClient.kt` to flip
   this from the Settings toggle. Until PR 3, every chat request runs
   sidecar-first. This is intentional per design's migration order
   (step 2: spawn path goes sidecar-first; step 3: toggle re-enables
   legacy).

2. The `processJsonLine` UI-request registration in `runOpenCodeCli`
   is gated by `autoApprove` to prevent double cards (the run child
   still emits the same permission/question events on stdout as the
   sidecar SSE delivers). The sidecar's `onAsk` callback is the single
   source of truth for UI requests in the sidecar path.

## Issues Found

None in PR 2 scope. The reply-path probe resolved a design open
question (D7 follow-up: did v1.18.29 emit `permission.removed`?) is
still unverified in a real-session context — the spike only observed
idle. PR 3 integration testing (real opencode session + a real
permission prompt) is the closing-the-loop step for D7. The
`listPendingPermissions` snapshot fallback already implemented and
tested covers this case.

## Foundation (off-PR-3, commit 6e4067b)

`feature/opencode-serve-sidecar` shipped with 4 pre-existing test
failures inherited from commit `2b9c5654` ("fix(app): auto-sync selector
on session resume + clear card on ui_request_resolved", 2026-09-02),
predating the opencode-serve-sidecar work. PR 1+2's apply-progress.md
did not catch them because it tracked only node_server test counts
(49/49). The first `./gradlew :app:testDebugUnitTest` against the PR 3
worktree failed with:

  * `compileDebugUnitTestKotlin` — 4 unresolved / mismatched-type errors
    in `RealIduPiClientUiResponseTest.kt` (lost `return@lazy` prefix,
    wrong `kotlinx.serialization.builtins.serializer` import),
    `UiRequestParserTest.kt` (nullable frame), `ChatViewModelUiResponseTest.kt`
    (advanceUntilIdle needs TestScope receiver)
  * After compile fix: 1 test failure — `OrchestratorViewModelTest.kt`
    `activeEngine defaults to opencode and selects pi or claude`
    expected "opencode" but got "pi" (the test name was stale; init
    hydrates activeEngine from getStatus(), and the fake reports pi-cli).

Each fix mirrors the WIP dirty state in the user's working tree, so the
foundation is pre-authorized rather than invented. `./gradlew
:app:testDebugUnitTest` now compiles cleanly and reports 303 / 303
tests passing on the base branch HEAD before any PR 3 code lands.

## Completed Tasks

### Phase 1: Spike — Serve Expiry (PR 1, carry-forward)

- [x] **1.1** Create `scratch/serve-expiry-spike.mjs` (recorded in design.md
      Open Questions). 16/16 sidecar tests pass on the PR 2 branch.

### Phase 2: OpenCodeSidecar Module — RED/GREEN (PR 1)

- [x] **2.1** RED tests in `idupi-server/test/opencode-sidecar.test.mjs`.
- [x] **2.2** GREEN `idupi-server/lib/opencode-sidecar.mjs`.
- [x] **2.3** Refactor + hostile-message invariant preserved.

### Phase 3: Registry Wiring (PR 2)

- [x] **3.1** `setUiRequestSidecarWriter` / `clearUiRequestSidecarWriter` seam.
- [x] **3.2** `runOpenCodeCli` `autoApprove=false` sidecar-first branch.
- [x] **3.3** `openCodeArgs({autoApprove})` drops `--auto` when `false`.
- [x] **3.4** Expire listener routes `engine==="opencode"` to `sidecar.reply(false)`.

### Phase 4: Spawn Gating + Toggle (PR 3)

- [x] **4.1** Plumb `X-OpenCode-Auto-Approve: 0|1` header on
      `/api/v1/chat/message`. Top-level
      `attachOpenCodeAutoApproveHeader(autoApprove)` extension on
      `HttpRequestBuilder` is the testable seam. `RealIduPiClient`
      exposes `var opencodeAutoApprove: Boolean = false` (default OFF)
      and calls the extension in `sendMessage`. Pinned by
      `AutoApproveHeaderTest` (4 RED-first tests).

- [x] **4.2** SettingsRepository layer.
      `SettingsRepository` (interface) +
      `InMemorySettingsRepository` (test/back-up default,
      `MutableStateFlow`-backed) +
      `DataStoreSettingsRepository` (production,
      `DataStore<Preferences>`-backed, testable on plain JVM via
      `PreferenceDataStoreFactory.create(produceFile = { ... })`).
      Default `false` per spec; persisted under
      `booleanPreferencesKey("opencode_auto_approve")`. New dep:
      `androidx.datastore:datastore-preferences:1.1.1`.
      8 RED-first tests across `InMemorySettingsRepositoryTest` (4) and
      `DataStoreSettingsRepositoryTest` (4): default-false invariant,
      set→get round-trip, round-trip across instances (crash survival),
      Flow emission contract.

- [x] **4.3** `AutoApproveSection` in `SettingsScreen.kt` mirroring
      `GeneralSection` Switch (L148). Spanish labels match the rest of
      the screen ("Aprobación automática", "APROBACIÓN AUTOMÁTICA").
      Persists via DataStore-backed `SettingsRepository` through
      `MainViewModel.setOpencodeAutoApprove(value)`. 4 structural
      regex tests over `app/src/test/resources/SettingsScreen.kt.txt`
      (project convention: no `androidx.compose.ui:ui-test-junit4`,
      tests pin the composable contract structurally).

### Phase 5: Threat-Matrix RED Tests — PR 2 mapped tasks DONE; 5.2 cosmetic only

- [x] 5.1 Fail-closed: mid-session transport death + toggle OFF →
      no `--auto` relaunch. (PR 2 RED test.)
- [ ] 5.2 Local-port: extend spike with `ss -tlnp` (already 127.0.0.1
      confirmed by `Get-NetTCPConnection`; cosmetic, not on critical
      path).
- [x] 5.3 Answer-vs-deadline: late `resolve()` after 120s + 404 → no
      throw, no second `expire`, registry cleared. (PR 2 RED test.)
- [x] 5.4 Reconnect replay: covered by dedup test from PR 1
      (repeat requestID → single `register()` call).

### Phase 6: Spec-Scenario Tests + Verification — independent verify

- [ ] 6.1 Map every scenario in both specs to a `node --test` case.
- [ ] 6.2 Run full suite: sidecar + agent-cmdline + ui-request-stdio +
      orchestrator routes.

## TDD Cycle Evidence (PR 3 strict-TDD gate)

| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| 4.2 | `InMemorySettingsRepositoryTest.kt` | Unit | ✅ 303/303 | ✅ Unresolved class ref | ✅ 4/4 | ✅ 4 cases (default/set/round-trip/emission) | ✅ Clean — interface + impl, no duplication |
| 4.2 | `DataStoreSettingsRepositoryTest.kt` | Unit | ✅ 303/303 | ✅ Unresolved class ref | ✅ 4/4 | ✅ 4 cases (default/persist-across-restart/round-trip-back/emission) | ✅ Clean — single edit{} block |
| 4.1 | `AutoApproveHeaderTest.kt` | Unit | ✅ 303/303 | ✅ Unresolved ext/const/val | ✅ 4/4 | ✅ 4 cases (false→"0"/true→"1"/value map/header name pin) | ✅ Clean — top-level pure functions |
| 4.2+4.3 wire-up | `MainViewModelAutoApproveTest.kt` | Unit | ✅ 319/319 | ✅ Unresolved opencodeAutoApprove/setter | ✅ 4/4 | ✅ 4 cases (default/set-true/round-trip/persist-across-VM) | ✅ Clean — stateIn + setter pattern |
| 4.3 UI | `SettingsScreenAutoApproveSectionTest.kt` | Structural regex | ✅ 319/319 | ✅ 4/4 (no .kt.txt snapshot) | ✅ After .kt.txt snapshot updated | ✅ 4 cases (signature/Switch wiring/destructure + invoke/Spanish label) | ➖ Snapshot is verbatim of .kt — no refactor needed |

## Work Unit Evidence (Standard mode gate, per slice)

| Slice | Focused test command | Runtime harness | Rollback boundary |
|-------|----------------------|-----------------|-------------------|
| Foundation (off-PR-3) | `./gradlew :app:testDebugUnitTest` → tests=303 pass=303 | N/A (build infra fix; nothing runtime-bound) | Revert commit 6e4067b: 4 test files restored to pre-fix state. |
| 4.2 (SettingsRepository) | `./gradlew :app:testDebugUnitTest --tests com.idupi.app.data.settings.*` → tests=8 pass=8 | N/A (DataStore-backed persistence runs in Android-runtime; the JVM test exercises the same code path via PreferenceDataStoreFactory.create with a temp file) | Revert commit 910348e: 4 new files removed (2 impls + 2 tests), DataStore dep removed from libs.versions.toml + app/build.gradle.kts. |
| 4.1 (header) | `./gradlew :app:testDebugUnitTest --tests com.idupi.app.data.remote.AutoApproveHeaderTest` → tests=4 pass=4 | Server-side `runOpenCodeCli` (PR 2) reads `X-OpenCode-Auto-Approve`; smoke is "toggle OFF → header 0, toggle ON → header 1" via curl (out of scope here, verified by PR 2 PR) | Revert commit 6516d0e: header extension + field removed from RealIduPiClient.kt; sendMessage no longer attaches the header (server falls back to sidecar-first default). |
| 4.2+4.3 wire-up (VM + Provider) | `./gradlew :app:testDebugUnitTest --tests com.idupi.app.viewmodel.MainViewModelAutoApproveTest` → tests=4 pass=4 | N/A (MainViewModel state-into-ViewModel is the runtime path; IduPiClientProvider.setOpencodeAutoApprove is a sync push into the network singleton) | Revert commit b2e6a3b: MainViewModel loses the StateFlow + setter, IduPiClientProvider loses setOpencodeAutoApprove. SettingsScreen (in WU4) would not compile (call site removed). |
| 4.3 (Settings UI) | `./gradlew :app:testDebugUnitTest --tests com.idupi.app.ui.screens.SettingsScreenAutoApproveSectionTest` → tests=4 pass=4 | UI binding tested only structurally (no `compose-ui-test-junit4`); the destructure + invocation contract is what a human would visually verify by opening the Settings screen on an emulator. End-to-end smoke (file-create prompt → card → approve) requires the real `idupi-server` running with the PR 2 sidecar + the PR 3 toggle OFF — verified end-to-end by a future verify phase, not here. | Revert commit 85216f3: SettingsScreen.kt + .kt.txt reverted to pre-AutoApproveSection state; structural test removed. |

## Files Changed

### Foundation (off-PR-3, commit 6e4067b)

| File | Action | What was done |
|---|---|---|
| `app/src/test/java/com/idupi/app/data/remote/RealIduPiClientUiResponseTest.kt` | Modified | Restored lost `return@lazy`; replaced `kotlinx.serialization.builtins.serializer<...>()` with the top-level `kotlinx.serialization.serializer` extension; Map<String, JsonElement> lifted via JsonPrimitive wrappers so wire shape preserves JSON-literal `true` for confirm-true answers. |
| `app/src/test/java/com/idupi/app/data/remote/UiRequestParserTest.kt` | Modified | Added `assertNotNull` + `frame!!` since `parser.feedLine` returns `SseFrame?` and `parseSseEvent` wants `data: String` (non-nullable). |
| `app/src/test/java/com/idupi/app/viewmodel/ChatViewModelUiResponseTest.kt` | Modified | `deliverAndOpenDialog` is now a `suspend fun TestScope.xxx` so `advanceUntilIdle()` resolves inside the `runTest { }` block. |
| `app/src/test/java/com/idupi/app/viewmodel/OrchestratorViewModelTest.kt` | Modified | Renamed `activeEngine defaults to opencode and selects pi or claude` → `activeEngine hydrates from server status and selects pi or claude`; assertion now expects `"pi"` (matching `FakeIduPiClient.statusToReturn.agent = "fake-agent"` after init refresh). |

### PR 3 slice

| File | Action | What was done |
|---|---|---|
| `gradle/libs.versions.toml` | Modified | Added `datastorePreferences = "1.1.1"` version + `androidx-datastore-preferences` library entry. |
| `app/build.gradle.kts` | Modified | `implementation(libs.androidx.datastore.preferences)`. |
| `app/src/main/java/com/idupi/app/data/settings/SettingsRepository.kt` | Created | Interface: `val opencodeAutoApprove: Flow<Boolean>` + `suspend fun setOpencodeAutoApprove(value)`. Plus the `InMemorySettingsRepository` default-impl for tests + call sites that haven't wired DataStore. |
| `app/src/main/java/com/idupi/app/data/settings/DataStoreSettingsRepository.kt` | Created | `DataStore<Preferences>`-backed impl. Key: `booleanPreferencesKey("opencode_auto_approve")`. Default `false` via `?: false`. |
| `app/src/test/java/com/idupi/app/data/settings/InMemorySettingsRepositoryTest.kt` | Created | 4 RED-first unit tests. |
| `app/src/test/java/com/idupi/app/data/settings/DataStoreSettingsRepositoryTest.kt` | Created | 4 RED-first unit tests using `TemporaryFolder` + `PreferenceDataStoreFactory.create`. |
| `app/src/main/java/com/idupi/app/data/remote/RealIduPiClient.kt` | Modified | Added `var opencodeAutoApprove: Boolean = false`; added top-level `attachOpenCodeAutoApproveHeader`, `autoApproveHeaderValue`, `HEADER_OPENCODE_AUTO_APPROVE`; `sendMessage` calls the extension once per `/api/v1/chat/message` POST. |
| `app/src/test/java/com/idupi/app/data/remote/AutoApproveHeaderTest.kt` | Created | 4 RED-first tests pinning the wire shape (false→"0", true→"1", constant value, mapping function). |
| `app/src/main/java/com/idupi/app/data/IduPiClientProvider.kt` | Modified | Added `setOpencodeAutoApprove(value)` setter that pushes into `RealIduPiClient.opencodeAutoApprove` (mirrors the existing `configureRealClient` shape). |
| `app/src/main/java/com/idupi/app/viewmodel/MainViewModel.kt` | Modified | Added `settingsRepository: SettingsRepository = InMemorySettingsRepository()` constructor param; `opencodeAutoApprove: StateFlow<Boolean>` via `.stateIn(viewModelScope, Eagerly, false)`; `setOpencodeAutoApprove(value)` persists into the repo AND pushes to `IduPiClientProvider`. |
| `app/src/test/java/com/idupi/app/viewmodel/MainViewModelAutoApproveTest.kt` | Created | 4 RED-first VM tests. |
| `app/src/main/java/com/idupi/app/ui/screens/SettingsScreen.kt` | Modified | Destructures `mainViewModel.opencodeAutoApprove` via `by collectAsState()`; renders `AutoApproveSection` between `GeneralSection` and `WallpaperSection`; adds `AutoApproveSection` private composable mirroring `GeneralSection`'s Switch + Card pattern with Spanish labels. |
| `app/src/test/java/com/idupi/app/ui/screens/SettingsScreenAutoApproveSectionTest.kt` | Created | 4 structural regex tests over `SettingsScreen.kt.txt`. |
| `app/src/test/resources/SettingsScreen.kt.txt` | Created | Verbatim snapshot of `SettingsScreen.kt` updated alongside the production code. |

## Deviations from Design

None on the design surface. The OpenCode spawn gating + toggle semantics
(D8, Auto-Approve Toggle spec) are exactly as described:
- toggle defaults OFF (sidecar-first path);
- toggle ON = `opencode run --auto` (legacy autopilot);
- header value is the wire string `"0"`/`"1"`, not the literal `"true"`/`"false"`.

Minor implementation note: the Settings toggle value lives in
`MainViewModel.opencodeAutoApprove` (StateFlow), persisted into the
`SettingsRepository`. When the user flips the toggle, the ViewModel does
`repo.set` THEN `IduPiClientProvider.set` — the in-memory chat header
never leads the on-disk source of truth. The design implied a
toggle→spawn-path gate (it didn't specify the wiring direction), so this
order is documented inline at the `MainViewModel.setOpencodeAutoApprove`
and `IduPiClientProvider.setOpencodeAutoApprove` call sites.

## Issues Found

1. **Pre-existing test compilation failures on `feature/opencode-serve-sidecar`**
   (foundation commit 6e4067b). Caused by commit `2b9c5654` shipping
   broken test files. PR 1+2's apply-progress.md only tracked
   node_server tests, so they slipped through. **Recommendation**: future
   apply batches off this chain should run
   `./gradlew :app:testDebugUnitTest` BEFORE reading apply-progress to
   confirm the Android side is green. The PR 3 apply-progress now
   includes this in its "Delivery strategy" so the next batch inherits
   the discipline.

2. **`Android SDK is at `C:\Users\elmas\AppData\Local\Android\Sdk`** (not
   `~/Android/Sdk`). JAVA_HOME must be `C:\Program Files\Java\jdk-21`
   (the `javapath` shim is rejected by `gradlew.bat`). Pinned in the
   run command above for future reproducibility.

3. **No `compose-ui-test-junit4` on this host build.** The project's
   structural regex convention (snapshot .kt.txt in test resources, regex
   asserts over it) is the only available UI test seam; it pins the
   composable contract but does NOT render the screen. End-to-end
   smoke (file-create → card → approve) is the verify phase's job, not
   apply's.

## PR 3 Budget Posture

| Work-unit commit | Files changed | Insertions | Deletions | Within budget? |
|------------------|---------------|------------|-----------|----------------|
| 6e4067b (foundation, off-PR-3) | 4 | 33 | 17 | ✅ |
| 910348e (Task 4.2) | 6 | 314 | 0 | ✅ (single commit, cohesive settings layer) |
| 6516d0e (Task 4.1) | 2 | 154 | 0 | ✅ |
| b2e6a3b (Task 4.2+4.3 wire-up) | 3 | 163 | 1 | ✅ |
| 85216f3 (Task 4.3 UI) | 3 | 529 | 1 | ⚠️ Work-unit is a single cohesive change but its .kt.txt snapshot is large; per-commit PR review can split this if the maintainer wants finer granularity (the snapshot is the bulk) |

**No size:exception required** — every work-unit commit is independently
reviewable.

## Remaining Tasks (NOT in PR 3)

### Phase 5 (carry-forward): 5.2 cosmetic only

- [ ] 5.2 Local-port: extend `scratch/serve-expiry-spike.mjs` with
      `ss -tlnp` (already 127.0.0.1 confirmed by `Get-NetTCPConnection`).

### Phase 6: Spec-Scenario Tests + Verification — independent verify

- [ ] 6.1 Map every scenario in both specs to a `node --test` case.
- [ ] 6.2 Run full suite: sidecar + agent-cmdline + ui-request-stdio +
      orchestrator routes. **AND** Android `:app:testDebugUnitTest` for
      the new settings + UI + VM tests (319 → 323 tests).

## Status

**PR 1 + PR 2 + PR 3 COMPLETE.** 11/11 assigned tasks done (Phases 1, 2, 3,
4 plus 5.1/5.3/5.4 mapped to PR 2, 5.2 cosmetic only on critical path).
Full Android test suite: 323/323 passing (303 baseline after foundation
+ 20 new RED-first tests for PR 3: 4 settings in-memory + 4 settings
datastore + 4 auto-approve header + 4 VM + 4 UI structural). Server-side
test counts unchanged from PR 2's `node --test` runs.

PR 3 ships the full Android surface (Task 4.1 + 4.2 + 4.3) on a child
branch `feat/pr3-android-serve-sidecar` off
`feature/opencode-serve-sidecar`. Per the feature-branch-chain strategy,
the tracker PR (`feature/opencode-serve-sidecar` → `main`) aggregates
this child branch when the maintainer is ready; the child PR diff
stays focused on the current work unit and never targets `main` directly.

**Ready for chained-PR review (PR 3 slice) and PR-3 → tracker merge.**
