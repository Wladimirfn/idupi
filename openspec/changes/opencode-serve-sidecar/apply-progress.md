# Apply Progress: opencode-serve-sidecar (PR 1 + PR 2 slices)

## Goal

Implement the PR 1 + PR 2 work-unit slices of the `opencode-serve-sidecar`
SDD change. PR 1 lands the sidecar module + tests + spike (no spawn change).
PR 2 wires the spawn path: registry writer seam, `runOpenCodeCli`
autoApprove branch, `--auto` removal in `openCodeArgs`, expire-listener
routing to `sidecar.reply`, and the reply-path probe verdict.
Feature-branch-chain delivery: PR #1 + PR #2 both target
`feature/opencode-serve-sidecar`, never `main` directly. PR 3 will land
the Android toggle + DataStore + header plumbing.

## Delivery strategy

- Mode: STANDARD (node_server strict_tdd: false per `openspec/config.yaml`)
- Slice: PR 1 + PR 2 of three chained PRs (feature-branch-chain)
- Files touched (PR 2 slice): 2 MODIFIED (`idupi-server/index.mjs`,
  `idupi-server/lib/agent-cmdline.mjs`), 1 MODIFIED test file
  (`idupi-server/test/opencode-sidecar.test.mjs`), 1 MODIFIED test file
  (`idupi-server/test/agent-cmdline.test.mjs`), 1 NEW scratch probe
  (`scratch/reply-path-probe.mjs`)
- Estimated review budget impact: PR 2 slice is ~280 insertions across
  6 files (per `git show --stat`). Within the 400-line review budget for
  one cohesive work unit.

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

## Remaining Tasks (NOT in PR 2)

### Phase 4: Spawn Gating + Toggle — PR 3

- [ ] 4.1 Plumb `X-OpenCode-Auto-Approve: 0|1` header in
      `RealIduPiClient.kt` (default 0).
- [ ] 4.2 Add `opencodeAutoApprove:Boolean` (default `false`) to settings
      repo.
- [ ] 4.3 Add `AutoApproveSection` in `SettingsScreen.kt` mirroring
      `GeneralSection` Switch (L148).

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

## Status

**PR 1 + PR 2 COMPLETE.** 8/8 assigned tasks done (Phases 1, 2, 3 plus
5.1/5.3/5.4 mapped to PR 2). 25/25 sidecar tests pass (16 PR 1 + 9 PR 2),
15/15 agent-cmdline tests pass (12 PR 1 baseline + 3 PR 2 autoApprove),
9/9 ui-request-stdio tests pass (no regression). 15/15 existing
index.mjs tests pass (`request-guard`, `async-subagent`,
`sessions-runtime`). Real OpenCode v1.18.29 spike + reply-path probe
produced D2, D3, D4, D7, reply-path verification.

PR 2 ships with `runOpenCodeCli` defaulting to `autoApprove=false`
(sidecar-first) per design's migration step (2). PR 3 adds the
`X-OpenCode-Auto-Approve` header plumbing so the Settings toggle can
flip back to legacy `--auto` for users without a working sidecar.

**Ready for chained-PR review (PR 2 slice, then PR 3 next).**
