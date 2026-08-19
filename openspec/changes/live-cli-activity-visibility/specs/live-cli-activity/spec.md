# Live CLI Activity Specification

## Purpose

Expose honest, bounded, correlated activity from Claude, OpenCode, and Pi while preserving compatibility and process backstops.

## Requirements

### Requirement: Correlated lifecycle and heartbeat

Each activity MUST use one stable ID across start, provider update, heartbeat, end, failure, and timeout. While open, a visible correlated signal MUST include elapsed time or time since the last provider update, with no gap greater than 20 seconds. A heartbeat proves only that the server and operation remain open, not subprocess health; terminal state MUST stop heartbeats.

#### Scenario: Open, stale, and terminal operation
- GIVEN a tool or MCP operation starts, receives no provider update for 20 seconds, then succeeds, fails, or times out
- WHEN lifecycle events are rendered
- THEN all events share one ID, an in-flight signal appears before any gap exceeds 20 seconds, the UI distinguishes recent-update from stale in-flight and terminal state, and no heartbeat follows termination

### Requirement: OpenCode additive identity

OpenCode `toolName` MUST be visible at start even when server/MCP is unknown. `result.details.server` MUST enrich additively without replacing or hiding the initial name or ID; missing end MUST preserve them.

#### Scenario: Identity before or without end
- GIVEN `tool_execution_start` provides `toolName` without a server
- WHEN an end provides `result.details.server` or never arrives
- THEN the original name and ID are immediately visible and retained, with server added only when available

### Requirement: Structural Claude MCP detection

Claude MUST classify MCP only from an actual `tool_use` name matching `mcp__<server>__<tool>`. `ToolSearch` MUST remain generic deferred-tool activity, and detection MUST use zero tool/server allowlists.

#### Scenario: Deferred structural invocation
- GIVEN Claude emits `ToolSearch` followed by a structurally named `tool_use`
- WHEN events are normalized
- THEN only the latter is MCP activity and arbitrary future server/tool names remain supported

### Requirement: Pi MCP status and enrichment

Pi MUST classify generic MCP status only from `extension_ui_request` method `setStatus` with `statusKey=mcp`; start without a server MUST remain generic, and `result.details.server` MUST provide additive end enrichment.

#### Scenario: Generic Pi start
- GIVEN Pi starts without server details, emits MCP status, and later supplies server details
- WHEN activity is rendered
- THEN it is generic at start, visibly MCP during status, and additively enriched at end

### Requirement: Safe compatibility payloads

Metadata MUST be bounded and redacted before SSE; secrets, tokens, user paths, and sensitive arguments MUST NOT reach the client. Tool/server allowlists MUST be zero. An old consumer MUST ignore unknown SSE types without crashing, and the new server MUST NOT require an old-APK ACK.

#### Scenario: Redaction and old consumer
- GIVEN sensitive provider data and an old APK receive a new activity event without ACK
- WHEN the event is published
- THEN only bounded redacted metadata reaches the client, the APK remains alive, and the server completes normally

### Requirement: Falsifiable unknown MCP proof

Live verification MUST temporarily register and invoke a temporary NEW trivial MCP absent from existing integrations, prove visibility with zero detection-code edits, and clean up isolated registration/configuration on success or failure.

#### Scenario: Unknown provider registration
- GIVEN isolated configuration registers a credential-free temporary NEW MCP
- WHEN it is invoked and live evidence is retained
- THEN activity is visible without detection edits and registration/configuration is restored even after failure

### Requirement: Live evidence and unchanged backstops

Retained live transcript or screenshot/app evidence MUST cover Claude, OpenCode, and Pi, including a long-running operation with heartbeat. `AGENT_CLI_TIMEOUT_MS` and `taskkill` MUST remain unchanged backstops; this feature MUST NOT redefine timeout or task-kill behavior.

#### Scenario: Evidence review
- GIVEN real live captures exist for all three engines and long-running heartbeat
- WHEN the change is reviewed
- THEN captures prove lifecycle, honest states, identity/enrichment, and unchanged timeout/process-kill behavior
