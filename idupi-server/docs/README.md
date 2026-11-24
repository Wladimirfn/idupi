# IDUPI server — documentation index

The IDUPI server is a zero-dependency Node.js HTTP server that runs on the user's
Windows PC. It owns the interactive PTY terminals, the PC-to-phone APK deliveries,
the system metrics and process list, the remote screen stream and the legacy agent
chat endpoints. The Android app and the PC CLIs talk to it over LAN or Tailscale.

Everything except the loopback-only browser terminal page requires the bearer token.

## Index

| Document | Contents |
|----------|----------|
| [`phone.md`](phone.md) | Phone agent contract for PC agents: status snapshot, command queue, `idupi-phone` CLI, screen dump format |
| [`sensors.md`](sensors.md) | Elevated sensor helper: install, uninstall, pinned hashes, version bumps |
| [`../../README.md`](../../README.md) | Product overview, quick start, app screens, build |
| [`../../PRIVACY.md`](../../PRIVACY.md) | Privacy policy |

## Authentication

- The token is read from `IDUPI_TOKEN` or from `.idupi-token` at the repository root.
  It is generated on first start and printed in full only then (or with
  `IDUPI_SHOW_TOKEN=1`); later starts print a masked value.
- Every `/api/v1/*` route requires `Authorization: Bearer <token>`. A missing or
  wrong token answers `401` with `WWW-Authenticate: Bearer`.
- The only exception is the `/terminal/*` static page, which answers before the
  token check **but only for loopback clients** (`127.0.0.1` / `::1`). Non-loopback
  clients get `403`, so the page is never exposed to the LAN or Tailscale.
- Handlers split out of `index.mjs` (`lib/pty-routes.mjs`, `lib/delivery-routes.mjs`,
  `lib/system-metrics-routes.mjs`, `lib/os-processes-routes.mjs`,
  `lib/screen-routes.mjs`, `lib/inbox-routes.mjs`, `lib/phone-routes.mjs`,
  `lib/phone-calendar-routes.mjs`) are mounted after `requireAuth`.

## API routes

### PTY terminals

Source: [`lib/pty-routes.mjs`](../lib/pty-routes.mjs), manager in
[`lib/pty-manager.mjs`](../lib/pty-manager.mjs).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/pty` | Bearer | List live terminals |
| POST | `/api/v1/pty` | Bearer | Create a terminal; body `{cols, rows, cwd?}`, answers `201 {id, pid, ...}` |
| GET | `/api/v1/pty/{id}/stream` | Bearer | Chunked `application/octet-stream`: 256 KiB replay ring, then live output |
| POST | `/api/v1/pty/{id}/input` | Bearer | Send input; body `{data}` (UTF-8 string) |
| POST | `/api/v1/pty/{id}/resize` | Bearer | Resize the PTY; body `{cols, rows}` |
| DELETE | `/api/v1/pty/{id}` | Bearer | Kill the terminal and its whole process tree; `404` when unknown |

A PTY has one size; the last client to resize wins. Reconnecting to `stream`
replays the ring before live bytes, so the phone, the browser page and
`idupi-term` can share one terminal.

Each listed terminal also carries `context`: the live `cwd` (read from the PEB
of the running agent, falling back to the shell and then the launch cwd), the
git `project` and `branch` (`detached: true` with the short sha as branch), and
the `agent` running in it (`{id, name, pid}`, or `null` when only the shell is
there). Source: [`lib/pty-context.mjs`](../lib/pty-context.mjs); it is sampled
at most every 3 s, only while terminals exist, and any failure degrades to
`null` fields instead of failing the list.

### APK deliveries

Source: [`lib/delivery-routes.mjs`](../lib/delivery-routes.mjs), registry in
[`lib/delivery-registry.mjs`](../lib/delivery-registry.mjs).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/deliveries` | Bearer | List published deliveries |
| POST | `/api/v1/deliveries` | Bearer | Register an existing `.apk`; body `{path}` (absolute, regular file, max 300 MB); snapshots it and returns `{id, name, size, sha256, createdAt}` |
| GET / HEAD | `/api/v1/deliveries/{id}/download` | Bearer | Stream the APK snapshot with `Content-Length`; HEAD returns headers only |
| DELETE | `/api/v1/deliveries/{id}` | Bearer | Remove a delivery |

Snapshots live in `idupi-server/deliveries/` (index plus `dlv-*.apk`); the newest
10 entries are kept. The hash is computed while copying, so later rebuilds do not
change the bytes of an already-published delivery.

### PC observability

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET / HEAD | `/api/v1/system/metrics` | Bearer | CPU/RAM usage, GPU usage/temp/power/VRAM, CPU temp and power, storage per disk; unknown sensors are `null` |
| GET / HEAD | `/api/v1/os/processes` | Bearer | All PC processes with memory, CPU, category and protection flag (2 s server cache) |
| POST | `/api/v1/os/processes/{pid}/kill` | Bearer | End a process; `409 protected` for critical system processes, `404` when gone |

Sources: [`lib/system-metrics.mjs`](../lib/system-metrics.mjs),
[`lib/os-processes.mjs`](../lib/os-processes.mjs). CPU temperature and power come
from the elevated helper described in [`sensors.md`](sensors.md) when it is
installed; GPU data comes from `nvidia-smi`.

### Terminal browser page

Source: [`lib/terminal-web-routes.mjs`](../lib/terminal-web-routes.mjs), page in
[`web/`](../web).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET / HEAD | `/terminal/` | None; **loopback only** | Static page that lists, creates and attaches to PTYs |
| GET / HEAD | `/terminal/app.js`, `/terminal/app.css` | None; loopback only | Page assets |
| GET / HEAD | `/terminal/vendor/xterm.js`, `xterm.css`, `addon-fit.js` | None; loopback only | Vendored xterm.js reused from the app |

`/terminal` redirects `301` to `/terminal/`. The page keeps the token in the URL
fragment, stores it in `sessionStorage` and sends it as `Authorization: Bearer`
to the API.

### App data

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/status` | Bearer | Server and active-engine status |
| GET | `/api/v1/projects` | Bearer | Registered projects |
| POST | `/api/v1/projects/switch` | Bearer | Activate a project |
| POST | `/api/v1/projects/add` | Bearer | Register a project |
| POST | `/api/v1/projects/remove` (also `/delete`) | Bearer | Remove a project |
| DELETE | `/api/v1/projects/{id}` | Bearer | Remove a project by id |
| GET | `/api/v1/projects/{id}/files` | Bearer | List project files |
| GET | `/api/v1/projects/{id}/file-content?path=` | Bearer | Read one project file |
| GET | `/api/v1/fs/browse` | Bearer | Browse the filesystem to add a project |
| GET | `/api/v1/models` | Bearer | Available models |
| POST | `/api/v1/model/switch` | Bearer | Select a model |
| GET | `/api/v1/commands` | Bearer | Available commands |
| GET | `/api/v1/alerts` | Bearer | Supervisor alerts |

### Phone inbox

Source: [`lib/inbox-routes.mjs`](../lib/inbox-routes.mjs), store in
[`lib/inbox-store.mjs`](../lib/inbox-store.mjs), agent runner in
[`lib/inbox-agent.mjs`](../lib/inbox-agent.mjs).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/inbox/messages` | Bearer | Accept a captured notification (`app`, `appLabel`, `packageName`, `sender`, `conversation`, `text`, `postedAt`, `notificationKey`, optional `digestOnly`); `app` is any followed app (preset id or a package name matching `^[A-Za-z0-9_.]{1,120}$`); `appLabel` is the optional PackageManager display name (trimmed, clipped to 60 chars); `digestOnly: true` stores the item as `ready` / `low` / category `Silenciado` without running the per-message agent; `202` with the stored item, `200` when the same `notificationKey` + `text` already exists |
| GET | `/api/v1/inbox?limit=50` | Bearer | Newest-first items; `limit` is 1..200 |
| POST | `/api/v1/inbox/{id}/state` | Bearer | Set `state` to `open`, `handled`, `replied`, `auto_replied` or `dismissed` |
| POST | `/api/v1/inbox/{id}/retry` | Bearer | Re-queue the analysis; `202` with the item as `analyzing` |
| POST | `/api/v1/inbox/digest` | Bearer | Summarize a conversation's open items (newest 60, ~12k chars of text) through the configured agent; `202` while `analyzing`, `200` with the cached digest when the included ids + texts did not change |
| GET | `/api/v1/inbox/digest?app=&conversation=` | Bearer | Current digest state `{app, conversation, itemCount, summary, pendingForOwner, suggestedReply, status, error, updatedAt}` |
| POST | `/api/v1/inbox/conversation/state` | Bearer | Close every open item of one `{app, conversation}` as `handled` or `dismissed` in one call |
| GET | `/api/v1/inbox/config` | Bearer | `{agent, model, effort, agents, efforts}` |
| PUT | `/api/v1/inbox/config` | Bearer | Validate and persist `{agent, model, effort}` |
| GET | `/api/v1/inbox/models?agent=opencode` | Bearer | Cached OpenCode `provider/model` list; other agents return `[]` |

Items and the agent config are persisted in `idupi-server/data/` (gitignored):
`inbox.json` keeps the newest 500 items, `inbox-config.json` keeps
`{agent, model, effort}` and `inbox-digests.json` keeps one digest per
conversation (pruned when the conversation has no open items). Analysis runs one
agent process at a time, with the message treated as untrusted data and every
agent invoked without a shell from an empty temp directory; the digest reuses
the same runner, queue, no-tools hardening and per-message delimited blocks, and
is keyed by a hash of the included item ids + texts. Auto-reply is a phone-side
policy; this server only stores state.

### Phone agent

Source: [`lib/phone-routes.mjs`](../lib/phone-routes.mjs), store in
[`lib/phone-store.mjs`](../lib/phone-store.mjs). Full contract, including the
screen dump format, in [`phone.md`](phone.md).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/phone/status` | Bearer | Phone reports a status snapshot (object, max 64 KiB); stored as latest plus a sampled 24 h history |
| GET | `/api/v1/phone/status` | Bearer | `{ latest, history, online, lastSeenAt }`; `online` means status or poll in the last 2 min |
| GET | `/api/v1/phone/commands/next?wait=25` | Bearer | Phone long-poll: oldest pending command marked `delivered`, or `204` after 1..30 s |
| POST | `/api/v1/phone/commands` | Bearer | PC queues `{type, args}`; `201 {command}` |
| GET | `/api/v1/phone/commands/{id}?wait=30` | Bearer | PC waits until `done`/`failed`/`expired` or 0..30 s |
| POST | `/api/v1/phone/commands/{id}/result` | Bearer | Phone reports `{ok, result?, error?}` |
| GET | `/api/v1/phone/commands?limit=20` | Bearer | Recent commands, newest first |

The status file is `idupi-server/data/phone-status.json` (gitignored); the
command queue is memory-only and pending commands expire after 60 s. Control
is off by default and requires the app's accessibility grant plus its master
switch.

### Phone calendar

Source: [`lib/phone-calendar-routes.mjs`](../lib/phone-calendar-routes.mjs),
store and math in [`lib/phone-calendar-store.mjs`](../lib/phone-calendar-store.mjs).
Agent contract and wire examples in [`phone.md`](phone.md).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/phone/calendar` | Bearer | Phone replaces the snapshot: `{capturedAt, rangeStart, rangeEnd, tzOffsetMinutes, calendars[], events[]}` (max 1000 events, 512 KiB); strict ISO-8601 validation |
| GET | `/api/v1/phone/calendar?from=&to=` | Bearer | `{capturedAt, storedAt, rangeStart, rangeEnd, tzOffsetMinutes, calendars, events, conflicts}`; events sorted by start, `conflicts` are deterministic overlapping non-all-day pairs |
| GET | `/api/v1/phone/calendar/free?from=&to=&min=30` | Bearer | `{from, to, minMinutes, tzOffsetMinutes, hasSnapshot, slots}` between 08:00 and 20:00 local |
| POST | `/api/v1/phone/calendar/proposals` | Bearer | PC queues `{title, start, end?, allDay?, location, notes, calendarId?, source}`; `201 {proposal, tzOffsetMinutes}` |
| GET | `/api/v1/phone/calendar/proposals?status=` | Bearer | Proposals newest first; `status` is `pending`, `approved`, `rejected`, `expired` or `all` |
| POST | `/api/v1/phone/calendar/proposals/{id}/decision` | Bearer | Phone reports `{decision: "approved"\|"rejected", eventId?}`; `409` when already decided or expired |

`phone-calendar.json` keeps the latest snapshot and
`phone-calendar-proposals.json` the queue (both gitignored). Pending proposals
expire after 7 days. Every write happens on the phone after the owner approves;
the PC can read and propose only. Event text from notifications is untrusted:
the inbox analysis fills an `event` field and the server validates it, but the
owner confirms it on the phone.

### Remote screen

Source: [`lib/screen-routes.mjs`](../lib/screen-routes.mjs).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/screen/config` | Bearer | Capabilities, including whether remote input is enabled |
| GET | `/api/v1/screen/monitors` | Bearer | Monitor list |
| GET | `/api/v1/screen/stream` | Bearer | Chunked frame stream |
| POST | `/api/v1/screen/ack` | Bearer | Acknowledge a received frame |
| POST | `/api/v1/screen/quality` | Bearer | Change stream quality |
| POST | `/api/v1/screen/input` | Bearer | Mouse/keyboard input; `403` when `IDUPI_REMOTE_INPUT=0` |
| GET / POST | `/api/v1/screen/clipboard` | Bearer | Read or set the PC clipboard |
| GET | `/api/v1/screen/audio` | Bearer | Audio capture |
| GET / POST | `/api/v1/screen/virtual-display` | Bearer | Query or change the virtual display |

### Agent chat and activity

These routes remain in the server. The current app has no chat screen; the
**Procesos** log panel still consumes the SSE stream, and the engine/model
selection endpoints back the shared terminal workflow.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/chat/stream` | Bearer | SSE activity/log stream |
| POST | `/api/v1/chat/message` | Bearer | Send a prompt to the active engine |
| GET | `/api/v1/chat/active-task` | Bearer | Current task state |
| POST | `/api/v1/chat/ui-response/{requestId}` | Bearer | Answer an engine UI request |
| POST | `/api/v1/engine/select` | Bearer | Select the active engine |

### Legacy endpoints

Still served for older clients and server-side features; the current app UI does
not use them.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/terminals` | Legacy log-stream terminal list |
| GET | `/api/v1/terminals/{id}/logs` | Legacy terminal log tail |
| POST | `/api/v1/terminals/spawn`, `/exec`, `/restart` | Legacy shell commands |
| DELETE | `/api/v1/terminals/{id}` | Legacy terminal close (tree kill) |
| GET | `/api/v1/sessions`, `/sessions/counts`, `/sessions/{id}/history` | Agent session history |
| POST | `/api/v1/sessions/resume`, `/sessions/new` | Agent session lifecycle |
| GET | `/api/v1/orchestrator/status`, `/orchestrator/providers/{id}/models` | Orchestrator status and provider models |
| POST | `/api/v1/orchestrator/profiles/apply`, `/save`, `/delete`, `/models/update`, `/action` | Orchestrator profile and model actions |

## Scripts

| Script | Purpose |
|--------|---------|
| [`scripts/start-server.ps1`](../scripts/start-server.ps1) | Start `node index.mjs` detached (hidden window, logs in `idupi-server/logs/`); no-op when the port already listens; `-Restart` tree-kills the current node listener first; `-Port` overrides `8788` |
| [`scripts/install-autostart.ps1`](../scripts/install-autostart.ps1) | Add or remove (`-Uninstall`) the Startup shortcut that runs `start-server.ps1` at user logon |
| [`scripts/install-sensors-helper.ps1`](../scripts/install-sensors-helper.ps1) | Re-launch elevated, verify and install PawnIO + LibreHardwareMonitor, copy the helper to `%ProgramData%\IDUPI`, register the `IDUPI Sensors` logon task; `-Uninstall` removes it |
| [`scripts/sensors-helper.ps1`](../scripts/sensors-helper.ps1) | Elevated helper that writes `%ProgramData%\IDUPI\sensors.json` every 2 s |
| [`scripts/sensors-deps.json`](../scripts/sensors-deps.json) | Pinned versions, URLs and SHA-256 hashes of the downloaded sensor dependencies |

## Command-line tools

| Tool | Usage |
|------|-------|
| [`bin/idupi-term.mjs`](../bin/idupi-term.mjs) (`idupi-term.cmd`) | `idupi-term` lists/attaches (creates if none), `ls`, `new [--cwd DIR]`, `attach <ref>`, `web`; `Ctrl+]` detaches without killing |
| [`bin/idupi-push.mjs`](../bin/idupi-push.mjs) (`idupi-push.cmd`) | `idupi-push <archivo.apk>` publishes an APK, `idupi-push ls` lists deliveries |
| [`bin/idupi-phone.mjs`](../bin/idupi-phone.mjs) (`idupi-phone.cmd`) | `idupi-phone status`, `screen`, `tap`, `long-press`, `type`, `scroll`, `back`/`home`/`recents`/`notifications`, `open`, `wait`, `calendar [agenda\|free\|propose\|proposals]`, `log`; see [`phone.md`](phone.md) |

Both CLIs read the token from `.idupi-token` or `IDUPI_TOKEN`, use `IDUPI_PORT`
(default `8788`) and print a hint pointing to `start-server.ps1` when the server
is unreachable.

## Environment variables

| Variable | Default | Effect |
|----------|---------|--------|
| `PORT` | `8788` | Server listening port |
| `IDUPI_TOKEN` | `.idupi-token` file | Bearer token override |
| `IDUPI_SHOW_TOKEN` | unset | `1` prints the full token on every start instead of the masked value |
| `IDUPI_REMOTE_INPUT` | enabled | `0` disables remote mouse/keyboard (`/screen/input` answers `403`) |
| `IDUPI_PORT` | `8788` | Port used by the CLIs |
| `IDUPI_CORS_ORIGIN` | unset | Single explicit origin allowed for a trusted local web client; never `*` |
| `IDUPI_NO_LISTEN` | unset | `1` loads the server without listening (tests and tooling) |

## Runtime data on the PC

| Path | Contents |
|------|----------|
| `.idupi-token` | Access token (repo root) |
| `idupi-server/projects.json` | Registered projects and active project |
| `idupi-server/deliveries/` | Delivery index and APK snapshots (newest 10) |
| `idupi-server/data/` | Phone inbox items/config, conversation digests, `phone-status.json`, `phone-calendar.json` and `phone-calendar-proposals.json` (gitignored) |
| `idupi-server/logs/` | Server stdout/stderr from `start-server.ps1` |
| `%ProgramData%\IDUPI\` | Sensor helper, LibreHardwareMonitor DLLs and `sensors.json` |

## Layout

| Path | Role |
|------|------|
| [`index.mjs`](../index.mjs) | HTTP entry point, route chain, auth choke point, legacy chat and orchestrator routes |
| [`lib/`](../lib) | Route modules, PTY manager, deliveries, metrics, processes, screen stream, phone agent and calendar |
| [`screen-helper/`](../screen-helper) | Zero-dependency Go helper: screen capture and ConPTY host |
| [`web/`](../web) | Static browser terminal page |
| [`test/`](../test) | `node --test` suites |
