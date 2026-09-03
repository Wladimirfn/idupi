# Tasks: Universal UI Request Selection

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~700 (server ~260, app ~200, tests ~240) |
| 400-line budget risk | Medium |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Phase A server) → PR 2 (interactive + stdio) → PR 3 (Android) |
| Delivery strategy | auto-chain |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Medium

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Phase A auto-resolve + registry | PR 1 | `cd idupi-server && node --check lib/ui-request-registry.mjs index.mjs chat-events.mjs` | Trigger 120s expiry on stub engine; verify auto-approve log + no taskkill | Revert Phase 1 files; `IDUPI_UI_REQUEST_PHASE_A=0` |
| 2 | Interactive surface + stdin pipe | PR 2 | Same `node --check`; `node scratch/ui-request-smoke.mjs` | Spawn local stub child; observe JSON line on stdin | Revert route + stdio arrays; registry still resolves |
| 3 | Android wire-up: parser + card + client | PR 3 | `./gradlew :app:testDebugUnitTest --tests "*UiRequest*Parser*Card*"` | Emulator install + manual chat | Revert Kotlin files; old APK still works via Phase A |

## Phase 1: Server Foundation (Registry + Phase A)

- [x] 1.1 Create `idupi-server/lib/ui-request-registry.mjs` exporting `register/resolve/expire/currentTokenFor`
- [x] 1.2 Implement per-session monotonic `token` and exact-value validation in registry entries
- [x] 1.3 Implement blanket `Todo` auto-approve for `select`; `cancelled:true` for non-approvable
- [x] 1.4 Wire `IDUPI_UI_REQUEST_PHASE_A=1` short-circuit in `index.mjs` so old APKs unblock
- [x] 1.5 Default ON; 120s timer strictly < 300s `AGENT_CLI_TIMEOUT_MS`; constant in `lib/cli-constants.mjs`

## Phase 2: Server Transport (SSE + HTTP Route)

- [x] 2.1 Add `UI_REQUEST` + `UI_REQUEST_RESOLVED` to `idupi-server/chat-events.mjs`
- [x] 2.2 Add `POST /api/v1/chat/ui-response/:requestId` behind `requireAuth` in `index.mjs`
- [x] 2.3 Validate `body.{value,token,sessionId}` (select→exact, confirm→bool, input→non-empty); 409 BEFORE any stdin write
- [x] 2.4 Expiry path logs decision, emits `ui_request_resolved`, clears registry entry before the 300s taskkill

## Phase 3: Server Engine Adapters + Stdio

- [x] 3.1 Pi RPC: extend `extension_ui_request` handler (index.mjs:2230) to forward `select/confirm/input`
- [x] 3.2 Export pure `normalizeUiRequest()` from `idupi-server/lib/orchestrator/engines/{pi,opencode,claude}.mjs`
- [x] 3.3 Spawn Claude (`L3881–3911`) and OpenCode (`L4146`) with `["pipe","pipe","pipe"]`; retain `child.stdin`
- [x] 3.4 Per-engine defaults: OpenCode `--auto`, Claude `bypassPermissions`, Pi uses registry

## Phase 4: Android Model + Parser + Client

- [x] 4.1 Add `INPUT` to `UiRequestMethod` enum and `deadlineAt: Long` to `domain/model/UiRequest.kt`
- [x] 4.2 Map `ui_request` → `UiRequestReceived` in `data/remote/SseFrameParser.kt`; discard `_resolved` (DEBUG)
- [x] 4.3 Replace `value.toString()` in `RealIduPiClient.sendUiResponse` with structured JSON `{value, token, sessionId}`
- [x] 4.4 Pass + store `token`/`sessionId` in `domain/repository/IduPiClient.kt` and `viewmodel/ChatViewModel.kt`; hold on 409, clear on 200

## Phase 5: Android UI (Card + ChatScreen)

- [x] 5.1 Add INPUT branch (TextField + Submit + Cancel) and 1s countdown to `ui/components/UiRequestCard.kt`
- [x] 5.2 Inline `UiRequestCard` in `ui/screens/ChatScreen.kt` when `activeUiRequest` matches last SYSTEM message
- [x] 5.3 Leaving Chat does NOT cancel; return re-renders countdown with remaining time
- [x] 5.4 Out-of-date value (e.g. "C" not in options) keeps dialog open, surfaces server rejection

## Phase 6: Testing (RED-first + Smoke Harness)

- [x] 6.1 RED: `SseFrameParser` decodes `ui_request` + discards `_resolved` (JUnit4 + coroutines-test)
- [x] 6.2 RED: `UiRequestCard` INPUT submit + 1s countdown tick
- [x] 6.3 RED: `RealIduPiClient.sendUiResponse` posts structured JSON, never `value.toString()`
- [x] 6.4 RED: `respondToUiRequest` holds on 409, clears on 200 (Fake IduPiClient)
- [x] 6.5 Smoke `scratch/ui-request-smoke.mjs`: Pi select writes one stdin JSON line; 120s expiry auto-approves; stale 409
- [x] 6.6 Stub-reader RED tests for Claude + OpenCode `["pipe","pipe","pipe"]` (exactly one JSON line)
