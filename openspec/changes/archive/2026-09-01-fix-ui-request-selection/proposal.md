# Proposal: Universal UI Request Selection

## Intent

When any engine CLI (Pi, OpenCode, Claude) asks the user to select or approve, the app never shows it: no `ui_request` SSE is emitted and CLIs spawn with stdin ignored, so prompts hang until the 300s `AGENT_CLI_TIMEOUT_MS` backstop kills the process (observed: OpenCode PID 23708). Exploration confirmed 5 broken links and orphaned Android UI infrastructure (UiRequest, UiRequestCard, ChatEvent.UiRequestReceived, sendUiResponse) needing only wiring.

## Scope

### In Scope
- Phase A (immediate unblock): server auto-resolves any ui_request per engine; revisit OpenCode `--auto` and Claude `bypassPermissions`.
- Phase B: emit `ui_request` SSE (new CHAT_EVENTS type), render grace-period dialog in app, return exact answer via CLI stdin (spawn `pipe`) or engine API; expiry falls back to auto-approve.
- Route `POST /api/v1/chat/ui-response/:requestId`; fix `sendUiResponse` serialization.
- Reuse `pi-select-ui-requests` concepts (PendingSelectRegistry, deadlines, old-APK safety), generalized to all engines.

### Out of Scope
- Changing `AGENT_CLI_TIMEOUT_MS`/taskkill backstop.
- Console-screen double `[chat-stream]` SSE subscribers (separate issue).
- Pi extensions, forks, `node_modules`, config mutation.

## Capabilities

### New Capabilities
- `ui-request-selection`: engine-agnostic ui_request SSE surfacing, grace-period dialog, authenticated answer transport, auto-approve fallback, terminality.

### Modified Capabilities
- None. `pi-select-ui-requests` is an unarchived sibling change to reconcile, not a main spec.

## Approach

Hybrid, universal. Phase A guarantees non-blocking runs (server-side auto-resolution per engine). Phase B adds human-in-the-loop on Change A's applied SSE substrate: grace window strictly below the 300s backstop; no answer → auto-approve.

## Affected Areas

Touches BOTH components (config rule: separate surfaces).

| Area | Component | Impact |
|---|---|---|
| `index.mjs`, `chat-events.mjs`, `lib/agent-cmdline.mjs` | idupi-server | ui_request forwarding, route, stdin pipe, auto-approve, spawn stdio |
| `SseFrameParser.kt`, `ChatScreen.kt`, `ChatBubble.kt`, `ChatViewModel.kt`, `RealIduPiClient.kt`, `UiRequestCard.kt` | app/ | parse, render, respond — wire orphaned infra |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Wrong/leaked answer reaches CLI | Med | Session-bound registry, exact-value validation |
| Auto-approve runs unwanted tool | Med | Log auto-decisions; restrict defaults (Q1) |
| Old APK ignores ui_request | Low | Expiry fallback keeps runs unblocked |
| Overlap with pi-select-ui-requests | Med | Reconcile ownership (Q3) |

## Rollback Plan

Revert Android wiring and server event/route/stdin changes together; Phase A flags revert independently. Old APKs ignore unknown SSE events.

## Dependencies

- Change A `live-cli-activity-visibility` SSE substrate (applied; remaining tasks harness-only).
- Reconciliation decision with `pi-select-ui-requests` (Pi-only, unapplied).

## Success Criteria

- [ ] Pending selection never reaches the 300s kill on any engine.
- [ ] App renders real options; exact chosen value reaches the CLI.
- [ ] Grace expiry auto-approves; run continues; decision logged.
- [ ] Old APK runs complete via Phase A fallback.
