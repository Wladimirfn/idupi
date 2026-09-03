# UI Request Selection Specification

## Purpose

When any engine CLI (Pi, OpenCode, Claude) asks to select, confirm, or input, the server surfaces it over chat SSE, the app answers via an authenticated route, and the exact answer reaches the engine's stdin — resolving before the 300s `AGENT_CLI_TIMEOUT_MS` backstop. `pi-select-ui-requests` stays separate.

## Requirements

### Requirement: UI Request Surfacing (server + app)

The server MUST add `ui_request` to `CHAT_EVENTS` and emit it (requestId, method, title, message, options, deadline) on any engine request. `SseFrameParser` MUST map it to `ChatEvent.UiRequestReceived`. Old APKs MAY ignore it.

#### Scenario: Select surfaced

- GIVEN Pi emits a select request with options ["A", "B"]
- WHEN the server processes it
- THEN the event (requestId, options, deadline) is emitted, parsed into `ChatEvent.UiRequestReceived`

### Requirement: Grace-Period Dialog (app)

ChatScreen MUST render pending requests with a countdown, supporting select buttons, confirm accept/reject, and free-text input. Leaving Chat MUST NOT cancel it: the request stays alive server-side and re-surfaces with remaining time.

#### Scenario: Select within grace

- GIVEN a pending select dialog
- WHEN the user taps an option before 120s
- THEN the exact option text is submitted

#### Scenario: Confirm within grace

- GIVEN a pending confirm dialog
- WHEN the user accepts before 120s
- THEN `true` is submitted

#### Scenario: Input within grace

- GIVEN a pending input request
- WHEN the user submits non-empty text before 120s
- THEN the exact text is submitted

#### Scenario: Alive on Chat exit

- GIVEN a pending dialog
- WHEN the user leaves Chat, returning before expiry
- THEN it re-renders with remaining time, answerable

### Requirement: Authenticated Answer Transport (server + app)

The server MUST expose authenticated `POST /api/v1/chat/ui-response/:requestId`. The app MUST send structured JSON, never stringified booleans. The server MUST validate answers (select: exact option text; confirm: boolean; input: non-empty text) in a single pending registry whose monotonic token binds answers to their session; stale tokens MUST be rejected.

#### Scenario: Exact value accepted

- GIVEN a pending select ["A", "B"]
- WHEN the app submits "B"
- THEN only the exact text "B" is accepted

#### Scenario: Invalid answer re-prompts

- GIVEN a pending select ["A", "B"]
- WHEN an out-of-date client submits "C"
- THEN the server rejects it; "C" never reaches the CLI; the dialog stays open

#### Scenario: Stale token rejected

- GIVEN request 1 superseded by request 2
- WHEN an answer for request 1's token arrives
- THEN it is rejected; only request 2 stays answerable

### Requirement: CLI Stdin Delivery (server)

The server MUST spawn engine CLIs with writable stdin (`pipe`), delivering each validated answer as one JSON line to it. Claude `bypassPermissions` MAY remain a launch fallback; later Claude requests use the same transport.

#### Scenario: Exact value reaches stdin

- GIVEN the user chose "B"
- WHEN the server resolves the request
- THEN the engine stdin receives a JSON line valued exactly "B"

### Requirement: Terminality, Deadline, and Fallback (server)

The grace window MUST stay strictly below the 300s `AGENT_CLI_TIMEOUT_MS` backstop, defaulting to 120s. Every request MUST resolve exactly once (answer, expiry fallback, or cancellation) before it. Expiry MUST yield a terminal, logged resolution: auto-approved value, or `cancelled: true` where approval is inapplicable. The fallback MUST be blanket auto-approve ("Todo": approve any request).

#### Scenario: Expiry auto-approves

- GIVEN a pending request unanswered at 120s (e.g. old APK)
- WHEN the grace expires
- THEN the server auto-approves per blanket policy, logs it, delivers a terminal resolution before the 300s taskkill, and clears the registry entry

### Requirement: Universal Engine Coverage (server)

Behavior MUST be identical across Pi, OpenCode, and Claude. Phase A auto-approve MAY be per-engine; app-mediated approval MUST exist for every engine issuing requests.

#### Scenario: OpenCode like Pi

- GIVEN OpenCode emits a permission request despite `--auto`
- WHEN it pends
- THEN rendering, validation, and stdin delivery match Pi's
