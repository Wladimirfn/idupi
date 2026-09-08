# Delta for UI Request Selection

## MODIFIED Requirements

### Requirement: CLI Stdin Delivery (server)

The server MUST spawn engine CLIs with writable stdin (`pipe`), delivering each validated answer as one JSON line to it — except OpenCode, whose validated answers MUST reach the engine through the sidecar HTTP reply route (see `opencode-permission-sidecar`) and MUST NOT be written to stdin. Claude `bypassPermissions` MAY remain a launch fallback; later Claude requests use the same transport.
(Previously: every validated answer, OpenCode included, was delivered as a JSON line to engine stdin.)

#### Scenario: Exact value reaches stdin

- GIVEN the user chose "B" for a stdin-based engine
- WHEN the server resolves the request
- THEN the engine stdin receives a JSON line valued exactly "B"

#### Scenario: OpenCode answer rides the sidecar

- GIVEN the user approved an OpenCode permission card
- WHEN the server resolves the request
- THEN the answer travels as a sidecar HTTP reply and no stdin line is written

### Requirement: Terminality, Deadline, and Fallback (server)

The grace window MUST stay strictly below the 300s `AGENT_CLI_TIMEOUT_MS` backstop, defaulting to 120s. Every request MUST resolve exactly once (answer, expiry fallback, or cancellation) before it. Expiry MUST yield a terminal, logged resolution: auto-approved value, or `cancelled: true` where approval is inapplicable. The fallback MUST be blanket auto-approve for stdin-based engines. For OpenCode via the sidecar, expiry MUST resolve as CANCELLED: reject the permission through the sidecar reply route, log the expiry with source `auto_approve` and `cancelled: true`, and clear the registry entry.
(Previously: expiry blanket auto-approve applied to every engine, including OpenCode.)

#### Scenario: Expiry auto-approves (stdin engines)

- GIVEN a pending stdin-engine request unanswered at 120s (e.g. old APK)
- WHEN the grace expires
- THEN the server auto-approves per blanket policy, logs it, delivers a terminal resolution before the 300s taskkill, and clears the registry entry

#### Scenario: OpenCode expiry cancels

- GIVEN a pending OpenCode permission card unanswered at 120s
- WHEN the grace expires
- THEN the card resolves CANCELLED, the permission is rejected via the sidecar, the expiry log carries source `auto_approve` and `cancelled: true`, and the requested file is absent

### Requirement: Universal Engine Coverage (server)

Behavior MUST be identical across Pi, OpenCode, and Claude. Phase A auto-approve MAY be per-engine; app-mediated approval MUST exist for every engine issuing requests. OpenCode reaches parity through the sidecar transport: surfacing, validation, and expiry semantics match the other engines, while answer delivery rides the sidecar HTTP reply instead of stdin.
(Previously: OpenCode coverage assumed a `--auto` run answered over stdin.)

#### Scenario: OpenCode like Pi

- GIVEN OpenCode emits a permission request captured by the sidecar
- WHEN it pends
- THEN rendering and validation match Pi's; only delivery differs (sidecar reply, not stdin)
