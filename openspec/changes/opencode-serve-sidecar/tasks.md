# Tasks: OpenCode Serve Sidecar for Permission Cards

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~660 across 7 files |
| 400-line budget risk | High |
| Chained PRs | Yes |
| Suggested split | PR 1 sidecar+tests → PR 2 spawn → PR 3 Android toggle |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Sidecar + tests + spike | PR 1 | `node --test idupi-server/test/opencode-sidecar.test.mjs` | spike vs `opencode serve` v1.18.29 | Revert sidecar + test + spike |
| 2 | Spawn branch + expire listener | PR 2 | `node --test idupi-server/test/opencode-sidecar.test.mjs idupi-server/test/agent-cmdline.test.mjs` | `curl /api/v1/chat/...` header off; watch SSE | Toggle ON reverts; revert `index.mjs` L4704/L102, `agent-cmdline.mjs` |
| 3 | Android toggle + DataStore + header | PR 3 | `./gradlew :app:test` | Emulator, toggle OFF → file-create card | Toggle default OFF; revert `SettingsScreen.kt`, `RealIduPiClient.kt`, repo |

## Phase 1: Spike — Serve Expiry

- [x] 1.1 Create `scratch/serve-expiry-spike.mjs`: real `opencode serve` on 127.0.0.1, attach `/event`, emit synthetic ask, log time-to-vanish + `permission.removed`; commit into `openspec/changes/opencode-serve-sidecar/design.md` Open Questions (D7).

## Phase 2: OpenCodeSidecar Module — RED/GREEN

- [x] 2.1 RED tests in `idupi-server/test/opencode-sidecar.test.mjs`: spawn+`GET /health` ≤3s, `permission.v2.asked`→confirm(120s), `question.asked`→select, dedup, reply 204, 404 idempotent, vanish-abort, fail-closed.
- [x] 2.2 GREEN `idupi-server/lib/opencode-sidecar.mjs`: `OpenCodeSidecar` with `spawn`, `subscribeEvents` (4 callbacks), `reply` (no throw on 404), `listPendingPermissions`, `shutdown` (SIGTERM 2s+SIGKILL), `baseUrl`.
- [x] 2.3 Refactor + preserve hostile-message invariant in `idupi-server/lib/agent-cmdline.mjs` tests.

## Phase 3: Registry Wiring

- [x] 3.1 In `idupi-server/index.mjs` mirror stdin seam (L149–L187) with `setUiRequestSidecarWriter`/`clearUiRequestSidecarWriter`; `engine==="opencode"` resolves to `sidecar.reply(value)`.
- [x] 3.2 In `idupi-server/index.mjs` L4704 branch on `autoApprove`: ON→legacy stdin; OFF→sidecar first, then `spawn("opencode", ...)` + `child.stdin.end()` (L4766).
- [x] 3.3 Drop `--auto` in `openCodeArgs` (`idupi-server/lib/agent-cmdline.mjs`) when `autoApprove:false`.
- [x] 3.4 In `idupi-server/index.mjs` L102 expire listener: `engine==="opencode"` → `sidecar.reply(false)`, log `source:"auto_approve"`, `cancelled:true`; log `permission.saved`.

## Phase 4: Spawn Gating + Toggle

- [x] 4.1 Plumb `X-OpenCode-Auto-Approve: 0|1` header in `app/src/main/java/com/idupi/app/data/remote/RealIduPiClient.kt` (default 0).
- [x] 4.2 Add `opencodeAutoApprove:Boolean` (default `false`) to settings repo.
- [x] 4.3 Add `AutoApproveSection` in `app/src/main/java/com/idupi/app/ui/screens/SettingsScreen.kt` mirroring `GeneralSection` Switch (L148); persist via DataStore.

## Phase 5: Threat-Matrix RED Tests

- [x] 5.1 Fail-closed in `idupi-server/test/opencode-sidecar.test.mjs`: mid-session transport death + toggle OFF → no `--auto` relaunch; assert via spawn-fake count.
- [ ] 5.2 Local-port: extend `scratch/serve-expiry-spike.mjs` with `ss -tlnp` (127.0.0.1 only, ephemeral).
- [x] 5.3 Answer-vs-deadline in same file: late `resolve()` after 120s + 404 → no throw, no second `expire`, registry cleared.
- [x] 5.4 Reconnect replay: repeat `requestID` for `permission.v2.asked` → single `register()` call.

## Phase 5b: Verify-Report Remediation (blocker #2, #3 + warning #5)

Reopened 2026-09-08 against evidence revision
`sha256:0c528b7b1d5e1ba4ca419536e0c2e0c0a1528c0b3dfcc3a3fae100c43ab00f44` (verdict FAIL).
Remediation is a focused, single-batch cut that does NOT introduce new
chained PRs; it is small enough to ship in one reviewable slice.

- [x] 5b.1 (blocker #2 / R4 vanish-abort) `onRemoved` in `idupi-server/index.mjs`
      wires the production vanish-abort: snapshot fallback via
      `sidecar.listPendingPermissions()`, force-expire the registry entry on
      confirmed vanish, kill the run child via taskkill, drop the sidecar
      writer so a late reply cannot resurrect the request. RED tests in
      `idupi-server/test/opencode-sidecar.test.mjs` (REM/R4 × 3) pin the
      three contract branches (truly vanished → abort; still in snapshot →
      defer; orphan requestId → no-op).
- [x] 5b.2 (blocker #3 / R6 precondition) `OpenCodeSidecar` exposes
      `verifyConfigPrecondition()` and `spawn()` calls it before launching
      the opencode child. The check reads
      `~/.config/opencode/opencode.json` via an injectable `readConfig`
      seam and fails closed when no sensitive operation is at `ask`. RED
      tests (REM/R6 × 5) cover the missing-config, missing-permission-block,
      all-allow, at-least-one-ask, and spawn-fail-closed branches.
- [x] 5b.3 (warning #5 / registry drift) `idupi-server/lib/ui-request-registry.mjs`
      `buildAutoApproveDecision(method, engine)` reconciles the per-engine
      semantics per the spec: OpenCode → `{cancelled:true}` for every
      method; stdin engines → blanket auto-approve (`"Todo"`) for `select`,
      `{cancelled:true}` for confirm/input where "approve" is meaningless;
      `IDUPI_UI_AUTO_APPROVE=1` opt-in extends the stdin blanket to every
      method without ever blanket-approving OpenCode. RED tests
      (REM/REG × 3) pin the per-engine decision.
- [x] 5b.4 (warning #4 / test-local mirror) The expire-routing helper
      `applyExpireRouting` is extracted to
      `idupi-server/lib/ui-request-expiry.mjs` (single source of truth);
      `idupi-server/index.mjs` imports it for the production wiring and
      `idupi-server/test/opencode-sidecar.test.mjs` imports it for the
      suite. The test-local mirror is replaced with an import + a
      drift-detector comment so a future refactor that changes the routing
      rule fails the test on the FIRST run.

## Phase 6: Spec-Scenario Tests + Verification

- [ ] 6.1 Map every scenario in both specs to a `node --test` case in `idupi-server/test/opencode-sidecar.test.mjs` (Toggle ON/OFF, Saved approval, Misconfigured, OpenCode expiry cancels, sidecar answer).
- [ ] 6.2 Run `node --test idupi-server/test/opencode-sidecar.test.mjs idupi-server/test/agent-cmdline.test.mjs idupi-server/test/ui-request-stdio.test.mjs`.