```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:35780f7d83af481db1ceb60b2ef92c226c1709e7c38a630b8fab4f57392710ce
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 9/9
scenarios: 16/16
test_command: node --test idupi-server/test/opencode-sidecar.test.mjs idupi-server/test/agent-cmdline.test.mjs idupi-server/test/ui-request-stdio.test.mjs
test_exit_code: 0
test_output_hash: sha256:8515a9b37de28ab73acc755f32a59e3f7bcb952af2d94e68757102846a356488
build_command: ./gradlew :app:testDebugUnitTest --console=plain
build_exit_code: 0
build_output_hash: sha256:c5c25cab388f29fc993fc9d26e7c44223f398ef71aefacb6de2508bd5525dfd2
```

## Verification Report

**Change**: opencode-serve-sidecar
**Version**: N/A (delta specs, no version field)
**Mode**: Standard (`strict_tdd: false` per `openspec/config.yaml` for the server slice; no TDD runner present in `idupi-server`)

FINAL re-verify after the Phase 5d single-test remediation. Prior evidence
revision `sha256:27a1feae2138ade8bca0db1a8e258b5730334f7850bd3d54fce219b1aaaf7f57`
(verdict FAIL, 1 blocker / 1 critical, 15/16 scenarios) is superseded by this
revision. The single delta is commit `f958726`
("test(opencode-serve-sidecar): REM/R9 parity"), which touches exactly one
file — `idupi-server/test/opencode-sidecar.test.mjs` — and adds one test.
Production code is untouched by that commit (verified with
`git show --stat f958726`: 3 files changed — the test file, plus
`apply-progress.md` and `tasks.md` documentation). The R9 parity test executes
green in a fresh run, the matrix closes at 16/16, and the admission gap that
forced the previous `fail` is gone.

`evidence_revision` formula (reproducible): sha256 over the byte concatenation
of `{fresh test stdout log}` + `{fresh test stderr log (0 bytes)}` +
`gradle-verify.log` + `lib/opencode-auto-approve-header.mjs` +
`lib/ui-request-vanish.mjs` + `test/opencode-sidecar.test.mjs`. This is the
prior revision's five inputs plus the Phase 5d delta file — the file that
carries the REM/R9 assertion this revision's verdict rests on.

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 24 |
| Tasks complete | 21 |
| Tasks incomplete | 3 (5.2, 6.1, 6.2) |

Counted directly from `tasks.md` checkboxes (21 `- [x]`, 3 `- [ ]`). Phase 5b
(5b.1–5b.4), Phase 5c (5c.1–5c.2) and Phase 5d (5d.1) are fully `[x]`; 5d.1 was
added by the delta commit and is marked complete in the file. Tasks 6.1 and 6.2
are verify-owned and are discharged by this report: the compliance matrix below
IS 6.1, and the suite execution below IS 6.2. Task 5.2 remains cosmetic-only
(`ss -tlnp` spike extension; the 127.0.0.1-only bind is already evidenced by
`Get-NetTCPConnection`) and is recorded as a WARNING, not a blocker.

### Build & Tests Execution

**Build**: ✅ Exit 0 — operator-captured, REUSED, deliberately NOT re-run
(Android surface untouched by the Phase 5d delta, which is test-only and
server-side; a previous worker hung on Gradle).
```text
Command : ./gradlew :app:testDebugUnitTest --console=plain
Source  : C:\Users\elmas\AppData\Local\Temp\opencode\gradle-verify.log
SHA256  : c5c25cab388f29fc993fc9d26e7c44223f398ef71aefacb6de2508bd5525dfd2
          Re-hashed this session: 3600 bytes, byte-identical to the file
          hashed in the two prior revisions. Reuse verified, not assumed.
Tail    : > Task :app:testDebugUnitTest UP-TO-DATE
          BUILD SUCCESSFUL in 9s
          26 actionable tasks: 26 up-to-date
Caveat  : EVERY task is UP-TO-DATE, including testDebugUnitTest. Exit 0 proves
          the tree compiles and is unchanged since the last executed run; it is
          NOT runtime evidence that any of the 20 PR 3 Android tests executed in
          the captured run. The Android surface (Phase 4) therefore has
          compile-only evidence in this revision. See WARNING 1.
```

**Tests**: ✅ 64 passed / ❌ 0 failed / ⚠️ 0 skipped
```text
Command : node --test idupi-server/test/opencode-sidecar.test.mjs
                      idupi-server/test/agent-cmdline.test.mjs
                      idupi-server/test/ui-request-stdio.test.mjs
Run by  : this verify worker (FRESH run, 6.44s wall, not operator-captured)
CWD     : C:\Users\elmas\AndroidStudioProjects\IDUPI
EXIT    : 0   (captured via cmd /c redirection: stdout 7154 bytes,
               stderr 0 bytes)
SHA256  : 8515a9b37de28ab73acc755f32a59e3f7bcb952af2d94e68757102846a356488
          (sha256 of the UTF-8 concatenation of stdout + stderr of the run)
Tail    : # tests 64
          # suites 0
          # pass 64
          # fail 0
          # cancelled 0
          # skipped 0
          # todo 0
          # duration_ms 6326.9137
Delta   : 63/63 (prior revision) -> 64/64. The +1 lands clean as REM/R9.
Phase 5d test confirmed PRESENT AND GREEN in this run (stdout line):
          ok REM/R9: PendingUiRequestRegistry produces identical
          method/options/deadlineMs shape for OpenCode and Pi pend entries
          (only delivery seam differs) (0.2968ms)
Prior remediation tests re-confirmed green in the same run:
          REM/R5 x 3, REM/R4 x 3, REM/R6 x 5, REM/REG x 3.
```

**Syntax check**: ✅ `node --check idupi-server/test/opencode-sidecar.test.mjs`
→ exit 0 (the single file touched by commit `f958726`).

**Coverage**: ➖ Not available (no coverage instrumentation in `idupi-server`)

### Spec Compliance Matrix

Counts are from the actual retrieved specs: `opencode-permission-sidecar`
(6 requirements / 11 scenarios) + `ui-request-selection` (3 requirements /
5 scenarios) = **9 requirements / 16 scenarios** (verified by heading count:
9 `### Requirement:`, 16 `#### Scenario:`).

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| R1 Sidecar Lifecycle | Spawn and teardown | `opencode-sidecar.test.mjs > spawn binds to 127.0.0.1 with an ephemeral port and surfaces baseUrl`; `> shutdown sends SIGTERM first, then SIGKILL if the child does not exit within 2s` | ✅ COMPLIANT |
| R1 Sidecar Lifecycle | Transport failure fails closed | `opencode-sidecar.test.mjs > PR2/5.1: fail-closed - a sidecar spawn failure is observed by the caller (no relaunch with --auto)`; `agent-cmdline.test.mjs > openCode args drop --auto when autoApprove is false` | ✅ COMPLIANT |
| R2 Event Subscription and Registry Mapping | Permission becomes confirm card | `opencode-sidecar.test.mjs > permission.asked maps to confirm with a 120s deadline`; `> permission.v2.asked also maps to confirm with the 120s deadline` | ✅ COMPLIANT |
| R2 Event Subscription and Registry Mapping | Question becomes select card | `opencode-sidecar.test.mjs > question.asked maps to select with the exact options` | ✅ COMPLIANT |
| R3 Permission Reply Transport | Approve replies once | `opencode-sidecar.test.mjs > reply resolves to { ok: true } on HTTP 204`; `> PR2: sidecar writer(true) calls reply with reply:'once'` | ✅ COMPLIANT |
| R3 Permission Reply Transport | Late reply after expiry | `opencode-sidecar.test.mjs > reply treats 404 as { ok:true, expired:true } and NEVER throws`; `> PR2/5.3: answer-vs-deadline - late resolve after the 120s expiry never throws and never produces a second expire` | ✅ COMPLIANT |
| R4 Vanish-Abort for Dead Turns | Vanished permission aborts turn | `opencode-sidecar.test.mjs > REM/R4:` x 3 — drive the PRODUCTION `applyVanishAbort` imported from `../lib/ui-request-vanish.mjs`, asserting taskkill argv, both `publishChatEvent` frames and the writer clear | ✅ COMPLIANT |
| R5 Auto-Approve Toggle | Toggle OFF | `agent-cmdline.test.mjs > openCode args drop --auto when autoApprove is false (sidecar mode)` + `REM/R5:` x 2 fail-closed branches + Android `AutoApproveHeaderTest` false→"0" (committed at HEAD) | ✅ COMPLIANT |
| R5 Auto-Approve Toggle | Toggle ON | `opencode-sidecar.test.mjs > REM/R5: parseOpenCodeAutoApproveHeader returns true when header is exactly '1'` + `agent-cmdline.test.mjs > openCode args keep --auto when autoApprove is true (legacy mode)`; chat-route threading (`index.mjs:5299-5300`) verified by source inspection | ✅ COMPLIANT |
| R6 Approval Scope and Preconditions | Saved approval logged | `opencode-sidecar.test.mjs > permission.saved routes to onSaved and permission.removed routes to onRemoved` | ✅ COMPLIANT |
| R6 Approval Scope and Preconditions | Misconfigured permissions detected | `opencode-sidecar.test.mjs > REM/R6:` x 5 (import production `../lib/opencode-sidecar.mjs`) | ✅ COMPLIANT |
| R7 CLI Stdin Delivery (server) | Exact value reaches stdin | `ui-request-stdio.test.mjs > Claude-style writer preserves a JSON boolean for confirm-true (not the string "true")`; `> Pi-style writer addresses the frame with the PI request id` | ✅ COMPLIANT |
| R7 CLI Stdin Delivery (server) | OpenCode answer rides the sidecar | `opencode-sidecar.test.mjs > PR2: sidecar writer(true) calls reply with reply:'once'` + source `index.mjs:3244` | ✅ COMPLIANT |
| R8 Terminality, Deadline, Fallback | Expiry auto-approves (stdin engines) | `opencode-sidecar.test.mjs > REM/REG: buildAutoApproveDecision returns blanket auto-approve for engine=pi/claude select` (imports production `../lib/ui-request-registry.mjs`) | ✅ COMPLIANT |
| R8 Terminality, Deadline, Fallback | OpenCode expiry cancels | `opencode-sidecar.test.mjs > REM/REG: ... CANCEL for engine=opencode regardless of method` + `PR2: expire routing for engine=opencode fires sidecar.reply(false)` via shared `../lib/ui-request-expiry.mjs` | ✅ COMPLIANT |
| R9 Universal Engine Coverage (server) | OpenCode like Pi | `opencode-sidecar.test.mjs > REM/R9: PendingUiRequestRegistry produces identical method/options/deadlineMs shape for OpenCode and Pi pend entries (only delivery seam differs)` — NEW in commit `f958726`, executed green this run (0.2968ms) | ✅ COMPLIANT |

**Compliance summary**: 16/16 scenarios compliant, 0 partial, 0 untested,
0 failing. Requirements fully compliant: 9/9 (R1–R9). The R9 row flips from
⚠️ PARTIAL (prior revision) to ✅ COMPLIANT on fresh runtime evidence; the
admission gate's 16/16 requirement is met.

**What REM/R9 actually proves (scope of the R9 flip).** The test imports the
production `PendingUiRequestRegistry` (`../lib/ui-request-registry.mjs`, the
same module `index.mjs` uses at runtime) and registers two pend entries — one
`engine: "opencode"`, one `engine: "pi-cli"` — with identical
`method: "select"`, `options`, `title` and `message` inputs, then asserts
`ocHandle.deadlineMs === piHandle.deadlineMs === 60_000`,
`ocEntry.method === piEntry.method`, `deepEqual(ocEntry.options, piEntry.options)`,
`ocEntry.title === piEntry.title`, `ocEntry.message === piEntry.message`, and
`ocEntry.engine !== piEntry.engine` (the ONLY permitted difference — the
delivery-seam signal `index.mjs` branches on). That is exactly the scenario's
THEN clause ("rendering and validation match Pi's; only delivery differs").
The GIVEN half of the scenario ("a permission request captured by the sidecar")
is not re-driven inside this one test — the two entries are registered through
the registry's public `register()` surface — and is inherited from the R2 rows
(sidecar SSE `permission.v2.asked` → confirm 120s; `question.asked` → select
with exact options) and the R7 row (OpenCode answers ride `sidecar.reply`, not
stdin). Composed, the three rows cover GIVEN/WHEN/THEN; the parity assertion
itself is the piece that was missing. See SUGGESTION 6 for hardening this into
a single test.

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|-------------|--------|-------|
| R1 Sidecar Lifecycle | ✅ Implemented | `opencode-sidecar.mjs` binds `serve --hostname 127.0.0.1 --port 0`; `index.mjs:4869` `if (!autoApprove)` spawns sidecar-first and throws fail-closed before `opencode run`. |
| R2 Event mapping | ✅ Implemented | `_routeFrame` maps `permission.asked`/`v2.asked`→confirm, `question.asked`→select; `_seen` Set dedup. |
| R3 Reply transport | ✅ Implemented | `reply()` normalizes 404 → `{ok:true, expired:true}`, never throws. `REPLY_PATH` matches the empirically probed route. |
| R4 Vanish-Abort | ✅ Implemented AND covered | `lib/ui-request-vanish.mjs` `applyVanishAbort({...})` is the SINGLE implementation: guard (unknown engine id → `{ignored:true}`), D7 snapshot fallback via `listPendingPermissions()` (still pending → defer), then `uiRequestRegistry.expire()` → `clearUiRequestSidecarWriter()` → `execFile("taskkill", ["/F","/T","/PID",pid])` → `UI_REQUEST_RESOLVED` + `MESSAGE_END`. Production call site `index.mjs:5034-5046` passes the real bag; the suite calls the same exported function. No drift layer. |
| R5 Auto-Approve Toggle | ✅ Implemented | `lib/opencode-auto-approve-header.mjs` exports `parseOpenCodeAutoApproveHeader(headers)` (true only when `raw.trim() === "1"`, behind a non-object guard and a `typeof raw !== "string"` guard) and `OPENCODE_AUTO_APPROVE_HEADER = "x-opencode-auto-approve"`. Chat route `index.mjs:5299-5300` reads it at the `activeEngine === "opencode"` branch and threads it into `runOpenCodeCli(...)`. `autoApprove=true` skips the sidecar (`if (!autoApprove)` at `:4869`) and re-enables stdin registration (`if (autoApprove)` at `:5145`); `openCodeArgs` then emits `--auto`. Chain complete both directions. |
| R6 Preconditions | ✅ Implemented | `evaluatePermissionPrecondition(doc)` + `verifyConfigPrecondition()` with an injectable `readConfig` seam; `spawn()` awaits it before launching the child. Fails closed with a diagnostic reason. |
| R7/R8 Stdin vs sidecar | ✅ Implemented | `index.mjs:3244` (client resolve) and `:141` (expire, via shared `applyExpireRouting`) branch on `entry.engine === "opencode"`. `buildAutoApproveDecision(method, engine)` is per-engine and `_onTimerFire` passes `entry.engine`. |
| R9 Universal coverage | ✅ Implemented AND covered | One shared `PendingUiRequestRegistry` + one shared validator; the only per-engine variance is the delivery seam (`engine` field), now pinned by REM/R9 at runtime. |

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| D1 one `serve` per session | ✅ Yes | Constructed inside `runOpenCodeCli` (`index.mjs:4870`), torn down per run. |
| D2 bind `127.0.0.1` ephemeral | ✅ Yes | Spike confirmed via `Get-NetTCPConnection`; tests assert the host/port. |
| D3 `GET /health` 3s probe | ✅ Yes | `/global/health` with a module-level `Promise.race` budget. |
| D4 own minimal SSE parser | ✅ Yes | No `eventsource` dependency. |
| D5 eager fail-closed probe at spawn | ✅ Yes | R6 precondition + health probe both run before the child launches. |
| D6 reject on expiry via local timer → POST | ✅ Yes | Shared `applyExpireRouting` → `sidecar.reply(false)`. |
| D7 vanish detect (`removed` SSE + snapshot) | ✅ Yes | `applyVanishAbort` defers when the id is still in the snapshot and aborts only when truly absent — exactly the D7 verdict. |
| D8 toggle gates spawn only | ✅ Yes (reachable both ways) | `parseOpenCodeAutoApproveHeader` → `runOpenCodeCli(..., autoApprove)`; ON = legacy `--auto`, OFF = sidecar. Nothing else is gated. |

### Issues Found

**CRITICAL**: None. The prior revision's CRITICAL #1 (R9 "OpenCode like Pi"
had no covering test) is CLOSED by commit `f958726`: the REM/R9 parity test
exists, is present in the fresh stdout as a passing case, and asserts the
scenario's THEN clause against production registry code.

**WARNING**

1. **Android Phase 4 has compile-only evidence.** The reused Gradle log is
   exit 0 but shows `26 actionable tasks: 26 up-to-date`, including
   `testDebugUnitTest`. No Android test executed in the captured run, so the
   20 PR 3 tests have no runtime evidence here. Not re-run per the anti-hang
   rule (Android is untouched by the Phase 5d delta, which is test-only and
   server-side). Recommend one fresh
   `./gradlew :app:testDebugUnitTest --rerun-tasks` capture before archive.
2. **Task 5.2 unchecked (cosmetic).** The `ss -tlnp` spike extension was never
   added; the 127.0.0.1-only bind is already evidenced by
   `Get-NetTCPConnection`.
3. **No E2E evidence for the proposal's success criteria.** "File-create prompt
   shows a card over SSE" and "untouched card expires → CANCELLED, file absent"
   remain unverified against real `opencode serve` v1.18.29; the spike only
   observed an idle session and no live permission ever fired. The R4 abort path
   has therefore never run against a real engine.
4. **The R5 header→argv chain is runtime-pinned at both ends but not across the
   seam.** `REM/R5` × 3 pin the parser (`"1"` → `true`) and `agent-cmdline`
   pins `--auto` for `autoApprove: true`; the two-line chat-route plumbing
   (`index.mjs:5299-5300`) is source-inspection evidence only, because
   `index.mjs` is a monolith no suite imports. Marked COMPLIANT on composed
   evidence, but it is the weakest link in the R5 chain.

**SUGGESTION**

5. `openCodeArgs` defaults `autoApprove = true` (legacy) while `runOpenCodeCli`
   defaults `autoApprove = false`. The chat route resolves the ambiguity for the
   production path; harmonising the function defaults would remove the footgun
   for future call sites.
6. Harden R9: drive both pend entries through the real producers — the
   sidecar's `onAsk` for OpenCode and the Pi stdout JSON-line path for Pi —
   then assert the same shape. That folds the scenario's GIVEN half into the
   parity test itself instead of inheriting it from the R2/R7 rows.
7. Extract `runOpenCodeCli` (or add one HTTP-level contract test) so the
   chat-route plumbing in WARNING 4 stops being source-only.
8. `spawn()` attaches a stdout `data` listener that is never removed after the
   listen line is parsed; `subscribeEvents()` attaches a second. Detach the
   first once the port is known.
9. The vanish-abort kills via `execFile("taskkill", …)`, Windows-only. It
   matches existing convention in this file, so it is not a regression — but it
   pins the sidecar to Windows hosts.
10. Four files remain dirty in the working tree (`app/build.gradle.kts`,
    `RealIduPiClient.kt`, `network_security_config.xml`,
    `RealIduPiClientUiResponseTest.kt`) plus a dirty `idupi-server/index.mjs`.
    Re-checked this revision: the `RealIduPiClient.kt` delta is a 6-line
    `ChatEvent.ErrorOccurred` emission on the exhausted-poll branch and does NOT
    touch the toggle header; the header and
    `opencodeAutoApprove: Boolean = false` are committed at HEAD. `index.mjs`
    carries the Phase 5c production wiring (header reader + `applyVanishAbort`
    call site) and is uncommitted — commit it before archive so the passing
    evidence maps to a tree position.

### Verdict

**PASS WITH WARNINGS** — 21/24 tasks complete (5.2 cosmetic; 6.1/6.2 discharged
by this report), 64/64 focused tests pass in a fresh run (exit 0, 6.44s), and
the single blocker from the prior revision is closed: R9 "OpenCode like Pi" now
has a covering test (REM/R9, added by commit `f958726`, green at runtime,
asserting identical `method`/`options`/`deadlineMs`/`title`/`message` across an
OpenCode and a Pi pend entry on the production registry with only `engine`
differing). The matrix closes at 16/16 scenarios and 9/9 requirements, with
zero blockers and zero critical findings. Warnings are all carried forward,
non-blocking, and unchanged in kind from the prior revision: Android has
compile-only evidence (Gradle reused, `UP-TO-DATE`, per the anti-hang rule),
task 5.2 is cosmetic, there is no E2E run against a real `opencode serve`, and
the R5 chat-route seam is source-only. No warning blocks admission.
