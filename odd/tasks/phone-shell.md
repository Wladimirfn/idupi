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
- **Shell bridge (decided after T1, replaces the option B helper):** one adb
  call starts `am instrument -w com.idupi.app/.data.phone.shell.ShellBridgeInstrumentation`;
  the instrumentation runs inside the IDUPI process (app cgroup, not adbd's)
  and adopts the shell permission identity via
  `UiAutomation.adoptShellPermissionIdentity()` (FLAG_DONT_USE_ACCESSIBILITY so
  the owner's accessibility services are untouched). The identity survives `am`
  dying and adbd restarting, so it keeps working away from Wi-Fi until the
  process dies or the phone reboots. Bootstrap needs adb once (in-app wireless
  pairing at home, or the PC).
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
- [ ] T2 App adb core: add libadb-android (Apache-2.0), key store, mDNS
      discovery, pairing client, connect, `shell:` exec with timeout and exit
      status; pure parts unit-tested (command builder, package validation,
      output parsing).
- [ ] T3 App pairing UX: Ajustes > "Acceso avanzado del teléfono" row with
      status (no vinculado / vinculado / conectado / no disponible), "Vincular"
      opens Wireless debugging and posts a notification with a `RemoteInput`
      for the 6-digit code; after pairing, grants WRITE_SECURE_SETTINGS.
- [ ] T4 `shell` phone command end to end: server `COMMAND_TYPES` +
      validation (`phone-store.mjs`), risk (`phone-risk.mjs`), CLI
      (`idupi-phone.mjs`), app parser/model/executor, phone-side allowlist and
      approval gate; tests on both sides.
- [ ] T5 Agent: phone-agent prompt uses `shell` for optimization (trim caches,
      stop heavy background apps, report freed space) and falls back to
      `settings` when the channel is unavailable.
- [ ] T6 Self-recovery: on boot / Wi-Fi connect to the trusted network, the app
      re-enables wireless debugging and reconnects (needs T1 green).
- [ ] T7 Keep the bridge alive and recover it: foreground service in the
      bridge process, detect loss (process death, reboot) and re-bootstrap on
      Wi-Fi through T2.
- [ ] T8 Release: version bump, assembleRelease, push to phone, live check,
      docs (`idupi-server/docs/phone.md`, README), PR.

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
| T1 | (this commit) | Device evidence 2026-11-27 below. `assembleRelease` ok; 1.64-puente-shell (versionCode 65) installed by adb. |

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

## Next step

T2: in-app adb client so IDUPI can start the bridge itself (no PC), then turn
the bridge into a typed executor (T4) kept alive by the foreground service.
