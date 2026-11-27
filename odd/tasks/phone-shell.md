# Phone shell: IDUPI gets adb-level access on its own (no Shizuku)

Locator: `odd/tasks/phone-shell.md` · Engram mirror: `odd/phone-shell/tasks`
Branch: `feat/phone-shell` (stacked on `feat/phone-dashboard`)

## Objective

The IDUPI app obtains a `shell` (uid 2000) command channel on the phone by
itself, the way Shizuku does, without installing Shizuku or any other app, so
the phone agent can optimize the device with real commands (clear every app
cache in one step, stop background apps, disable telemetry packages) instead
of navigating Settings by taps.

## Problem (verified 2026-11-27)

- "Optimiza mi teléfono" failed twice (2026-11-26 23:13, 2026-11-27 05:59):
  the agent got lost navigating Settings and hit the step budget without
  freeing anything.
- The same actions are one command each as uid 2000, verified over PC adb on
  the owner's S24 Ultra (Android 16): `id` -> `uid=2000(shell)`,
  `cmd appops get`, `pm list packages -d` all answer.
- The Android app has no privileged channel today (explore-1: no adb, pairing,
  Binder service or app_process code in `app/`).

## Owner decisions (2026-11-27)

- Build it into IDUPI itself; fewer third-party apps is better. Owner trusts
  the implementation choices ("confío en ti").
- Existing approval rules apply (memory `feedback-phone-actions-need-approval`):
  owner bubble tasks auto-approve messaging, deletes ask by voice, money by
  notification.

## Research (consult-1, 2026-11-27; sources in the run log)

- Shizuku: in-app wireless-debugging pairing (mDNS `_adb-tls-pairing._tcp`,
  TLS 1.3 + exported keying material + SPAKE2, pairing code typed into a
  `RemoteInput` notification while Settings stays open), then adb `shell:` to
  start an `app_process` server as uid 2000 that hands a Binder back.
- Library: `libadb-android` 3.1.1 (MuntashirAkon), `GPL-3.0-or-later OR
  Apache-2.0` -> we elect Apache-2.0 (repo is MIT). Pairing + `shell:` streams;
  deps Bouncy Castle, spake2-android (LGPL native, note in licenses),
  optional Conscrypt. Not security-audited upstream.
- Android 16 disables wireless debugging on Wi-Fi disconnect or network change;
  neither design survives a reboot; adb key authorization expires after 7 days
  without use.

## Design

- **Phase 1 (option A, on demand):** the app pairs once with its own adbd,
  stores its adb key privately (app-private file, never exported), discovers
  `_adb-tls-connect._tcp` and opens `shell:` per command with a timeout.
- **Self-recovery after reboot:** the first time the channel works, it runs
  `pm grant com.idupi.app android.permission.WRITE_SECURE_SETTINGS`
  (persists across reboots). With it the app can switch wireless debugging back
  on by itself (`adb_wifi_enabled`) on the trusted home Wi-Fi and reconnect with
  the already-paired key, so after a reboot the owner does nothing. Shizuku
  cannot do this.
- **Shell bridge in an own helper package (decided after T1):** the bridge is
  `ShellBridgeInstrumentation` inside `com.idupi.shell` (module
  `:shellhelper`, same release signature, no launcher activity). One adb call
  starts `am instrument -w com.idupi.shell/.ShellBridgeInstrumentation`; the
  instrumentation runs in the helper's own process and adopts the shell
  permission identity with `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`
  (FLAG_DONT_USE_ACCESSIBILITY left every accessibility service unbound). The
  force-stop that `am instrument` performs hits only the helper, never
  com.idupi.app. The identity survives `am` dying and adbd restarting, so it
  keeps working away from Wi-Fi until the helper dies or the phone reboots.
- **Helper delivery:** the helper APK is embedded in the IDUPI APK (assets);
  during bootstrap IDUPI installs or updates it over its own adb session
  (`pm install`), so the owner installs nothing by hand.
- **App <-> helper channel:** the helper exposes a bound service protected by
  a signature-level permission; only com.idupi.app (same key) can call it.
  The typed allowlist is enforced inside the helper (execution boundary).
- **Strict command surface:** no free-form shell. A typed `shell` phone command
  with fixed actions: `trim-caches`, `force-stop <pkg>`,
  `background <pkg> allow|ignore`, `disable <pkg>`, `enable <pkg>`,
  `info storage|memory|battery|packages`. Package names validated by regex. The
  allowlist is enforced on the phone at the execution boundary, not only on the
  server.
- **Risk:** `info` free; `trim-caches`, `force-stop`, `background` treated as
  optimization (auto in an owner turn); `disable` destructive (asked by voice
  in an owner turn); `enable` free (undo).

## Tasks

- [x] T1 Device spike over PC adb on the S24 (see evidence). Result: plain
      uid-2000 processes (Shizuku's model) die on this phone when the owner
      leaves Wi-Fi; the self-instrumentation shell bridge (plan C) survives.
- [x] T1b Helper spike: `:shellhelper` module (`com.idupi.shell`) with the
      bridge; verified on device (evidence 8). In-app bridge removed from
      com.idupi.app.
- [x] T2 App adb core: add libadb-android (Apache-2.0, JitPack
      `com.github.MuntashirAkon:libadb-android:3.1.1`), key store, mDNS
      discovery, pairing client, connect, `shell:` exec with timeout and exit
      status; pure parts unit-tested (command builder, package validation,
      output parsing).
- [ ] T3 App pairing UX: Ajustes > "Acceso avanzado del teléfono" row with
      status (no vinculado / vinculado / conectado / no disponible), "Vincular"
      opens Wireless debugging and posts a notification with a `RemoteInput`
      for the 6-digit code; after pairing, grants WRITE_SECURE_SETTINGS.
- [ ] T3b Bootstrap: embed the helper APK in IDUPI's assets at build time;
      over the adb session install/update it (`pm install -r`) and start the
      bridge (`am instrument`); "Iniciar puente" action and status in the
      same Ajustes row.
- [ ] T4 `shell` phone command end to end: helper bound service
      (signature permission) with the typed allowlist, server `COMMAND_TYPES`
      + validation (`phone-store.mjs`), risk (`phone-risk.mjs`), CLI
      (`idupi-phone.mjs`), app parser/model/executor calling the helper,
      approval gate; tests on both sides.
- [ ] T5 Agent: phone-agent prompt uses `shell` for optimization (trim caches,
      stop heavy background apps, report freed space) and falls back to
      `settings` when the channel is unavailable.
- [ ] T6 Self-recovery: on boot / Wi-Fi connect to the trusted network, the app
      re-enables wireless debugging and reconnects.
- [ ] T7 Keep the bridge alive and recover it: detect loss (helper death,
      reboot) and re-bootstrap on Wi-Fi through T2/T3b.
- [ ] T8 Release: version bump, assembleRelease (app + helper), push to
      phone, live check, docs (`idupi-server/docs/phone.md`, README), PR.

## Acceptance criteria

- "Optimiza mi teléfono" clears all app caches in one command and reports the
  space before/after, without navigating Settings.
- After a reboot at home, the channel comes back without the owner pairing or
  toggling anything (if T1 confirms One UI allows it).
- No command outside the allowlist runs, even if the server sends it.

## Checks

- App: `./gradlew :app:testDebugUnitTest :app:assembleDebug` (JUnit4).
- Server: TDD off (`openspec/config.yaml`); `node --test idupi-server/test/<file>`.
- Device: live checks on the S24 recorded with evidence.

## Delivery

- Forecast well above ~400 authored lines (library glue + UX + command path):
  strategy `ask-on-risk`; slice into chained PRs (adb core + pairing / command
  path + agent / self-recovery) once the running count passes the budget.

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T2 | (this commit) | worker work-1 (opencode, deepseek-v4.1-flash). `:app:testDebugUnitTest --tests com.idupi.app.data.phone.shell.*` 36 tests ok; `:app:assembleDebug` ok. Deps: libadb-android 3.1.1 (JitPack, filtered), conscrypt-android 2.5.3, bcpkix/bcprov 1.81. Not device-verified yet (pairing, mDNS, loopback connect, exec): checked live in T3. Note for T3b: `am instrument -w` never returns, so launch it detached (`setsid sh -c '...' &`) instead of through a blocking `exec`. |
| T1b | bfe580d | Helper 0.1-spike `assembleRelease` ok, installed by adb; device evidence 8. App `compileReleaseKotlin` ok after removing the in-app bridge. |
| T1 | 6dbb709, a846217, 91c161c | Device evidence 2026-11-27 below. `assembleRelease` ok; 1.64-puente-shell (versionCode 65) installed by adb. |

T1 evidence (S24 Ultra, One UI / Android 16):

1. A setsid'd uid-2000 heartbeat survives a PC adb disconnect (06:47-06:48).
2. Turning wireless debugging off restarts adbd: every uid-2000 process dies
   (heartbeat stopped 06:49:00, OFF at 06:49:03) and legacy `tcpip 5555` is
   reset (connection refused).
3. Shell processes live in adbd's cgroup (`0::/system/uid_0/pid_<adbd>`); no
   `cgroup.procs` is writable by shell, so Shizuku's cgroup escape is
   impossible here.
4. Wi-Fi off kills them too (heartbeat last entry 07:05:51 with `wifi=0`).
5. Plan C: `ShellBridgeInstrumentation` adopted the shell identity
   (`adopted uid=10480`, cgroup `0::/apps/uid_10480/pid_30631`) and wrote the
   WRITE_SECURE_SETTINGS-protected `idupi_shell_probe` every 5 s. Killing `am`
   (07:11:50) did not stop it; adbd restarted at 07:14:10 after the owner's
   Wi-Fi off/on and the probe kept succeeding: 36 probes 07:11:35-07:14:30,
   no gap over 7 s, all ok, same process since 07:11:34.
6. Long field test (owner, 2026-11-27): Wi-Fi off from ~07:20 (last Wi-Fi
   scan entry 07:20:04) to 08:46:37 (`setWifiEnabled enable=true`), screen
   off, light Doze from 07:22 and deep Doze 08:22:31-08:46:36. Same bridge
   process (pid 30631, started 07:11:33) still alive at 08:47:48; 631 logged
   probes 07:18:50-08:47:46, all ok, no failure. Intervals stretched to
   8-33 s while dozing (CPU sleep pauses `Thread.sleep`), never a stop. adbd
   restarted again at 08:47:18 and the probe kept succeeding.
7. Side effect found 08:50: starting the bridge broke IDUPI's accessibility
   service (the bubble) from 07:11. `am instrument` force-stops the whole
   target package (One UI 16 `am` has no `--no-restart`), and while the
   bridge's UiAutomation was registered the system bound no accessibility
   service at all (`Bound services:{}`, `accessibility_enabled` kept dropping
   to 0). The suppression outlived the force-stopped bridge; settings
   re-toggles and a reinstall did not help. A fresh UiAutomation
   connect/disconnect (`adb shell uiautomator dump`) cleared it and IDUPI
   bound again at 09:02.

Design consequence: the bridge cannot run inside `com.idupi.app`. It moves to
a small own helper package (instrumentation targets the helper, so the
force-stop hits only the helper), installed by IDUPI over adb during
bootstrap and reached through a signature-protected Binder, and its
UiAutomation must not suppress accessibility (verify `Bound services` while
it runs).

8. Helper spike (09:05): `com.idupi.shell` 0.1-spike installed by adb;
   `am instrument -e flags 1` (FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
   adopted the shell identity, IDUPI kept its process (pid 31355) and its
   accessibility service stayed bound. Killing `am` (09:05:38) did not stop
   the probe; `am force-stop com.idupi.shell` left IDUPI's accessibility
   bound (no stuck suppression); restarting the bridge worked.

## Next step

T2: in-app adb client so IDUPI can pair, install the helper and start the
bridge by itself (no PC), then the typed executor in the helper (T4).
