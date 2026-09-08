# Proposal: OpenCode Serve Sidecar for Permission Cards

## Intent

OpenCode sessions never surface permission cards: `opencode run --auto` self-approves and stdin is closed — no answer path exists; users lose control of sensitive operations.

## Scope

### In Scope

- Persistent `opencode serve` sidecar: spawn, SSE `/event`, session tracking, shutdown; transport failure → fail closed (no `--auto` unless toggle explicitly ON).
- `permission.asked` AND `permission.v2.asked` → registry (120s); `question.asked` → select; replies true→once, false/expiry→reject via `POST /api/session/{id}/permission/{requestID}/reply`; legacy route documented.
- Settings toggle (default OFF) wired to spawn path only after sidecar lands.

### Out of Scope

- Claude rewrite, ACP, audit telemetry.
- Pi confirm/input + registry sanitation → own change; collides with open `pi-select-ui-requests`.

## Capabilities

### New Capabilities

- `opencode-permission-sidecar`: serve lifecycle, SSE, permission/question mapping, idempotent replies, vanish-abort.

### Modified Capabilities

- `ui-request-selection`: stdin delivery replaced by sidecar HTTP reply; Terminality/Fallback: OpenCode expiry becomes cancel, not blanket auto-approve; Universal Engine Coverage via sidecar transport.

## Approach

Persistent `opencode serve` sidecar, NOT `run --port`: spikes proved `run` without `--auto` auto-rejects in ms, its server dying with it; `serve` blocks on ask. The sidecar maps events into the registry, replies fast, treats 404-after-expiry as already-cancelled (idempotent, never user-facing). Serve drops pending permissions within minutes, hanging the turn — vanished permissions abort it; no server fallback exists.

## Affected Areas

- `idupi-server/lib/agent-cmdline.mjs` — Modified — toggle-aware spawn
- `idupi-server/lib/opencode-sidecar.mjs` (new) — New — lifecycle, SSE, mapping, replies, vanish-abort
- `idupi-server/lib/pending-registry` — Modified — sidecar entries via existing API
- `app/` Settings — Modified — toggle (default OFF)
- Pi producer — Removed — own change

## Risks

- HTTP surface (Low) — loopback bind + ephemeral port (127.0.0.1 verified; no password flag in v1.18.29)
- `permission.saved`/`always` suppresses future asks (Med) — default "once"; log `permission.saved`
- Serve drops pending permissions (Med) — abort vanished turns; first spike measures expiry window
- Config below `ask` (Med) — precondition check

## Rollback Plan

Toggle re-enables legacy `run --auto`; full revert reverts the spawn path. Sidecar sessions: data persists in OpenCode storage but `run` cannot attach to those IDs; in-flight turns die with the sidecar and re-run fresh — within-session continuity lost.

## Dependencies

- `opencode serve` v1.18.29 (verified): SSE `/event`; reply route → 204; legacy route documented.
- Existing registry, token/SSE fan-out, `UiRequestCard`, resolve route; expiry window unmeasured → first spec/apply spike.

## Success Criteria

- [ ] File-create prompt shows a card over SSE
- [ ] Untouched card expires → CANCELLED, file absent, expiry log with source=auto_approve and value.cancelled=true
- [ ] Toggle ON = autopilot; OFF = cards; transport down + OFF → no `--auto`
- [ ] Late reply idempotent; vanished-permission turns abort, not hang
