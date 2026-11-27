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

## T4 contract (decided 2026-11-27)

- Phone command `{ "type": "shell", "args": { ... } }`, args by action:
  - `{ "action": "info", "topic": "storage"|"memory"|"battery"|"packages" }`
  - `{ "action": "trim-caches" }`
  - `{ "action": "force-stop", "package": "<pkg>" }`
  - `{ "action": "background", "package": "<pkg>", "mode": "allow"|"ignore" }`
  - `{ "action": "disable", "package": "<pkg>" }`
  - `{ "action": "enable", "package": "<pkg>" }`
  No other key or action is accepted (both sides reject unknown args).
- Package names: same regex as `PackageNames.isValid`, max 200 chars.
  Protected packages (force-stop/background/disable refused on both sides):
  `com.idupi.app`, `com.idupi.shell`, `android`, `com.android.systemui`,
  `com.android.settings`, `com.android.phone`, `com.android.providers.settings`,
  `com.google.android.gms`, `com.sec.android.app.launcher`,
  `com.samsung.android.honeyboard`.
- Risk: `info`, `enable` free; `trim-caches`, `force-stop`, `background`
  gated with category `optimization` (auto-approved in an owner turn exactly
  like `message`, otherwise asked); `disable` gated `destructive` (voice).
- Execution: IDUPI calls the helper through its ContentProvider
  `content://com.idupi.shell.bridge` (`ContentResolver.call(method, arg, extras)`),
  protected by the signature permission `com.idupi.shell.permission.BRIDGE`
  plus a calling-package/signature check. The helper re-validates the
  allowlist before running anything. Result: human-readable Spanish text plus
  `ok` boolean; `trim-caches` reports free space before/after.
- Bridge status method `status` -> `active` only when the instrumentation
  holds the shell identity (a helper process started by a provider call
  without instrumentation reports `inactive`).

## T4b contract: scan -> propose -> one approval -> execute (owner, 2026-11-27 11:15)

Owner: "hace un scan luego propone, luego ejecuta" with ONE approval for the
whole set, never one per app.

- `{ "action": "scan" }` (free, runs in the app, no helper needed): third-party
  apps with `cacheBytes`, `appBytes`, `dataBytes`, `lastUsed` (UsageStats, the
  app already holds usage access), total reclaimable cache, and candidates:
  `unused` (not opened in 30+ days, launchable, not protected), `heavyCache`
  (top cache holders). Returns Spanish text plus a JSON block the agent reads.
- `{ "action": "uninstall", "package": "<pkg>" }` -> `pm uninstall --user 0
  <pkg>`; destructive (voice), protected packages refused.
- `{ "action": "batch", "steps": [ ... ] }`: 1-30 steps, each one of
  trim-caches | force-stop | background | disable | enable | uninstall (no
  info/scan/batch inside, no duplicate step). ALWAYS gated as ONE approval with
  category `plan`: the approval card/notification/voice prompt lists every
  step in Spanish (e.g. "Borrar la caché de todas las apps; desinstalar 3
  apps: A, B, C"). In an owner turn it is asked in the bubble (voice when it
  contains uninstall/disable), never auto-approved. On approval the phone runs
  the steps in order through the helper, continues past a failed step, and
  returns one summary (per step ok/fail, freed space for trim-caches).
- Approval reasons: shell commands get their own reason labels instead of
  "texto riesgoso" (`shell_optimization`, `shell_destructive`, `shell_plan`).
- Agent (T5): for "optimiza mi teléfono" the phone agent runs `shell scan`,
  replies with the proposal list, then sends ONE `shell batch`; falls back to
  `settings` navigation only when the bridge is unavailable.

## T4c changes after the first live plans (owner, 2026-11-27 12:10)

Evidence (turns pat-5ca884e208cc .. pat-6f8504b15c9a, 11:50-12:05): trim-caches
worked (reclaimable cache 8.8 GB -> 1 GB); 19/19 uninstalls failed with an
Android NullPointerException (`pm uninstall` run as a child of the helper);
the owner approved three plans in three turns; a follow-up turn ("aprobado
pero deja MiniMax") could not see the previous proposal. Owner: "siento que
estamos siendo muy estrictos ... con tanta aprobación que no logra ejecutar".

- Approval: a `batch` plan inside an owner turn is auto-approved (the owner's
  own request is the consent), on the server AND on the phone gate. Outside an
  owner turn it stays ONE `plan` approval. Single destructive shell actions in
  an owner turn follow the same rule.
- Agent: if the request already authorizes the actions ("borra la caché",
  "desinstala lo que no uso menos X") run ONE batch directly. For a generic
  "optimiza", run the safe steps at once (trim-caches, background limits) and
  PROPOSE uninstall/disable as a numbered list; execute them when the owner
  confirms in a later turn.
- Cross-turn context: the agent prompt includes the owner's recent turns
  (last 3 within 15 minutes: request + answer) so a confirmation like "sí,
  todas menos MiniMax" resolves against the previous proposal.
- Uninstall: run in-process in the helper with `PackageInstaller.uninstall`
  (caller package `com.idupi.shell`, DELETE_PACKAGES via the adopted shell
  identity, result through an IntentSender/BroadcastReceiver, 30 s timeout);
  `pm uninstall` stays only as a fallback.
- Bootstrap failure text no longer says "Reinicia el teléfono".

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
- [x] T3 App pairing UX: Ajustes > "Acceso avanzado del teléfono" row with
      status (no vinculado / vinculado / conectado / no disponible), "Vincular"
      opens Wireless debugging and posts a notification with a `RemoteInput`
      for the 6-digit code; after pairing, grants WRITE_SECURE_SETTINGS.
- [x] T3b Bootstrap: embed the helper APK in IDUPI's assets at build time;
      over the adb session install/update it (`pm install -r`) and start the
      bridge (`am instrument`); "Iniciar puente" action and status in the
      same Ajustes row.
- [~] T4 `shell` phone command end to end: helper bound service
      (signature permission) with the typed allowlist, server `COMMAND_TYPES`
      + validation (`phone-store.mjs`), risk (`phone-risk.mjs`), CLI
      (`idupi-phone.mjs`), app parser/model/executor calling the helper,
      approval gate; tests on both sides.
- [~] T4b Scan, uninstall and one-approval batch (contract above).
- [ ] T4c Approval relax, cross-turn context, PackageInstaller uninstall.
- [~] T5 Agent: phone-agent prompt uses `shell` for optimization (trim caches,
      stop heavy background apps, report freed space) and falls back to
      `settings` when the channel is unavailable.
- [ ] T6 Self-recovery: on boot / Wi-Fi connect to the trusted network, the app
      re-enables wireless debugging and reconnects.
- [ ] T7 Keep the bridge alive and recover it: detect loss (helper death,
      reboot) and re-bootstrap on Wi-Fi through T2/T3b.
- [x] T7b Accessibility rebind safety net. CORRECTION 2026-11-27 11:00: the
      premise "an adb `install -r` leaves IDUPI's accessibility service
      unbound" was wrong. `dumpsys accessibility` prints one bound service per
      line and the checks at 09:50-09:55 read only the first line; after the
      1.69 install IDUPI, LG and Microsoft were all bound (ServiceRecord
      alive). The only real loss was the morning one (`Bound services:{}`,
      stale UiAutomation from the in-app bridge). Kept: the app-side net
      (`AccessibilityKick` on start and `MY_PACKAGE_REPLACED`, acts only when
      the service is enabled but not connected; helper `kick-accessibility`
      first, adb `uiautomator dump` fallback). Removed: the PC push-flow kick
      (it suppressed accessibility on every install for nothing).
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
| T4b live 1 | af684b6 | 11:48 1.71 installed + server restarted; `shell scan` ok (133 third-party apps, 8.8 GB reclaimable cache, 20 unused 30+ days). Owner "Iniciar puente": helper 0.4 installed at 11:49:40, but the bridge never started (11:49:41 and 11:52:21): the reader stops at the exit marker and closes the stream right after `setsid ... &`, killing the child before it left the session. Same command with the session held open worked from the PC. Fix: `startBridgeDetached()` ends with `& sleep 3`. 1.72 (versionCode 73) installed, bridge stopped so the owner's retry exercises the fix. Follow-up: the failure text "Reinicia el teléfono" is misleading. |
| Off Wi-Fi | (docs commit) | 11:42 owner left home: phone on LTE through the VPN (`Red: vpn`, adb `offline`). Through the server: `shell info storage` 85.19 GB free of 238 GB, `info battery` 57 %, privileged `shell enable com.sec.android.app.sbrowser` -> "App activada", `info packages` 150. The bridge (helper 0.3, pid 2791 since 11:08) works away from the home Wi-Fi: the owner's hard requirement is met. |
| T4b | ce6f8c0, 28ea085 | Server (work-2): scan/uninstall/batch validation, batch always ONE `plan` approval never auto-approved (`riskKind: batch_destructive` -> voice), reasons shell_optimization/shell_destructive/shell_plan, CLI `shell scan|uninstall|batch`, T5 agent playbook scan -> proposal -> one batch, Settings fallback only on "puente no disponible"; 126 tests ok (full suite 1061/1062, the one failure is the known opencode SIGTERM in sessions-runtime). Android (work-1): `PhoneScanCollector` (StorageStats + UsageStats, 30-day unused, top-10 cache), batch loop through the helper (helper re-validates each step), grouped Spanish plan text + plan voice prompt, helper 0.4 with `uninstall`; `:app:testDebugUnitTest` 1487 ok, helper 8 ok. 1.71-plan-optimizacion (versionCode 72) built; install + server restart pending the owner's return to Wi-Fi (server restart waits so the agent prompt does not ask an old app for scan/batch). |
| T4 live | (helper fix commit) | 11:06-11:09 via `idupi-phone shell ...`: info storage/memory/battery ok; `enable com.sec.android.app.sbrowser` first failed "Unknown package" (package visibility for the helper uid; the shell permission itself passed, PackageManager logged the call), fixed with `QUERY_ALL_PACKAGES` in helper 0.3 -> enable ok, packages = 150. So child processes of the helper DO carry the shell identity. trim-caches queued an approval labeled "texto riesgoso" (wrong label). Owner's "Optimiza mi teléfono" (pat-4bd2ba9df7f1, 11:11) still navigated Settings for 40 steps with per-app approvals: the agent does not know `shell` yet (T5). |
| T4+T7b | 82bb581, c779011, (revert commit) | Server worker work-2 (commandcode): `shell` in COMMAND_TYPES + validation, risk (`optimization` auto in owner turn like `message`, `disable` destructive), CLI `idupi-phone shell ...`, docs; 105 server tests ok. Android worker work-1: helper 0.2 (signature permission `com.idupi.shell.permission.BRIDGE`, call-only `BridgeProvider`, `BridgeState`, `ShellRequestValidator`, `ShellExecutor` fixed argv + API fallbacks, `kick-accessibility`), app `ShellBridgeClient`, `PhoneCommand.Shell`, gate/voice routing, bootstrap decides by helper `status`; `:shellhelper:testDebugUnitTest` 8 ok, `:app:testDebugUnitTest` 1449 ok. 1.69-comandos-shell (versionCode 70) installed 10:58; T7b net logged `kick skipped: enabled=true connected=true` (correct). Live shell commands pending (needs helper 0.2 via "Iniciar puente"). |
| T3/T3b live | (docs commit) | Owner tapped "Iniciar puente" at 10:17:31 with no PC involved: `pm path` (helper missing) -> streamed `exec:cmd package install -r -t -S 2104902` done in 1.6 s -> detached `setsid ... am instrument` -> helper pid 24526 `adopted flags=1 uid=10501`, probes ok every 5 s; IDUPI's accessibility service stayed bound. Pairing (10:09) + bootstrap now proven end to end on the S24. |
| T3 fix 2-3 | 815ae7c, d4813f8 | Owner retry 10:09: mDNS found the pairing service (192.168.1.8:46079) and pairing succeeded over loopback (`paired via 127.0.0.1:46079`, adbd persisted the key). Connect ok, `pm grant` ok (`WRITE_SECURE_SETTINGS: granted=true`), then `pm path com.idupi.shell` hung 10 s: libadb 3.1.1 bug, a peer CLSE that arrives while data is still queued only sets `mPendingClose`, so the next `read` waits forever. Reader now stops at the exit-marker line (`ShellOutputParser.isComplete`, tested). Failures are now logged under tag `IdupiPhoneShell` (code masked). 1.68 (versionCode 69) installed. |
| T3 fix | (this commit) | Owner's first try crashed at 09:57:47 ("IDUPI se cerró"): `PhoneShellService` resolved `NotificationManagerCompat.from(this)` in a property initializer, before the Service has a base context (NPE in `ContextWrapper.getSystemService`). Now lazy. 1.66-acceso-avanzado (versionCode 67) installed; accessibility rebound with the uiautomator kick (T7b). |
| T3+T3b | a6b6e83 | worker work-1. Ajustes > "Acceso avanzado del teléfono" (`SettingsRoute.PhoneShell`), `PhoneShellService` (FGS dataSync: mDNS pairing port, RemoteInput code, pair, bootstrap, 5 min cap), `ShellBridgeBootstrap` (connect, pm grant WRITE_SECURE_SETTINGS, streamed `cmd package install -S` with file fallback, detached `setsid am instrument`, pidof + probe check), helper APK embedded as `assets/idupi-shell-helper.apk`. `:app:testDebugUnitTest` 1413 ok (57 in phone.shell); `:app:assembleRelease` ok; 1.65-acceso-avanzado (versionCode 66) installed by adb, helper uninstalled first so the app installs it itself. Live pairing pending (owner). |
| T2 | 68de31d | worker work-1 (opencode, deepseek-v4.1-flash). `:app:testDebugUnitTest --tests com.idupi.app.data.phone.shell.*` 36 tests ok; `:app:assembleDebug` ok. Deps: libadb-android 3.1.1 (JitPack, filtered), conscrypt-android 2.5.3, bcpkix/bcprov 1.81. Not device-verified yet (pairing, mDNS, loopback connect, exec): checked live in T3. Note for T3b: `am instrument -w` never returns, so launch it detached (`setsid sh -c '...' &`) instead of through a blocking `exec`. |
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

T4: the typed command surface in the helper (signature-protected bound
service + allowlist) and the `shell` phone command end to end; T7b
accessibility rebind kick so updates stop leaving the bubble disconnected.
