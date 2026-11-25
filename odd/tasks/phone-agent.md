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
- [x] T2 App status: pure mappers + Android collectors (battery, signal,
  network, memory, storage, usage stats, foreground app), reporter every 60 s
  from a persistent service, usage-access permission UI.
- [x] T3 App control: AccessibilityService + config XML, command executor
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
- T2 done (54addc4): pure wire model and rules in `data/phone/` (`PhoneStatus`
  matching the status contract, `UsageAggregation`, `SignalMapping`/
  `NetworkMapping`/`BatteryMapping`, local-midnight window), thin collectors
  (battery sticky intent, `signalStrength` + operator, data network type only
  when READ_PHONE_STATE is already granted, `NetworkCapabilities` kind without
  SSID, `MemoryInfo`, `StatFs`, `UsageStatsManager` events + PackageManager
  labels behind the PACKAGE_USAGE_STATS app-op check), `PhoneStatusReporter`
  (60 s loop + `reportNow`, skips when disabled or no profile, never logs
  values) hosted process-wide from `InboxNotificationListener` with idempotent
  `start`/`stop` for T3, `postPhoneStatus` on the client trio, `phone_status_enabled`
  DataStore key (default on), and Ajustes > "ASISTENTE DEL TELÉFONO" (switch,
  usage-access status rechecked on resume with its system-settings button,
  "Enviar ahora") with `PhoneAgentSettingsViewModel`.
  Verification: `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`
  exit 0; 493 unit tests, 0 failures (50 new across
  UsageAggregation/PhoneStatusMappers/PhoneStatusSerialization/
  PhoneStatusReporter/PhoneStatusSettingDataStore/PhoneAgentSettingsViewModel).
  Unverified on a device: real collector values (no instrumentation here), and
  `signal.networkType` is expected null because READ_PHONE_STATE is not
  declared or requested.
  Next: T3 accessibility service and command executor.
- T3 done (ff54298, d8e7faf, eda18db): pure control core in
  `data/phone/control/` (`NodeSnapshot`/`ScreenDumpFormatter` with the exact
  docs/phone.md line shape, depth-first ordinals shared across window roots,
  escaping, `••••` masking, invisible-node skip; `NodeMatcher` with exact text,
  then case-insensitive contains on text/desc, view ids, nearest clickable
  ancestor and ambiguity refusal; `CommandParser` mirroring the server
  validation; `AppResolver`; 30-entry payload-free `PhoneCommandLog`;
  `PhoneControlPoller`/`PollBackoff`). `IdupiAccessibilityService` with
  `res/xml/idupi_accessibility_service.xml` and the manifest entry, started only
  while the service is connected, the new `phone_control_enabled` switch
  (default off) is on and a profile exists; it observes the switch, cancels the
  in-flight poll when it turns off, stops on `onInterrupt`/`onUnbind`/
  `onDestroy`, and hosts the idempotent `PhoneStatusReporting.start`. Executor:
  screen from `rootInActiveWindow` (active-window fallback), tap/long-press via
  `ACTION_CLICK`/`ACTION_LONG_CLICK` then gesture, type via `ACTION_SET_TEXT`
  with `campo de contraseña` refusal and `ACTION_IME_ENTER` on API 30+, scroll
  action or swipe, global actions (quick settings API 31+), open through the
  launcher intent, wait, status through `reportNow`; every path returns ok/error
  and never crashes the service. Client trio: `getNextPhoneCommand(waitSeconds)`
  (204 → null, timeout wait + 15 s) and `postPhoneCommandResult`. Ajustes adds
  the control switch, accessibility status rechecked on resume with its settings
  button and the Android 13 restricted-settings hint, a READ_PHONE_STATE row for
  4G/5G, and recent activity; the catalog gains the `idupi-phone` section.
  Expired commands are reported as failures instead of executed; the log records
  typed text as a length and never the dump.
  Verification: `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`
  exit 0; 563 unit tests, 0 failures (70 new across the control tests, poller,
  accessibility status, control-setting DataStore, ViewModel and catalog).
  Unverified on a device: real accessibility trees/windows, gesture dispatch,
  package visibility and IME submit; no instrumentation here. Live end-to-end
  against the PC and the APK delivery belong to T4; the server was not restarted.
  Next: T4 version bump, APK delivery and live e2e.
- T4 (partial): version 42 / 1.41-phone-agent; app 563 tests / 0 failures,
  release APK cert d8ba0fc9, delivered to the phone. Server restart pending the
  owner's OK (a live PTY was open), then live e2e with idupi-phone.
  2026-11-24: server started on feat/phone-agent after the PC reboot (no live
  PTYs); branch pushed and stacked PR #21 opened on #20. Pending: the phone
  has not reported status yet (`idupi-phone status`: no data); live e2e.
- Live e2e (2026-11-24, on 1.43): `idupi-phone status` returns battery,
  signal, network, memory, storage and top apps; `idupi-phone screen` returned
  the lock-screen (AOD) node dump through the accessibility service.
