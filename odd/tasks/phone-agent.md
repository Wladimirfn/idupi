# Phone agent (status + control from the PC)

## Objective

Make the owner's phone visible and controllable by the PC assistant. The
IDUPI app reports the phone's state to the server: battery, cellular signal,
network, memory, storage, screen time per app and the foreground app. Through
an Android AccessibilityService, the app also executes commands queued on the
PC: read the screen, tap, type, scroll, go back or home, and open apps. A PC
CLI (`idupi-phone`) exposes all of it to any agent running on the PC. ADB
control is a later phase.

## Problem / why

The owner wants one assistant that knows everything happening on the phone
and can act on it from the PC, without depending on the phone being plugged in
or on the same Wi-Fi pairing. The phone is the owner's own device. The app
itself is the persistent channel, through accessibility and usage access,
both granted explicitly by the owner.

## Scope

In:
- Server: phone status ingest + history, command queue with long-poll for the
  phone and result wait for the PC, `bin/idupi-phone` CLI, docs for agents.
- App: status collectors + periodic reporter; AccessibilityService command
  executor with a long-poll loop; Ajustes section with permissions, a master
  "control from the PC" switch and a recent command log; add `idupi-phone` to
  the commands catalog.

Out (later): ADB wireless pairing/control, screen video streaming, an MCP
server wrapper, acting on lock screen / unlocking.

## Constraints

- Control is OFF by default; it needs the owner's accessibility grant AND the
  in-app master switch. Commands expire after 60 s so a stale queue never
  executes later.
- Never expose password-field text in screen dumps; never type into a
  password field.
- Same trust level as the existing token (a token holder already has PTY
  execution on the PC). All routes after requireAuth.
- Zero-dependency Node server, execFile without a shell, deps injected.
- Code/comments/commits in English; UI copy in Spanish.
- Each APK delivered with idupi-push and a higher versionCode.

## Tasks

- [x] T1 Server + CLI: `lib/phone-store.mjs`, `lib/phone-routes.mjs`
  (status POST/GET, commands POST, phone long-poll `next`, result POST, PC
  result wait), `bin/idupi-phone.mjs` + `.cmd`, `docs/phone.md`, tests.
- [ ] T2 App status: pure mappers + Android collectors (battery, signal,
  network, memory, storage, usage stats, foreground app), reporter every 60 s
  from a persistent service, usage-access permission UI.
- [ ] T3 App control: AccessibilityService + config XML, command executor
  (screen dump, tap by text/id/coords, type, scroll, global actions, open app,
  wait), long-poll loop gated by the master switch, command log, Ajustes UI,
  `idupi-phone` entries in the commands catalog.
- [ ] T4 Deliver: version bump, APK to phone, server restart, live e2e with
  `idupi-phone status|screen|open|tap`.

## Acceptance

- `idupi-phone status` on the PC shows the phone's current battery, signal,
  network and today's top apps.
- With control enabled, `idupi-phone screen` returns the visible UI as text,
  and `idupi-phone open whatsapp`, `tap "<text>"`, `type "<text>"`, `back`
  and `home` act on the phone and report success or failure.

## Checks

- Server: `node --test idupi-server/test/<file>.test.mjs` per file (CI style).
- App: `./gradlew :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config); ordinary functional checks.

## Delivery

Branch `feat/phone-agent`, stacked on `feat/phone-inbox` (#20). Strategy:
stacked PRs (same as #18 → #19 → #20).

## Progress

- Baseline: 1.40-alertas-inbox (versionCode 41) installed on the phone;
  listener access granted by the owner.
- T1 done (commits 561d807, a2c8735, 46dc2ed): status ingest + sampled history
  in `data/phone-status.json`, in-memory command queue with 60 s expiry, phone
  long-poll with supersede/disconnect cleanup, PC wait, validated per-type
  args, `idupi-phone` CLI and `docs/phone.md`.
  Verification: phone-store 15/15, phone-routes 14/14, idupi-phone-cli 11/11;
  whole server suite 62/62 files green (180 s cap per file);
  `node --check idupi-server/index.mjs` OK. CLI smoke-tested end to end
  against an isolated server on port 18788 (status/screen/tap/open/log and a
  failed tap exiting 1); the live server on 8788 was not restarted.
  Next: T2 app status collectors and reporter.
