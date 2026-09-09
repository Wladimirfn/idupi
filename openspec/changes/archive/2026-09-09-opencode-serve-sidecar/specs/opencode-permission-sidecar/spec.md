# OpenCode Permission Sidecar Specification

## Purpose

A persistent `opencode serve` sidecar gives OpenCode an answer path; `run --auto` self-approves and closed stdin cannot respond.

## Requirements

### Requirement: Sidecar Lifecycle

The server MUST spawn a persistent `opencode serve` process per OpenCode session, bound to loopback on an ephemeral port, track its sessions, and stop it with the session. Spawning MUST fail closed: if the sidecar or its event transport is unavailable and auto-approval is not explicitly enabled, the server MUST NOT relaunch OpenCode with self-approval.

#### Scenario: Spawn and teardown

- GIVEN a session starts with cards enabled
- WHEN the sidecar spawns on 127.0.0.1 with an ephemeral port
- THEN session IDs are tracked and session end shuts it down

#### Scenario: Transport failure fails closed

- GIVEN the toggle is OFF
- WHEN the sidecar or its event stream dies mid-session
- THEN the server does not relaunch OpenCode with self-approval

### Requirement: Event Subscription and Registry Mapping

The sidecar MUST subscribe to the OpenCode SSE `/event` stream and map events into the pending registry: `permission.asked` and `permission.v2.asked` become confirm requests (120s deadline); `question.asked` becomes a select request with the exact options. Repeated events for one requestID MUST NOT produce multiple cards.

#### Scenario: Permission becomes confirm card

- GIVEN an active SSE subscription
- WHEN OpenCode emits `permission.v2.asked`
- THEN one confirm card pends with a 120s deadline

#### Scenario: Question becomes select card

- GIVEN an active SSE subscription
- WHEN OpenCode emits `question.asked`
- THEN one select card pends with those exact options

### Requirement: Permission Reply Transport

The server MUST answer each permission by POSTing to OpenCode's reply route (`/api/session/{id}/permission/{requestID}/reply`); the legacy route MUST be documented as fallback. Approve MUST reply once; user rejection and registry expiry MUST reply reject. Replies MUST be idempotent: a 404 after expiry MUST be treated as already-cancelled and MUST NOT surface an error to the user.

#### Scenario: Approve replies once

- GIVEN a pending permission card
- WHEN the user approves
- THEN exactly one approve-once reply is POSTed and the card resolves approved

#### Scenario: Late reply after expiry

- GIVEN a card expired and was rejected
- WHEN approve is tapped afterwards
- THEN the reply returns 404, is treated as already-cancelled, and no error reaches the user

### Requirement: Vanish-Abort for Dead Turns

OpenCode serve can drop pending permissions within minutes, hanging the turn. The sidecar MUST detect a tracked permission vanishing without a reply and MUST abort that turn instead of hitting the backstop.

#### Scenario: Vanished permission aborts turn

- GIVEN a tracked permission pended for a running turn
- WHEN the permission disappears from OpenCode without a reply
- THEN the turn is aborted and the user is informed, not left hanging

### Requirement: Auto-Approve Toggle

The Settings toggle MUST default to OFF: OFF spawns the sidecar and surfaces cards; ON launches OpenCode with auto-approval. It MUST gate only the spawn path.

#### Scenario: Toggle OFF

- GIVEN the toggle is OFF
- WHEN OpenCode asks permission
- THEN a card appears and nothing self-approves

#### Scenario: Toggle ON

- GIVEN the toggle is ON
- WHEN a session starts
- THEN OpenCode auto-approves and no cards appear

### Requirement: Approval Scope and Preconditions

Approval MUST default to once per request; a saved approval (`permission.saved`) MUST be logged, since it suppresses future asks. The sidecar MUST verify OpenCode enforces `ask`; weaker configurations MUST disable card mediation with a clear reason, never silently.

#### Scenario: Saved approval logged

- GIVEN the user approved always
- WHEN OpenCode persists the approval (`permission.saved`)
- THEN it is logged and further asks for that operation stop surfacing cards

#### Scenario: Misconfigured permissions detected

- GIVEN OpenCode is configured to auto-approve below `ask`
- WHEN the sidecar runs its precondition check
- THEN card mediation is disabled and the reason is reported
