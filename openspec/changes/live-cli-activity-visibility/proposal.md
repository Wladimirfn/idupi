# Proposal: Live CLI Activity Visibility

## Intent

Make CLI/MCP work observable in Android chat without leaking activity across authenticated sessions or projects. This Change A is limited to activity visibility; dependent Change B owns Pi UI decisions.

## Scope

### In Scope
- Correlate start, update, honest 15-second in-flight heartbeat, end, failure, and timeout events across Claude, OpenCode, and Pi.
- Detect MCP generically from provider structure, not a tool-name allowlist. Show OpenCode `toolName` from start and add server enrichment without replacing it; expose Pi MCP status.
- Apply complete stderr/metadata redaction with Unicode-safe bounds and authenticated opaque, session-bound SSE isolation.
- Preserve old-APK graceful handling of unknown events.
- Prove a newly registered unknown MCP live with verified `PI_CODING_AGENT_DIR`, token, free port, authentication, hash, and cleanup; retain real SSE and Android evidence.

### Out of Scope
- Dependent Change B `pi-select-ui-requests`: all UI decision/replay/HTTP/RPC/pending/Android `UiRequest` work, including the pre-existing `sendUiResponse` defect.
- A MUST NOT modify `UiRequest`, `UiRequestCard`, or the response route.
- Sessions surfaces; changes to timeout or taskkill behavior; raw metadata, credentials, polling, invented events, and provider allowlists.

## Capabilities

### New Capabilities
- `live-cli-activity`: Correlated, redacted, session-isolated CLI/MCP activity and compatibility handling.

### Modified Capabilities
- None.

## Approach

Extend server normalization and authenticated SSE delivery, then map activity into Android’s existing event flow without introducing the Change B decision channel. Bound every field and redact stderr/metadata deterministically on Unicode-safe boundaries. Keep heartbeat semantics honest: unresolved in-flight activity, not proof of subprocess health. Validate the unknown-MCP harness end-to-end and clean up all temporary state.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `idupi-server/index.mjs`, `idupi-server/chat-events.mjs`, bridge/auth paths | Modified | Correlation, redaction, structural MCP, heartbeat, SSE isolation. |
| Android chat event/client/viewmodel surfaces | Modified | Decode and render activity plus unknown-event compatibility only. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Cross-session leakage or metadata disclosure | High | Opaque authenticated binding, strict isolation, bounded Unicode-safe redaction. |
| False liveness or provider-specific detection | Med | Explicit heartbeat semantics and unknown-MCP falsification. |

## Rollback Plan

Revert server and Android activity changes together; leave Sessions, timeout, and taskkill behavior unchanged. Old APKs ignore unknown events.

## Success Criteria

- [ ] Real SSE and Android evidence proves correlated start/update/15s heartbeat/end/failure/timeout across all three engines.
- [ ] Structural MCP detection and additive OpenCode identity pass the unknown-MCP harness with zero detection-code edits and verified cleanup.
- [ ] Redaction, auth, opaque session binding, project isolation, and old-APK compatibility are falsifiably verified.
