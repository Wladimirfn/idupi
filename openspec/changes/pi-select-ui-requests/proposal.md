# Proposal: Pi Select UI Requests

## Intent

Deliver a real, Pi-only `ask_user_question` `select` decision channel so Android can present the exact issued choices, return an authenticated exact value, or safely dismiss an unanswered decision without killing the persistent Pi process. This resolves the orphaned Android path and the pre-existing response-client defect while preserving Pi’s documented cancellation semantics.

## Scope

### In Scope
- Consume Change A’s authenticated opaque, session-bound SSE substrate and safe event transport; B MUST NOT apply before that infrastructure exists.
- Produce and transport Pi `ui_request` and terminal events from genuine `extension_ui_request.method=select` messages.
- Implement `PendingSelectRegistry` with authenticated session/project/`engine=pi-cli`/request-ID/options binding, exact rendered-value answers, and atomic answer/deadline/write races.
- Enforce an IDUPI-owned decision deadline strictly below five minutes; send documented `cancelled:true`, mark `cancelled`/expired terminally, and suspend/rearm the unchanged five-minute backstop.
- Preserve old-APK safety, authenticated point-of-subscriber reconnect replay, and Android `Map` state/render/terminal behavior.
- Correct `RealIduPiClient.sendUiResponse` JSON serialization and route contract.

### Out of Scope
- Generic live activity, heartbeat, or MCP detection (owned by Change A `live-cli-activity-visibility`).
- Claude/OpenCode decisions, `confirm`, Sessions, or changing the timeout constant/taskkill behavior.
- Custom extensions, forks, flags, `node_modules` edits, configuration mutation, or dependency installation.

## Capabilities

### New Capabilities
- `pi-select-ui-requests`: Authenticated Pi select requests, exact answers, cancellation, replay, terminality, and Android rendering.

### Modified Capabilities
- None.

## Approach

Wire the verified Pi RPC producer into a session-bound pending registry and shared SSE event stream. Validate context and issued options before stdin; use compare-and-set terminal transitions, one cancellation write, and tombstones for late/duplicate responses. Map requests into Android’s `Map<id, UiRequestState>` with disabled terminal controls and idempotent replay handling. Do not add a Pi extension or broaden Change A ownership.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `idupi-server/index.mjs`, `idupi-server/chat-events.mjs`, server registry | Modified | Pi producer, route, deadline/backstop coordination, event transport and replay. |
| `app/.../ChatEvent.kt`, `RealIduPiClient.kt`, `ChatViewModel.kt`, `UiRequestCard.kt`, `ChatScreen.kt` | Modified | Decode, respond, render, terminalize, and reconnect-deduplicate requests. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Cross-context leak or wrong option reaches Pi | High | Authenticate and bind every field; validate before stdin; point replay only. |
| Answer/deadline/write race strands Pi | High | Atomic terminal CAS, one write, failure terminal, rearm backstop, late-response tombstones. |
| Old APK ignores request | Med | Positive deadline sends documented cancellation before unchanged taskkill backstop. |

## Rollback Plan

Revert server registry/route/event changes and Android request surfaces together. Leave Change A’s SSE/activity infrastructure, timeout constant, and taskkill behavior unchanged; old APKs continue ignoring unknown events.

## Dependencies

- Exact prerequisite: `live-cli-activity-visibility`. B MUST NOT apply before A provides authenticated opaque session-bound SSE infrastructure and safe event transport.

## Success Criteria

- [ ] Live proof shows genuine Pi select, authenticated exact-value answer, terminal UI, and process survival.
- [ ] Live proof shows documented `cancelled:true` deadline path, Pi cancellation/continuation, no taskkill, and no orphan.
- [ ] Reconnect isolation, old-APK cancellation, late-response rejection, and route serialization are falsifiably verified.
