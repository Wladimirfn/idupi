# Phone agent — status and control from the PC

The phone reports its state to the IDUPI server and executes commands the PC
queues. This document is the contract for agents running on the PC: what the
phone exposes, how to drive it with `idupi-phone`, and the exact shape of the
screen dump.

Everything here requires the bearer token, exactly like the rest of
`/api/v1/*`. A token holder already has PTY execution on the PC, so phone
control is the same trust level.

## Safety model

- **Control is off by default.** The app needs the owner's accessibility grant
  *and* the in-app master switch. Status reporting is separate from control.
- **Commands expire.** A pending command older than 60 s becomes `expired` and
  is never delivered, so a stale queue can never fire a tap later.
- **No password text.** Screen dumps replace the text of password fields with
  `••••`, and the executor never types into a password field.
- **Actions are unverified by the server.** The server is a queue; the phone
  decides whether a node matched and reports `ok` or `error`.

## Routes

Source: [`lib/phone-routes.mjs`](../lib/phone-routes.mjs), store in
[`lib/phone-store.mjs`](../lib/phone-store.mjs).

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/phone/status` | Phone → server: store a status snapshot (JSON object, max 64 KiB) as the latest sample and add it to history (max one sample per 5 min, last 24 h, 300 entries) |
| GET | `/api/v1/phone/status` (alias `GET /api/v1/phone`) | PC → server: `{ latest, history, online, lastSeenAt }` |
| GET | `/api/v1/phone/commands/next?wait=25` | Phone long-poll: `200 { command }` with the oldest pending command marked `delivered`, or `204` after `wait` seconds (1..30) |
| POST | `/api/v1/phone/commands/{id}/result` | Phone → server: `{ ok: boolean, result?: any, error?: string }`; marks `done` or `failed` |
| POST | `/api/v1/phone/commands` | PC → server: `{ type, args }`; answers `201 { command }` |
| GET | `/api/v1/phone/commands/{id}?wait=30` | PC long-poll: waits until `done`/`failed`/`expired` or `wait` seconds (0..30, `0` answers now) |
| GET | `/api/v1/phone/commands?limit=20` | Recent commands, newest first (`limit` 1..100) |

`online` means the phone sent a status or polled for a command in the last
2 minutes; `lastSeenAt` is that moment. Only one phone poller is expected: a
new `next` poll supersedes the parked one (the old request answers `204`, its
timer and subscription are cleared).

Status is persisted in `idupi-server/data/phone-status.json` (gitignored). The
command queue is memory-only; a server restart never replays a stale command.

### Command types

| `type` | `args` | Meaning |
|--------|--------|---------|
| `screen` | `{}` | Return the visible UI as text |
| `tap` | exactly one of `{text}` / `{id}` / `{x, y}` | Tap a node or coordinates |
| `long_press` | same as `tap` | Long-press a node or coordinates |
| `type` | `{text, submit?}` | Type text; `submit: true` presses the action key |
| `scroll` | `{direction}` where direction is `up`, `down`, `left`, `right` | Scroll |
| `global` | `{action}` where action is `back`, `home`, `recents`, `notifications`, `quick_settings` | Global system action |
| `open` | `{app}` | Launch an app by package name or visible label |
| `wait` | `{ms}` (0..10000) | Pause inside the phone executor |
| `status` | `{}` | Ask the phone to report `POST /phone/status` now |

Unknown types, unknown argument keys and wrong target combinations answer
`400` and create nothing.

A command looks like:

```json
{
  "id": "phc-1a2b3c4d5e6f",
  "type": "tap",
  "args": { "text": "Enviar" },
  "state": "pending",
  "createdAt": "2026-11-24T12:00:00.000Z",
  "expiresAt": "2026-11-24T12:01:00.000Z",
  "deliveredAt": null,
  "finishedAt": null,
  "result": null,
  "error": null
}
```

`state` is `pending`, `delivered`, `done`, `failed` or `expired`. A failed
result carries a short `error`; a successful one may carry `result`.

### Result shapes the CLI understands

| Command | Successful `result` | CLI output |
|---------|--------------------|------------|
| `screen` | `{ "text": "<dump>" }` | the dump text (or the raw JSON with `--json`) |
| every other type | `{}` or `{ "message": "..." }` | `Listo.` or `Listo: <message>` |

A failed phone-side execution reports `{ ok: false, error: "<reason>" }`; the
CLI prints `El teléfono falló: <reason>` and exits non-zero.

## Status snapshot contract

The phone posts an object (max 64 KiB). The server stores it as-is and adds
`receivedAt` (ISO, server clock). A field named `receivedAt` sent by the phone
is overwritten. The CLI reads these fields, all optional:

```json
{
  "battery": { "percent": 87, "charging": true, "temperatureC": 31.2 },
  "signal": { "level": 4, "dbm": -78, "operator": "Claro", "networkType": "LTE" },
  "network": { "kind": "wifi", "ssid": "Casa", "rssiDbm": -55 },
  "memory": { "usedBytes": 4294967296, "totalBytes": 8589934592 },
  "storage": { "usedBytes": 51539607552, "totalBytes": 137438953472 },
  "foregroundApp": { "label": "WhatsApp", "packageName": "com.whatsapp" },
  "topAppsToday": [
    { "label": "WhatsApp", "packageName": "com.whatsapp", "minutes": 42 }
  ]
}
```

`network.kind` is `wifi`, `mobile`, `none` or another short word. Missing
fields are simply not printed by `idupi-phone status`.

## Screen dump format

`screen` returns `{ "text": "<dump>" }`. The dump is one line per visible
node, depth-first, in this exact shape:

```
[n] <class short> "text" (desc) #id [clickable] [editable] @x,y,w,h
```

- `[n]` — node ordinal within this dump, 1-based. Only meaningful for reading
  one dump; it is not a command target.
- `<class short>` — simple class name (`TextView`, `Button`, `EditText`,
  `ImageView`, `RecyclerView`, ...).
- `"text"` — the node's text, verbatim, with `"` escaped as `\"` and line
  breaks as `\n`. Omitted when empty.
- `(desc)` — content description, same escaping. Omitted when empty.
- `#id` — the view resource id (for example `com.whatsapp:id/send`). Omitted
  when the node has none.
- `[clickable]` and `[editable]` — present only when the node reports them.
- `@x,y,w,h` — bounds in screen pixels.

Password fields show their text as `••••`. Invisible nodes are skipped. When
nothing is visible the dump is an empty string.

Example:

```
[1] FrameLayout @0,0,1080,2400
[2] TextView "Chats" #com.whatsapp:id/title @24,120,240,180
[3] Button "Enviar" (Enviar mensaje) #com.whatsapp:id/send [clickable] @900,2100,1060,2200
[4] EditText "••••" #com.whatsapp:id/password [editable] @40,300,1040,420
```

## Calendar

Source: [`lib/phone-calendar-routes.mjs`](../lib/phone-calendar-routes.mjs), store
and math in [`lib/phone-calendar-store.mjs`](../lib/phone-calendar-store.mjs).

The phone owns the calendars (Android `CalendarContract`: Google, Samsung and
the Outlook app when its calendar sync is on). It reports a read-only snapshot
to the PC; the PC reads the agenda, asks for free slots and proposes events.
**The PC never writes to a calendar.** A write happens only on the phone after
the owner taps through a confirmation sheet (from an alert) or approves a PC
proposal.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/phone/calendar` | Phone → server: replace the snapshot (events for the next 14 days) |
| GET | `/api/v1/phone/calendar?from=&to=` | PC → server: `{ capturedAt, rangeStart, rangeEnd, tzOffsetMinutes, storedAt, calendars, events, conflicts }` |
| GET | `/api/v1/phone/calendar/free?from=&to=&min=30` | PC → server: `{ from, to, minMinutes, tzOffsetMinutes, hasSnapshot, slots }` |
| POST | `/api/v1/phone/calendar/proposals` | PC → server: queue a proposal for the owner to approve on the phone |
| GET | `/api/v1/phone/calendar/proposals?status=pending&limit=50` | Proposals, newest first (`status`: `pending`, `approved`, `rejected`, `expired` or `all`) |
| POST | `/api/v1/phone/calendar/proposals/{id}/decision` | Phone → server: `{ decision: "approved"\|"rejected", eventId? }` |

The snapshot is persisted in `idupi-server/data/phone-calendar.json`; the
proposal queue in `phone-calendar-proposals.json` (both gitignored). A pending
proposal expires after 7 days.

### Snapshot contract

`POST /api/v1/phone/calendar` accepts exactly this object (max 512 KiB):

```json
{
  "capturedAt": "2026-11-24T12:00:00.000Z",
  "rangeStart": "2026-11-24T00:00:00.000Z",
  "rangeEnd": "2026-12-08T00:00:00.000Z",
  "tzOffsetMinutes": -180,
  "calendars": [
    { "id": "cal-1", "name": "Personal", "accountType": "com.google",
      "accountName": "owner@gmail.com", "color": "#4285F4", "writable": true }
  ],
  "events": [
    { "id": "ev-1", "calendarId": "cal-1", "title": "Reunión de equipo",
      "start": "2026-11-24T13:00:00.000Z", "end": "2026-11-24T14:00:00.000Z",
      "allDay": false, "location": "Oficina", "description": "..." }
  ]
}
```

- `capturedAt`, `rangeStart`, `rangeEnd` and every event `start`/`end` must be
  ISO-8601 strings; a bad one rejects the whole upload with `400
  INVALID_SNAPSHOT`.
- Caps: at most 1000 events and 50 calendars; `title` and `location` clip to
  200 chars, `description` to 500.
- `tzOffsetMinutes` is the phone's offset from UTC (UTC−3 → `-180`). It defines
  the local day boundaries used by free slots; times themselves stay UTC.
- `rangeEnd` must not precede `rangeStart`.

`GET /api/v1/phone/calendar` filters events that overlap `from`/`to` (either
bound is optional), sorts them by start, and returns `conflicts` computed
deterministically: every pair of non-all-day events whose ranges overlap
(`end` equal to the next `start` is not an overlap). All-day events never
conflict. Before the first snapshot it answers `200` with `capturedAt: null`
and empty lists.

### Free slots

`GET /api/v1/phone/calendar/free` accepts `from`, `to` (ISO; default: now for
7 days), `min` (minutes, 1..1440, default 30) and returns gaps between
`dayStart` 08:00 and `dayEnd` 20:00 local time. Overlapping events merge into
one busy block; all-day events do not block free time. Without a snapshot it
answers `200` with `hasSnapshot: false` and no slots. `from` and `to` are
required together when either is present.

### Proposals

`POST /api/v1/phone/calendar/proposals`:

```json
{ "title": "Almuerzo", "start": "2026-11-25T13:30:00.000Z",
  "end": "2026-11-25T14:30:00.000Z", "allDay": false,
  "location": "Centro", "notes": "Con Ana", "calendarId": "cal-1",
  "source": "agent" }
```

`title`, `start` and `source` are required. `end` defaults to one hour after
`start`; `allDay: true` spans one day. The server stores it as `pending` with
`conflicts` (overlapping non-all-day events at creation time) and answers
`201 { proposal, tzOffsetMinutes }`.

The phone later reports its decision; only the owner can approve. `approved`
may carry the `eventId` the phone wrote. A second decision answers `409
ALREADY_DECIDED`; an expired proposal answers `409 EXPIRED`. Proposal text is
untrusted display data until the owner approves it.

## CLI

[`bin/idupi-phone.mjs`](../bin/idupi-phone.mjs) (`idupi-phone.cmd` on Windows).
It reads the token from `IDUPI_TOKEN` or `.idupi-token`, uses `IDUPI_PORT`
(default `8788`) and talks to `127.0.0.1`.

Every action enqueues a command, then waits up to ~65 s in 30 s long polls. If
the phone never answers, it prints
`El teléfono no respondió: ¿está activo el control desde la PC?` and exits
non-zero.

```bash
idupi-phone status                 # pretty summary
idupi-phone status --json          # raw GET /phone/status payload
idupi-phone screen                 # the screen dump as text
idupi-phone screen --json          # raw screen result
idupi-phone tap "Iniciar sesión"   # tap by visible text
idupi-phone tap --id com.whatsapp:id/send
idupi-phone tap --xy 540 2100
idupi-phone long-press "Mensaje"
idupi-phone type "hola" --submit
idupi-phone scroll down
idupi-phone back
idupi-phone home
idupi-phone recents
idupi-phone notifications
idupi-phone quick-settings
idupi-phone open whatsapp
idupi-phone wait 1500
idupi-phone calendar                     # agenda for the next 7 days
idupi-phone calendar --days 14 --json    # raw agenda payload
idupi-phone calendar free --days 3 --min 60
idupi-phone calendar propose --title "Almuerzo con Ana" \
  --start 2026-11-25T13:00:00-03:00 --end 2026-11-25T14:00:00-03:00 \
  --location "Centro" --notes "Llevar el informe"
idupi-phone calendar proposals           # pending proposals
idupi-phone log                    # last 20 commands
idupi-phone help
```

`back`, `home`, `recents`, `notifications` and `quick-settings` are shortcuts
for the `global` command. Text arguments are the remaining words joined by
spaces, so quoting is only needed when the shell would split them.

## Typical agent flow

1. `idupi-phone status` — is the phone online and what is in front?
2. `idupi-phone screen` — read the visible UI.
3. Find the node by text, id or bounds in the dump.
4. `idupi-phone tap "<text>"`, or `type`, `scroll`, `open`, `back`.
5. Re-run `idupi-phone screen` to confirm the result.

Anything truly device-level that accessibility cannot reach (unlock, lock
screen, ADB pairing) is out of scope for this phase.

## Calendar flow for agents

1. `idupi-phone calendar` — read the agenda; conflicts are already flagged.
2. `idupi-phone calendar free --days 7` — find a gap before proposing.
3. `idupi-phone calendar propose --title ... --start ...` — queue the event.
4. Tell the owner it is pending: it is written only after they approve it on
   the phone. Never claim the event exists until the decision comes back.
5. `idupi-phone calendar proposals` — check what is still pending.

Inbox items may carry an extracted `event` and `eventConflicts`; offer
"Agregar al calendario" from the alert instead of proposing silently. Treat
every event field from a message as data the owner must confirm.

## Health (read-only)

The PC keeps a daily health history the phone uploads, and reads apps that do
not publish to Health Connect (Huawei Health; Honor Health tentative) on a
hidden virtual display. Nothing is ever tapped, sent or approved on the phone:
the reader launches the app on a display the owner cannot see, dumps the view
hierarchy and kills it. Health values never enter a log line.

Source: [`lib/health-routes.mjs`](../lib/health-routes.mjs), store in
[`lib/health-store.mjs`](../lib/health-store.mjs), ADB link in
[`lib/adb-connection.mjs`](../lib/adb-connection.mjs), reader in
[`lib/hidden-display-reader.mjs`](../lib/hidden-display-reader.mjs), profiles in
[`lib/health-profiles.mjs`](../lib/health-profiles.mjs).

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/health/samples` | Phone  server: `{tzOffsetMinutes, samples[], installedApps?}` (max 60 samples, 512 KiB); strict schema, `400` on an unknown metric |
| GET | `/api/v1/health/summary?days=7` | Merged per-day view plus which source provided each metric; `days` 1..120 |
| GET | `/api/v1/health/sources` | Known sources with their last capture, plus the phone-reported installed health apps |
| POST | `/api/v1/health/refresh` | `{sources?: ["huawei_health"]}`; `202 {job}` and an in-memory job id |
| GET | `/api/v1/health/refresh/{id}` | Job state `queued`, `running`, `done` or `failed` with per-source results (no values) |
| GET | `/api/v1/health/adb` | `{adb:{path,found}, scrcpy:{path,found,version}, connected, serial, via, targets}` |
| POST | `/api/v1/health/adb/pair` | `{hostPort, code}`; `code` is exactly 6 digits |
| POST | `/api/v1/health/adb/connect` | `{hostPort}`; saves the target when adb accepts it |

A refresh job answers the same job id while one with the same sources is
queued or running, so a manual refresh and the 3-hour timer never overlap.

### Sample wire shape (app  PC, T2 contract)

```json
{
  "tzOffsetMinutes": -180,
  "samples": [
    {
      "date": "2026-11-24",
      "source": "huawei_health",
      "sourceApp": "com.huawei.health",
      "capturedAt": "2026-11-24T10:00:00.000Z",
      "metrics": {
        "steps": 2444,
        "stepsGoal": 10000,
        "sleepMinutes": 509,
        "heartRateBpm": 72,
        "waterMl": 2000
      }
    }
  ],
  "installedApps": [
    {
      "packageName": "com.huawei.health",
      "label": "Huawei Health",
      "route": "hidden_display"
    }
  ]
}
```

- `date` is the owner-local `YYYY-MM-DD`; `capturedAt` is ISO-8601.
- `source` matches `^[a-z][a-z0-9_]{0,39}$`. Known ids: `huawei_health`,
  `health_connect`, `wellbeing`, `samsung_health`, `mi_fitness`.
- The latest capture per source/day wins. Its non-null metrics overwrite the
  older ones; keys the new capture omitted keep the older value.
- History keeps 120 days. A future date, a date outside that window, an unknown
  metric key or an out-of-range value rejects the whole upload with `400`.
- `installedApps[].route` is one of `health_connect`, `hidden_display`,
  `not_supported` (any `^[a-z][a-z0-9_]{0,39}$` is accepted and stored).

### Metrics

| Key | Unit | Sanity range |
|-----|------|--------------|
| `steps`, `stepsGoal` | count | 0..200000 |
| `distanceM` | meters | 0..1000000 |
| `caloriesKcal`, `activeCaloriesKcal` | kcal | 0..50000 |
| `heartRateBpm`, `heartRateMin`, `heartRateMax` | bpm | 20..250 |
| `restingHeartRateBpm` | bpm | 20..200 |
| `sleepMinutes`, `sleepGoalMinutes`, `exerciseMinutes`, `exerciseGoalMinutes` | minutes | 0..1440 |
| `sleepStages` | `{deep, light, rem, awake}` minutes | 0..1440 each |
| `sleepStart`, `sleepEnd` | ISO-8601 | - |
| `weightKg`, `weightGoalKg` | kg | 20..400 |
| `spo2Pct` | % | 50..100 |
| `stress` | 0..100 | 0..100 |
| `stressLabel`, `wakeTime`, `wakeGoal`, `bedtimeGoal` | text | max 40 / 10 chars |
| `waterMl`, `waterGoalMl` | ml | 0..20000 |
| `screenTimeMinutes`, `nightScreenMinutes` | minutes | 0..1440 |
| `unlocks` | count | 0..5000 |
| `topApps` | `[{label, minutes}]` | max 20, label 80 chars, minutes 0..1440 |
| `workouts` | `[{type, minutes, start?, kcal?}]` | max 50, minutes 0..1440 |

A merged day reports `sources` next to `metrics`, mapping each metric key to the
source that supplied the winning value.

### Hidden-display refresh

Requirements on the PC:

- `adb` from the Android SDK platform-tools. Resolution order: `IDUPI_ADB`, then
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, then `adb` on `PATH`.
- `scrcpy` 5.x (it needs `--new-display`). Resolution order: `IDUPI_SCRCPY`,
  then `PATH`, then the newest
  `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Genymobile.scrcpy_*\scrcpy-win64-v*\scrcpy.exe`.
- Wireless debugging paired once. The connect port changes per session; the
  manager tries `adb devices`, mDNS `_adb-tls-connect`, the saved serials and
  finally a bounded TCP probe of the saved hosts (ports 30000-49999).

Flow per read (one at a time, 60 s cap): create the virtual display with
`scrcpy --new-display=1080x4200/440 --start-app=<pkg> --no-window --no-audio
--record=<temp.mkv> --max-fps=5 --video-bit-rate=500K`, wait for
`New display: ... (id=N)`, wake only that display with
`adb shell input -d N keyevent KEYCODE_WAKEUP`, `uiautomator dump --display N`,
read it with `exec-out cat`, retry wake+dump once if the target package is not
in the dump, then kill scrcpy. The temporary `.mkv` and the remote `.xml` are
deleted in a `finally` block; a failed read reports `app_not_visible`,
`no_display`, `timeout` or `adb_unreachable`.

Only `com.huawei.health` and `com.hihonor.health` may be launched. Honor Health
reuses the Huawei resource ids and the same parser, but is marked tentative
until the owner's device confirms it.

```bash
idupi-phone health adb                     # adb/scrcpy found? phone connected?
idupi-phone health adb pair 192.168.1.50:37123 123456
idupi-phone health adb connect 192.168.1.50:37123
idupi-phone health sources                 # which apps report, and their route
idupi-phone health refresh --wait          # read Huawei Health now
idupi-phone health --days 7                # merged history
idupi-phone health summary --days 30 --json
```

The scheduler refreshes Huawei Health every 3 h only when a target is saved,
skips quietly when ADB is unreachable, and never starts a second refresh while
one is running. `idupi-phone health` is the read path for PC agents: read the
summary, cite the day and metric, and never present a health value as a
diagnosis.
