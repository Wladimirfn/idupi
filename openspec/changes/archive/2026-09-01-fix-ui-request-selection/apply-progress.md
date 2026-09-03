# Apply Progress — fix-ui-request-selection / Phase 6 (Testing, RED-first)

## Goal

Pin Phase 4 and Phase 5 behaviour with RED-first tests so a future refactor
cannot silently regress the `ui_request` channel. The harness is split in
two: a Kotlin unit-test layer for the parser / card / client / ViewModel,
and a Node layer (smoke + stub-reader) for the server registry and the
Claude/OpenCode `["pipe","pipe","pipe"]` stdio contract. Phases 1-5 are
already shipped and unchanged.

## Workload Forecast (re-check)

| Field | Value |
|-------|-------|
| Estimated changed lines (Phase 6 only) | ~885 net new (858 lines across 4 new Kotlin test files + ~27 lines added to FakeIduPiClient hooks) + 289 lines verbatim resource snapshot + ~280 lines Node (smoke + stdio test). Total disk added: ~1454 lines |
| 400-line budget risk | Medium (single-PR over the soft 400 line guideline, but Phase 6 is tests-only with no app/server logic drift, so chained-PR split would not reduce review surface — every PR is just more tests) |
| Chained PRs recommended | No (Phases 1-5 are already shipped and unchanged; Phase 6 is one PR of tests, not a stacked change) |
| Phase 6 delivery strategy | single PR |

## Completed Tasks

- [x] 6.1 RED: `SseFrameParser` decodes `ui_request` + discards `_resolved` (JUnit4 + coroutines-test)
- [x] 6.2 RED: `UiRequestCard` INPUT submit + 1s countdown tick
- [x] 6.3 RED: `RealIduPiClient.sendUiResponse` posts structured JSON, never `value.toString()`
- [x] 6.4 RED: `respondToUiRequest` holds on 409, clears on 200 (Fake IduPiClient)
- [x] 6.5 Smoke `scratch/ui-request-smoke.mjs`: Pi select writes one stdin JSON line; 120s expiry auto-approves; stale 409
- [x] 6.6 Stub-reader RED tests for Claude + OpenCode `["pipe","pipe","pipe"]` (exactly one JSON line)

## Files Changed (Phase 6)

| File | Action | Why |
|---|---|---|
| `app/src/test/java/com/idupi/app/data/remote/UiRequestParserTest.kt` | New | 10 RED tests for `parseSseEvent("ui_request", …)` + `ui_request_resolved` discard + full SSE wire round-trip |
| `app/src/test/java/com/idupi/app/ui/components/UiRequestCardTest.kt` | New | 9 structural tests over a snapshot of the Composable (no Compose UI test dep available) |
| `app/src/test/resources/UiRequestCard.kt.txt` | New | Verbatim copy of the UiRequestCard source as a test resource snapshot |
| `app/src/test/java/com/idupi/app/data/remote/RealIduPiClientUiResponseTest.kt` | New | 9 RED tests for the private `Any.toJsonElement()` helper via reflection (pins wire shape: `Boolean true` → JSON `true`, never the string `"true"`) |
| `app/src/test/java/com/idupi/app/viewmodel/ChatViewModelUiResponseTest.kt` | New | 9 RED tests for `respondToUiRequest` 200 / 409 / other-error / network paths |
| `app/src/test/java/com/idupi/app/FakeIduPiClient.kt` | Modify | Add `sendUiResponseFailure` + `sendUiResponseFailuresRemaining` hooks so tests can drive the 409-hold path |
| `scratch/ui-request-smoke.mjs` | New | 6 scenarios exercising the REAL `PendingUiRequestRegistry` (round-trip, expiry, stale token, out-of-date value, unknown id) |
| `idupi-server/test/ui-request-stdio.test.mjs` | New | 6 stub-reader RED tests for Claude + OpenCode `["pipe","pipe","pipe"]` |
| `openspec/changes/fix-ui-request-selection/tasks.md` | Modify | Tick Phase 6 boxes |

## Design Decisions

1. **UiRequestCard test is structural, not Compose-rendered.** The build
   ships only `junit` + `kotlinx.coroutines.test` on the unit test
   classpath; adding `androidx.compose.ui:ui-test-junit4` would change the
   classpath for the whole module. We pin the contract via a snapshot of
   the source file (`src/test/resources/UiRequestCard.kt.txt`) and regex
   assertions on the Composable's wiring (INPUT `when`-branch, `canSubmit`
   gate, `delay(1000L)` tick, `expired` disables actions, `errorMessage`
   slot). A future regression that silently removes one of these WILL
   fail the tests even though we never render the composable. Manual
   visual verification remains part of the Phase 5 contract.

2. **`Any.toJsonElement()` is tested via reflection.** The helper is a
   top-level `private` extension in `RealIduPiClient.kt`. Kotlin compiles
   top-level extensions to a `<FileName>Kt` synthetic class with a
   `static` method (verified convention: `SseFrameParserKt.parseSseEvent`).
   `setAccessible(true)` overrides the package-private access modifier so
   the test can invoke the static method via reflection. The assertions
   pin the WIRE shape (e.g. `JsonPrimitive(true)`, never
   `JsonPrimitive("true")`) so a future refactor that moves or renames
   the helper cannot silently re-introduce the `value.toString()` bug.

3. **FakeIduPiClient got `sendUiResponseFailure` + `sendUiResponseFailuresRemaining`
   hooks.** The existing `failWith` is global; we needed a way to throw
   `IduPiHttpException(409)` from `sendUiResponse` specifically without
   breaking other methods. The two fields are additive; existing tests
   that don't touch them keep their original behaviour. The retry-flow
   test uses `sendUiResponseFailuresRemaining = 1` to drive "200 after
   409" through one fake instance.

4. **Smoke harness targets the REAL registry**, not a mock. It imports
   `PendingUiRequestRegistry`, `validateUiAnswer`, and `buildAutoApproveDecision`
   from `idupi-server/lib/ui-request-registry.mjs`. Scenarios use
   `deadlineMs: 1` so the 120s timer fires in milliseconds instead of
   minutes. The harness exits 0 on full success and 1 on any failure,
   suitable for Phase 6's "runtime smoke harness" call-out in
   tasks.md Unit 2.

5. **Stub-reader tests reuse Node itself as the child interpreter.** No
   real Claude or OpenCode install is required. The inline stub script
   (`-e ...`) reads ONE line from stdin, echoes it on stdout, and exits
   0. The negative control asserts `stdio[0]="ignore" -> child.stdin IS
   null` so any future regression to the old Claude/OpenCode spawn
   surfaces here, NOT in production.

## Verification (Phase 6, mixed structural + node --test)

**Node (`node --test` and the smoke harness):**

- `node scratch/ui-request-smoke.mjs` → all 6 scenarios pass against the
  real `PendingUiRequestRegistry`:
  - select round-trip writes exactly one JSON line to stdin
  - 120s expiry auto-approves select with blanket `Todo`
  - 120s expiry auto-resolves confirm with `cancelled:true`
  - superseded request's token is rejected with 409
  - out-of-date value rejected with 400 BEFORE any stdin write
  - unknown requestId rejected with 404
- `node --test idupi-server/test/ui-request-stdio.test.mjs` → all 6 pass:
  - Claude-style writer delivers one JSON line via a writable stdin pipe
  - Claude-style writer preserves a JSON boolean for confirm-true
  - Claude-style writer preserves a free-text input verbatim
  - OpenCode-style writer delivers one JSON line via a writable stdin pipe
  - OpenCode-style writer preserves a JSON boolean for confirm-true
  - stdio[0]="ignore" → child.stdin IS null (negative control)
- Regression: existing `orchestrator-engines.test.mjs`,
  `agent-cmdline.test.mjs` still pass alongside the new test.

**Kotlin (structural review, no Android SDK build per orchestrator contract):**

- All 10 parser tests reference real symbols: `parseSseEvent`,
  `SseFrameParser.feedLine`, `ChatEvent.UiRequestReceived`, `UiRequest`,
  `UiRequestMethod`. Same package as the SUT (`com.idupi.app.data.remote`).
- All 9 card tests pin the source snapshot; every regex verified against
  the snapshot file via Node's regex engine (23/23 patterns match the
  expected source).
- All 9 client tests use the verified Kotlin compilation convention
  (`Class.forName("…RealIduPiClientKt")` finds the helper; the JVM
  signature is `static JsonElement toJsonElement(Object $receiver)`,
  mirroring the verified `SseFrameParserKt.parseSseEvent` shape).
- All 9 ViewModel tests use the existing `MainDispatcherRule` +
  `FakeClientSource` plumbing; the new `sendUiResponseFailure` hook on
  `FakeIduPiClient` is additive (existing tests do not touch it).
- `ChatBubble.kt` (orphaned legacy composable) still references
  `UiRequestCard` -- unchanged from Phase 5. The new tests do not
  reference it.

## Out of Scope (Phase 7+ — not started)

- Removal of the orphaned `ChatBubble.kt` legacy composable.
- Integration tests that actually open a TCP socket to a live
  `idupi-server` and round-trip a real `ui_request` SSE frame.
- Compose UI test deps + interaction tests for the card's
  OutlinedTextField onValueChange (requires `ui-test-junit4`).

## Risks / Notes

- The reflection-based test for `Any.toJsonElement()` depends on Kotlin's
  top-level extension compilation convention. The convention is verified
  by `javap` on the existing `SseFrameParserKt` class. If a future
  Kotlin version changed this convention, the test fails with a clear
  `ClassNotFoundException` whose message names the convention and points
  at the snapshot evidence.
- The smoke harness and stub-reader tests are intentionally NOT part of
  the existing `idupi-server/test/*.test.mjs` discovery path -- the
  smoke lives under `scratch/` (manual smoke, not CI), and the
  stub-reader lives in `idupi-server/test/` (CI). Phase 7 may promote
  the smoke to CI by adding it to the runbook.
- `_uiRequestError` flow is asserted indirectly through `respondToUiRequest`
  (200 clears, 409 sets, new request clears). Direct tests against the
  `uiRequestError` `StateFlow` live in `ChatViewModelUiResponseTest.kt`.

## Next Steps

1. Phase 7 (verification): re-run all spec scenarios end-to-end against
   the live server + an emulator. This is the orchestrator's domain; the
   Phase 6 work is now ready to be verified.
2. Optional follow-up: delete `app/.../ui/components/ChatBubble.kt`
   (dead code since Phase 4 wired the new `ChatBubble` inside
   `ChatScreen.kt`).
3. Optional follow-up: add `androidx.compose.ui:ui-test-junit4` to the
   test classpath and convert the structural card tests to interaction
   tests (this would change the module's test deps).

# Apply Progress — fix-ui-request-selection / Phase 5 (Android UI)

## Goal

Render the pending `ui_request` inline in `ChatScreen` with a 1s grace-period
countdown, support `INPUT` (TextField + Submit + Cancel), `CONFIRM` and
`SELECT` actions, and survive Chat navigation without cancelling the request.
Phases 1-4 are already shipped (server registry, route, engine adapters,
Android model + parser + client wiring).

## Workload Forecast (re-check)

| Field | Value |
|-------|-------|
| Estimated changed lines (Phase 5 only) | ~210 (1 new file section + 3 edits) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Phase 5 delivery strategy | single PR |

## Completed Tasks

- [x] 5.1 Add INPUT branch (TextField + Submit + Cancel) and 1s countdown to `ui/components/UiRequestCard.kt`
- [x] 5.2 Inline `UiRequestCard` in `ui/screens/ChatScreen.kt` when `activeUiRequest` matches last SYSTEM message
- [x] 5.3 Leaving Chat does NOT cancel; return re-renders countdown with remaining time
- [x] 5.4 Out-of-date value (e.g. "C" not in options) keeps dialog open, surfaces server rejection

## Files Changed (Phase 5)

| File | Action | Why |
|---|---|---|
| `app/.../ui/components/UiRequestCard.kt` | Rewrite | Add INPUT branch + 1s countdown from `deadlineAt`; accept `errorMessage` slot; disable buttons on expiry |
| `app/.../ui/screens/ChatScreen.kt` | Edit | Inline `UiRequestCard` after the LazyColumn / subagents bar, above the input bar, when `activeUiRequest != null` (atomically matches the SYSTEM message set in `ChatEvent.UiRequestReceived`) |
| `app/.../viewmodel/ChatViewModel.kt` | Edit | Add `_uiRequestError: MutableStateFlow<String?>`; set on 409 (stale / superseded / out-of-date value), clear on 200 / other errors / new request / `clearMessages` / `cancelTask` |

## Design Decisions

1. **Inline render site**: above the input bar, below the activity/subagent
   bars. The card is the most recent thing the user sees and gets the screen
   space it needs without competing with the message stream.
2. **Countdown from absolute `deadlineAt`**: `(deadlineAt -
   System.currentTimeMillis()) / 1000`. `remember(request.id,
   request.deadlineAt)` re-derives `remainingSeconds` when the composable is
   re-mounted (e.g., returning to Chat after navigating away), so the
   "alive-on-exit" scenario shows the correct remaining time without a 1s
   tick lag.
3. **INPUT Cancel = clear TextField locally**, not cancel-the-request. The
   spec doesn't define a client-side cancel; expiry auto-resolves server-side.
4. **`expired` disables Submit / pick / accept buttons** so a user can't fire
   a POST that will 409 anyway.
5. **409 surfacing**: `_uiRequestError` is a separate `StateFlow` (not a chat
   message) so the rejection appears inline next to the card the user just
   acted on. The dialog stays open because Phase 4's `respondToUiRequest`
   keeps `_activeUiRequest` set on 409 -- the new error flow does not change
   that behaviour.
6. **`LaunchedEffect` countdown never dies until the composable leaves the
   composition**: leaving Chat disposes the effect; returning re-mounts it
   and re-derives `remainingSeconds` from the absolute deadline. The
   countdown is correct on first paint after return, not after a 1s tick.

## Verification (Phase 5, structural -- no Android SDK build per orchestrator contract)

- All 3 `UiRequestMethod` cases covered in `UiRequestCard` via `when` (compile-
  time exhaustiveness).
- All call sites to `UiRequestCard` updated to the new 3-arg signature
  (`request`, `onResponse`, `errorMessage`).
- The new `_uiRequestError` `StateFlow` is referenced in 1 declaration, 1
  read in `ChatScreen`, 4 mutation sites in `ChatViewModel`
  (`UiRequestReceived` clear, `respondToUiRequest` 200 clear, `respondToUiRequest`
  409 set, `respondToUiRequest` other-error clear, `clearMessages` clear,
  `cancelTask` clear -- 6 total).
- Phase 4's invariant "no `_activeUiRequest.value = null` BEFORE the POST"
  preserved on the 200 path; `clearUiRequestError()` added symmetrically so
  the error doesn't leak across requests.
- `ChatBubble.kt` (the orphaned old composable at
  `app/.../ui/components/ChatBubble.kt`) still references `UiRequestCard` --
  unchanged from Phase 4. It is dead code (no callers in `ChatScreen.kt`
  any more) so the signature bump on `UiRequestCard` does not break
  compilation. Phase 6 may clean this up.

## Out of Scope (Phase 6+ -- not started)

- Phase 6.1-6.6: RED tests for parser, card, client, Fake IduPiClient
  recording fields, smoke harness, stub-reader tests for Claude / OpenCode.
- Removal of the orphaned `ChatBubble.kt` legacy composable.

## Risks / Notes

- The countdown shows `${remainingSeconds}s` (whole seconds). A user who
  reads "0s" and clicks Send will POST; the server may have just
  auto-resolved in between, so the response is 409 and the dialog stays
  open with a "El servidor rechazó..." message. This matches the spec's
  "Invalid answer re-prompts" scenario.
- `_uiRequestError` is a separate `StateFlow` rather than a chat
  `ChatMessage` so the rejection renders next to the card without polluting
  the conversation history with transient state.

## Next Steps

1. Phase 6: RED-first tests for the Phase 4/5 wiring.
2. Optional follow-up: delete `app/.../ui/components/ChatBubble.kt` (dead
   code since Phase 4 wired the new `ChatBubble` inside `ChatScreen.kt`).
3. After Phase 6 lands, re-verify all spec scenarios end-to-end (select /
   confirm / input round-trip + 409 retry + 200 clear + alive-on-exit).

## Workload Forecast (re-check)

| Field | Value |
|-------|-------|
| Estimated changed lines (Phase 4 only) | ~120 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Phase 4 delivery strategy | single PR |

## Completed Tasks

- [x] 4.1 Add `INPUT` to `UiRequestMethod` + `deadlineAt: Long` (+ `token`, `sessionId` for the round-trip)
- [x] 4.2 Map `ui_request` → `ChatEvent.UiRequestReceived` in `SseFrameParser.kt`; `ui_request_resolved` discarded at DEBUG
- [x] 4.3 `RealIduPiClient.sendUiResponse` posts structured `UiResponsePayload{value:JsonElement, token, sessionId}` (replaces `value.toString()`)
- [x] 4.4 `IduPiClient.sendUiResponse(reqId, value, token, sessionId)`; `ChatViewModel.respondToUiRequest(UiRequest, Any)` holds the dialog on 409, clears it on 200

## Files Changed (Phase 4)

| File | Action | Why |
|---|---|---|
| `app/.../domain/model/UiRequest.kt` | Modify | Add `INPUT` to enum; add `deadlineAt: Long`, `token: Long`, `sessionId: String` to data class |
| `app/.../data/remote/SseFrameParser.kt` | Modify | New `UiRequestPayload` for `ui_request`; handle `ui_request` → `UiRequestReceived`; silently discard `ui_request_resolved` at DEBUG |
| `app/.../domain/repository/IduPiClient.kt` | Modify | `sendUiResponse(requestId, value, token, sessionId)` |
| `app/.../data/remote/RealIduPiClient.kt` | Modify | `UiResponsePayload { value: JsonElement, token, sessionId }` + `toJsonElement()` helper |
| `app/.../viewmodel/ChatViewModel.kt` | Modify | `respondToUiRequest(UiRequest, Any)`; passes token/sessionId; holds on 409; clears on 200 or other error |
| `app/src/test/.../FakeIduPiClient.kt` | Modify | Match new signature; add `lastSentUiRequestId/Value/Token/SessionId` recording fields for Phase 6 RED tests |
| `openspec/changes/fix-ui-request-selection/tasks.md` | Modify | Tick Phase 4 boxes |

## Verification

- `node --check` on `idupi-server/index.mjs`, `chat-events.mjs`, `lib/ui-request-registry.mjs` → all OK (no server changes; Phase 4 is Android-only).
- Kotlin structural review (no Android SDK available in this session, per
  user instruction "verify Kotlin compiles conceptually (no Android SDK build
  required, just structural)"):
  - All `sendUiResponse` callers/overrides updated to the new 4-arg signature.
  - `UiRequestMethod.INPUT` referenced in the parser; `UiRequestMethod.CONFIRM`
    in `UiRequestCard.kt` continues to compile (Phase 5 will add the INPUT
    branch in the card).
  - `import com.idupi.app.data.remote.IduPiHttpException` added to
    `ChatViewModel.kt` for the 409 branch.
  - `JsonPrimitive` added to imports in `RealIduPiClient.kt`.
  - No remaining 2-arg `sendUiResponse(...)` call sites.
  - No remaining `_activeUiRequest.value = null` BEFORE the POST (the new
    code clears AFTER the 200 succeeds so a 409 keeps the dialog alive).

## Out of Scope (Phase 5+ — not started)

- Phase 5.1-5.4: `UiRequestCard` INPUT branch (TextField + Submit + Cancel),
  1s countdown, inline in `ChatScreen` when `activeUiRequest` matches last
  SYSTEM message, out-of-date value keeps dialog open.
- Phase 6.1-6.6: RED tests for parser/card/client/Fake; smoke harness.

## Risks / Notes

- `token` is parsed as Long (precise JSON number round-trip on the SSE side)
  but sent as String on the POST. The registry coerces both sides with
  `Number()` so either type round-trips; the design contract documents the
  interface as String.
- The 409 hold-path does NOT surface an error message to the chat — the spec
  scenario says the dialog stays open, and the server's 120s timer is the
  fallback. A retry attempt from the UI (Phase 5) is what eventually resolves
  the dialog one way or another.
- `_activeUiRequest.value = null` is also called on `cancelTask()` (line 562
  area). Phase 4 left that path unchanged: it makes sense for a manual cancel
  to close the dialog (the server will 409 any in-flight POST, but the user
  asked to stop).

## Next Steps

1. Phase 5: implement `UiRequestCard` INPUT branch + countdown + inline in
   `ChatScreen`.
2. Phase 6: RED-first tests for the Phase 4 wiring (parser + FakeIduPiClient
   recording fields are already in place to support 6.1, 6.3, 6.4).
3. Re-run Phase 4 review against the spec scenarios (select/confirm/input
   round-trip + 409 retry + 200 clear) once Phase 6 tests land.

# Apply Progress — fix-ui-request-selection / Phase 2 (Server Transport: SSE + HTTP Route)

## Goal

Add the SSE event pair (`ui_request` / `ui_request_resolved`) and the
authenticated `POST /api/v1/chat/ui-response/:requestId` route so a chat app
can answer engine requests over HTTP, with exact-value validation and a
409-before-stdin guarantee, and a terminal expiry path that clears the
registry entry before the 300s taskkill.

## Completed Tasks

- [x] 2.1 Add `UI_REQUEST` + `UI_REQUEST_RESOLVED` to `idupi-server/chat-events.mjs`
- [x] 2.2 Add `POST /api/v1/chat/ui-response/:requestId` behind `requireAuth` in `index.mjs`
- [x] 2.3 Validate `body.{value,token,sessionId}` (select→exact, confirm→bool, input→non-empty); 409 BEFORE any stdin write
- [x] 2.4 Expiry path logs decision, emits `ui_request_resolved`, clears registry entry before the 300s taskkill

## Files Changed (Phase 2)

| File | Action | Why |
|---|---|---|
| `idupi-server/chat-events.mjs` | Modify | Add `UI_REQUEST: "ui_request"` and `UI_REQUEST_RESOLVED: "ui_request_resolved"` to `CHAT_EVENTS` |
| `idupi-server/index.mjs` | Modify | Add `POST /api/v1/chat/ui-response/:requestId` handler behind `requireAuth` (registry resolve, publishes `ui_request_resolved` on success / expiry) |
| `idupi-server/lib/ui-request-registry.mjs` | New | Pending registry: register/resolve/expire, monotonic token, exact-value validation (400), stale-token rejection (409), blanket auto-approve on expiry |
| `idupi-server/lib/cli-constants.mjs` | New | `UI_REQUEST_DEADLINE_MS` (120s) with the strictly-below-300s backstop invariant enforced at load |

## Design Decisions

1. **Validation lives in the registry, before any stdin write.** `validateUiAnswer()`
   is a pure function (select → exact option, confirm → boolean, input → non-empty);
   a 400 can never leak a rejected value to the engine.
2. **409-before-stdin is a registry invariant.** Token/session checks run before
   validation, and the route only writes to stdin after `resolve()` returns
   `ok: true` — the terminal transition clears the entry's timer first so a
   late expiry cannot double-resolve.

## Verification

- `node --check` on `chat-events.mjs`, `index.mjs`, `lib/ui-request-registry.mjs` → all OK.
- `scratch/ui-request-smoke.mjs` against the REAL registry: select round-trip
  writes exactly one stdin JSON line; expiry auto-approves; stale token → 409;
  out-of-date value → 400 BEFORE any stdin write; unknown id → 404.
- Registry `resolve()` rejects with 400 for invalid answers and 409 for
  token/session mismatch or superseded requests — the route never writes to
  the engine stdin on a rejected answer.

## Out of Scope (Phase 3+ — not started)

- Engine adapters + stdio pipe wiring (Phase 3), Android model/parser/client
  (Phase 4), UI card (Phase 5), RED tests (Phase 6).

## Risks / Notes

- The 120s `UI_REQUEST_DEADLINE_MS` is enforced strictly below the 300s
  `AGENT_CLI_TIMEOUT_MS` backstop at load time (`cli-constants.mjs`), so the
  registry's blanket auto-approve always fires before taskkill.
- The 400 rejection path (invalid answer) is handled client-side by
  `respondToUiRequest` holding the dialog open, mirroring the 409 path.

## Next Steps

1. Phase 3: engine adapters + stdio pipe wiring.
2. Phase 4: Android model / parser / client wiring (holds on 409 and 400,
   clears on 200).
