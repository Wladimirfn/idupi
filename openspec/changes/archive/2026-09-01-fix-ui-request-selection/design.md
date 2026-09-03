# Design: Universal UI Request Selection

## Technical Approach

Engine-agnostic UI request channel. A single `PendingUiRequestRegistry` owns `select`/`confirm`/`input` lifecycle from Pi, OpenCode, Claude. Server emits a new `ui_request` SSE frame `(requestId, token, method, title, message, options, deadlineMs)`; app renders an in-place grace-period dialog and POSTs `{ value, token, sessionId }` to `/api/v1/chat/ui-response/:requestId`; server validates and writes one JSON line to the CLI's writable stdin. Grace expiry → blanket Phase-A auto-approve (`Todo` or `cancelled:true`). Reuses `ChatEvent.UiRequestReceived` + `UiRequestCard`.

## Architecture Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Registry | Single `PendingUiRequestRegistry` keyed on `requestId`; per-session monotonic `token` | Universal coverage; spec mandates monotonic-token binding |
| Transport | Existing SSE hub + new `CHAT_EVENTS.UI_REQUEST`; new POST behind `requireAuth` | SSE substrate live; minimal additive surface |
| CLI stdin | Claude + OpenCode `stdio: ["pipe","pipe","pipe"]` (was `["ignore",...]`); Pi keeps default | Resolver needs writable stdin |
| Deadline | 120s default; MUST stay < 300s `AGENT_CLI_TIMEOUT_MS` | Spec value; one constant keeps timer-vs-backstop invariant obvious |
| Auto-approve | Blanket `Todo` for `select`; `cancelled:true` for non-approvable; expiry logged | Phase-A contract; spec requires terminal fallback |
| Stale-token | `body.token === entry.token === session.currentToken`; mismatch → 409 | Spec: stale tokens MUST be rejected |
| Old-APK | Parser's unknown-type fallback logs DEBUG + discards; registry still auto-resolves | "Old APKs MAY ignore it"; Phase-A unblock stays |
| Engine producer | Pi: extend `extension_ui_request` handler (index.mjs:2230) with select/confirm/input. OpenCode/Claude: each adapter exposes pure `normalizeUiRequest` | One normalizer keeps registry engine-agnostic |

## Data Flow

```
engine CLI → adapter.normalizeUiRequest() → registry.register()
 → SSE: ChatEvent.UI_REQUEST
 → SseFrameParser → ChatEvent.UiRequestReceived
 → UiRequestCard renders 120s countdown + actions
 → POST /api/v1/chat/ui-response/:requestId  body:{value,token,sessionId}
 → requireAuth → registry.resolve() validates
 → child.stdin.write(JSON.stringify({...}) + "\n") → engine CLI

Expiry: setTimeout(120s) → registry.expire() → writes auto-approved
(or cancelled:true) → emits ui_request_resolved → clears entry.
```

## File Changes

| File | Action |
|---|---|
| `idupi-server/lib/ui-request-registry.mjs` | Create: register/resolve/expire/currentTokenFor/count. |
| `idupi-server/chat-events.mjs` | Modify: add `UI_REQUEST` + `UI_REQUEST_RESOLVED`. |
| `idupi-server/index.mjs` | Modify: Pi RPC forward select/confirm/input; Claude (L3881–3911) + OpenCode (L4146) spawn `["pipe",...]` + retain `child.stdin`; new POST handler with `requireAuth`; `IDUPI_UI_REQUEST_PHASE_A=1` auto-approves on expiry. |
| `idupi-server/lib/orchestrator/engines/{pi,opencode,claude}.mjs` | Modify: each exposes pure `normalizeUiRequest`. |
| `app/.../domain/model/UiRequest.kt` | Modify: add `INPUT` + `deadlineAt: Long`. |
| `app/.../data/remote/SseFrameParser.kt` | Modify: `ui_request` → `UiRequestReceived`; `_resolved` → no UI. |
| `app/.../ui/components/UiRequestCard.kt` | Modify: INPUT branch (TextField+Submit+Cancel); 1s countdown; 409 keeps card alive. |
| `app/.../ui/screens/ChatScreen.kt` | Modify: inline `UiRequestCard` when `activeUiRequest` matches last SYSTEM message. Leaving Chat does NOT cancel. |
| `app/.../data/remote/RealIduPiClient.kt` | Modify: `sendUiResponse(reqId, value, token, sessionId)` structured JSON (replaces `value.toString()` bug); 409 → re-prompt. |
| `app/.../viewmodel/ChatViewModel.kt` + `domain/repository/IduPiClient.kt` | Modify: pass + store token/sessionId; hold request on 409. |

## Interfaces / Contracts

```kotlin
enum class UiRequestMethod { CONFIRM, SELECT, INPUT }
data class UiRequest(val id: String, val method: UiRequestMethod,
    val title: String, val message: String,
    val options: List<String> = emptyList(), val deadlineAt: Long = 0L)
suspend fun sendUiResponse(requestId: String, value: Any, token: String, sessionId: String)
```
```javascript
UI_REQUEST: "ui_request"
UI_REQUEST_RESOLVED: "ui_request_resolved"
// POST /api/v1/chat/ui-response/:requestId  body:{value,token,sessionId}  → 200|400|404|409
```

## Testing Strategy

| Layer | What | Approach |
|---|---|---|
| Android unit | Parser decodes ui_request/_resolved; UiRequestCard INPUT + countdown; sendUiResponse typed JSON | JUnit4 + coroutines-test; RED first (`apply.tdd: true`) |
| Android integration | respondToUiRequest holds on 409, clears on 200 | Fake IduPiClient |
| Server smoke | Pi select writes one stdin JSON line; 120s expiry auto-approves; 409 stale; `node --check` on every changed `.mjs` | Manual Node harness in `scratch/` |

## Threat Matrix

Applicable — changes Claude/OpenCode spawning (`stdio[0]` `"ignore"` → `"pipe"`) and adds an HTTP route writing to child stdin.

| Boundary | Design response | RED test |
|---|---|---|
| Claude `cmd.exe /c <shim>` + `["pipe","pipe","pipe"]` | `shell:false`/`windowsHide:true` already; writer captures `child.stdin`, writes validated JSON only | Spawn with stubbed stdin reader; assert exactly one JSON line |
| OpenCode `["pipe","pipe","pipe"]` | Same; writer held on local `child` inside `runOpencodeOnce` | Same stub-reader pattern |
| Pi RPC persistent child | N/A — already pipe | — |
| `POST /api/v1/chat/ui-response/:requestId` | `requireAuth` first; registry rejects 404/409 BEFORE any stdin write | 401 no token; 404 unknown; 409 stale; 200 + stdin line |
| VCS/PR/executable-file | N/A | — |

## Migration / Rollout

Two-phase behind `IDUPI_UI_REQUEST_PHASE_A=1` (default ON):
- **Phase A (no client)**: registry auto-approves after 120s with `Todo`/`cancelled:true`. `autoApproveOnly` short-circuits SSE emit. Old APKs ignore.
- **Phase B (after app ships)**: flip flag → SSE emit live; app answers within 120s; expiry still auto-approves.
- **Rollback**: revert the table; remove `UI_REQUEST`; revert Claude/OpenCode stdio to `["ignore","pipe","pipe"]`.

## Open Questions

None blocking. `pi-select-ui-requests` sibling stays separate per proposal Q3; this design supersedes its registry concept without modifying its files.
