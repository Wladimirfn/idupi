# Design: OpenCode Serve Sidecar for Permission Cards

## Technical Approach

Persistent `opencode serve` per session is the answer path. `run --port` self-approves or dies; `serve` blocks on `/event` and accepts a real HTTP reply. The sidecar maps SSE into the existing `PendingUiRequestRegistry` (no schema drift) and posts to the reply route. Existing `POST /api/v1/chat/ui-response/:requestId` (index.mjs:3114) stays the only UI-facing sink. The toggle gates the spawn path.

## Architecture Decisions

| # | Decision | A | B | Chosen | Why |
|---|---|---|---|---|---|
| D1 | Process model | one `serve` per session | server-wide, many `session.create` | A | Matches `run --session-id`; isolates blast radius; v1.18.29 has no bus session listing. |
| D2 | Bind | `0.0.0.0` | `127.0.0.1` ephemeral | B | Local-only; v1.18.29 has no auth. |
| D3 | Health check | TCP connect | `GET /health` (3s) | B | TCP returns OK on half-dead serve; HTTP catches hung event loop. |
| D4 | SSE consumer | `EventSource` | own minimal SSE parser | B | Wire is small (`event:`/`data:`, `:` heartbeats). |
| D5 | Fail-closed | lazy (first event) | eager probe at spawn | B | Lazy fires only after OpenCode's first ask — self-approve already irreversible. |
| D6 | Reject on expiry | local timer → POST | wait for `serve` to drop | A | Deterministic 120s ceiling; D7 is the safety net. |
| D7 | Vanish | `permission.removed` SSE | + `GET /api/permission` snapshot | B+fallback | v1.18.29 may not emit `removed`; on idle-turn + missing id, abort. |
| D8 | Toggle scope | toggles spawn + run flags | gates spawn only | B | Spec: "It MUST gate only the spawn path." ON=`run --auto`; OFF=sidecar. |

## Data Flow

    toggle=OFF ─► runOpenCodeCli ─► OpenCodeSidecar.spawn()  (127.0.0.1:ephermeral, GET /health 3s)
                                            │ SSE /event
                                            ▼
                  sseLoop map:  permission.asked | v2.asked → confirm(120s)
                                question.asked                → select(exact options)
                                permission.saved              → log + suppress
                                permission.removed            → vanish-abort
                                            │
                                            ▼
                  uiRequestRegistry.register({engine:"opencode"})  ─►  existing UI_REQUEST SSE  ─►  app card
                                            │
            POST /api/v1/chat/ui-response/:id (existing) ─► sidecar.reply(requestId, value)
                                            │
                                            ▼
                  POST /api/session/{sid}/permission/{pid}/reply
                  204→ok; 404→{expired:true} (no throw, no user-facing error)

Wire: `entry.engine="opencode"`; `ui_request` SSE frame unchanged; writer map routes `resolve()` value to `sidecar.reply()` (mirrors `uiRequestStdinWriters` at index.mjs:149).

## File Changes

| File | Action | Description |
|---|---|---|
| `idupi-server/lib/opencode-sidecar.mjs` | **Create** | `OpenCodeSidecar`: `spawn` (127.0.0.1:0, 3s `GET /health`), `subscribeEvents` (4 callbacks), `reply` (204/204/404, never throws), `listPendingPermissions` (D7), `shutdown` (SIGTERM 2s + SIGKILL), `baseUrl`. Zero new deps; `node:http` + minimal SSE parser. |
| `idupi-server/lib/agent-cmdline.mjs` | **Modify** | `openCodeArgs(opts)` adds `autoApprove` (drops `--auto` when `false`). Hostile-message test invariant unchanged. |
| `idupi-server/index.mjs` | **Modify** | `runOpenCodeCli` (L4704) branches on `autoApprove`: ON→legacy; OFF→sidecar first, then `spawn("opencode", ...)` + `child.stdin.end()` (L4766). New `setUiRequestSidecarWriter` / `clearUiRequestSidecarWriter` mirror the stdin seam (L149-187). `expire` listener (L102) routes `engine==="opencode"` to `sidecar.reply(false)`. `permission.saved` logged from sidecar's saved handler. |
| `idupi-server/lib/ui-request-registry.mjs` | **Modify** | No public-API change. Sidecar is a caller, not a registry feature. |
| `app/.../ui/screens/SettingsScreen.kt` | **Modify** | New `AutoApproveSection` mirroring `GeneralSection` Switch (L169); wires `SettingsRepo.opencodeAutoApprove: Boolean` (default `false`) via DataStore. |
| `app/.../data/remote/*.kt` | **Modify** | Carry `opencodeAutoApprove` on every chat request (header `X-OpenCode-Auto-Approve: 0\|1`). |
| `idupi-server/test/opencode-sidecar.test.mjs` | **Create** | `node --test`; fakes `spawn` + `node:http`; covers mapping, idempotency, fail-closed, vanish. |
| `scratch/serve-expiry-spike.mjs` | **Create** | Manual. Real `opencode serve`; time-to-vanish. **First `sdd-apply` task**; output pins D7. |

## Testing Strategy

| Layer | What | How |
|---|---|---|
| Unit (mapping) | `permission.asked` / `v2.asked` / `question.asked` → canonical; dedup on repeat `requestID` | `node --test idupi-server/test/opencode-sidecar.test.mjs`; fake SSE child. |
| Unit (idempotency) | 204 true; 204 false; 404 → no error, no second `expire` | same file, mocked reply. |
| Unit (fail-closed) | health 3s timeout + toggle OFF → reject before spawning `opencode` | same file, stub `/health`. |
| Integration | real `opencode serve`; time-to-vanish | `scratch/serve-expiry-spike.mjs` — first `sdd-apply` task; informs D7. |
| E2E | file-create prompt → card → approve → file; untouched → CANCELLED | emulator + server, toggle OFF. |

## Threat Matrix

| Boundary | Case | Applicability | Design response | RED test |
|---|---|---|---|---|
| Persistent process | `serve` survives request; transport dies | Applicable | Health probe 3s at spawn; mid-session death → in-flight requests get 404 idempotent path. | Fail-closed test. |
| Local-port exposure | 127.0.0.1 ephemeral port accessible to other local users | Applicable | Bind 127.0.0.1 only; ephemeral; v1.18.29 has no auth → document residual risk. | Probe assertion; `ss -tlnp` in spike. |
| Answer-vs-deadline race | user taps approve at 120s; reply fires after expiry | Applicable | Expiry first calls `sidecar.reply(false)`; late `resolve()` is no-op; `sidecar.reply` treats 404 as already-cancelled. | Idempotency test with 404. |
| Reconnect replay | SSE drop mid-event; reconnect replays same `requestID` | Applicable | `seen = new Set(requestID)` per session; dedup before `register()`. | Repeat-dedup test. |
| Shell / VCS / exec-class | none in scope | N/A — argv injection pinned by `agent-cmdline.test.mjs`; sidecar takes a parsed `sessionId` validated by `assertSafeCliId`. | — | — |

## Migration / Rollout

Order is the only way "Transport failure fails closed" can hold: (1) land `opencode-sidecar.mjs` + tests, **no spawn change yet** — `openCodeArgs` keeps `--auto`; (2) land the `runOpenCodeCli` branch (toggle=OFF → sidecar, ON → legacy); (3) land Settings toggle + Android wiring, DataStore default `false`; (4) rollback: toggle=ON reverts; full revert removes step 2. Sidecar sessions persist on disk but `run` cannot attach — in-flight turns die with the sidecar.

## Open Questions

- [x] Real expiry window under v1.18.29 — drives D7 fallback. Resolved by
      PR 1 spike (`scratch/serve-expiry-spike.mjs`): on idle no
      `permission.removed` arrives; real-session behaviour remains
      unverified until a live permission fires in PR 2+ integration.
      **D7 verdict (PR 1)**: snapshot fallback via
      `listPendingPermissions()` MUST stay in place.
- [x] Whether v1.18.29 emits `permission.removed` — D7 primary vs. fallback.
      Same spike: **no** `permission.removed` in idle; primary signal is
      unreliable; snapshot fallback is the safety net.
- [x] **Reply-route shape** (`/api/session/{sid}/permission/{rid}/reply`
      vs `/session/{id}/permissions/{permissionID}`) — resolved by
      `scratch/reply-path-probe.mjs` (PR 2 verification). Empirical
      evidence against the real `opencode serve` v1.18.29 binary:

      | Path | Status | Body | Verdict |
      |------|--------|------|---------|
      | `POST /api/session/syn-sid/permission/syn-rid/reply` | **400** | `{"_tag":"InvalidRequestError","message":"Invalid session ID","field":"sessionID"}` | **The design-intent path is correct.** Structured request-validation error from the engine's typed pipeline. |
      | `POST /api/session/syn-sid/permission/syn-rid` | 200 | HTML (web UI index page) | Web UI fallback. Not the API. |
      | `POST /session/syn-sid/permissions/syn-rid` | 500 | `{"name":"UnknownError",...}` | Wrong shape; engine errored. |
      | `POST /api/session/syn-sid/permissions/syn-rid` | 200 | HTML (web UI index page) | Web UI fallback. Not the API. |

      **Resolution**: the design's `REPLY_PATH` constant stays
      `/api/session/{sid}/permission/{rid}/reply` (matches the engine's
      request-validation pipeline; the v1.18.29 SDK plural-shape hypothesis
      was wrong). The sidecar's idempotency contract (204 ok / 404 expired
      / never throw) is unchanged.
