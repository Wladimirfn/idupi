# Phone health and digital wellbeing (Salud)

Locator: `odd/tasks/phone-health.md` · Engram mirror: `odd/phone-health/tasks`
Branch: `feat/phone-health` (stacked on `feat/phone-calendar`)

## Objective

IDUPI gathers the owner's health and digital-wellbeing data from whatever the
phone has (Samsung Health, Huawei Health, Honor Health, Mi Fitness, Zepp,
Fitbit, Google Fit, Garmin...) plus Android usage stats, keeps a daily history
on the PC, and the configured agent produces a daily summary with trends and
recommendations (crossed with the agenda). The owner sees it in a new "Salud"
screen.

## Verified facts (2026-11-24)

- Health Connect is the standard store: Mi Fitness and Samsung Health write
  steps/heart rate/sleep/calories to it (Mi Fitness does not share stress/HRV).
- Huawei Health does NOT write to Health Connect. Its official route is Huawei
  Health Kit (developer registration + OAuth + review up to ~15 workdays):
  prepared now, registered later with the owner.
- Hidden virtual display route works on the owner's SM-S928B (Android 16) over
  wireless ADB (LAN and Tailscale): scrcpy 5.0.1 `--new-display=1080x2340/440
  --start-app=<pkg> --no-window --no-audio --record=<temp.mkv> --max-fps=5`
  creates a display; `adb shell input -d <id> keyevent KEYCODE_WAKEUP` wakes only
  that display group (phone locked, real screen stays OFF, owner confirmed);
  `adb shell uiautomator dump --display <id> <file>` returns the app's view
  hierarchy. Huawei Health "Hoy" ids: step_text, title_text/value_text/
  goal_text/value_des (Levantarse, Ejercicio, Estrés, Consumo de agua),
  quick_entry_text, function_set_items_title/_data/_unit (Registros de
  ejercicio, Corazón, Peso, Sueño). Killing scrcpy destroys the display.
- scrcpy needs a video consumer (`--record` to a temp file) and control enabled
  for `--start-app`; use the SDK adb (env ADB) to avoid adb server clashes. The
  wireless-debugging connect port changes per session; mDNS advertised only the
  pairing service on this network.

## Scope

In: health source detection, Health Connect reader, digital wellbeing extras,
hidden-display reader for apps without Health Connect (Huawei Health; Honor
Health tentative), ADB connection manager, health store/routes/CLI, AI daily
digest, Salud screen + Ajustes section, Huawei Health Kit registration guide.
Out: medical advice/diagnosis, writing health data, Huawei Health Kit live
integration (after registration), ARTEMIS integration (next feature).

## Constraints

- Observation only: nothing is sent/approved on the phone. Risky actions always
  need explicit owner approval (owner rule 2026-11-24).
- ADB/scrcpy run with fixed argv from an allowlist (no arbitrary shell from any
  route); only allowlisted health packages are launched; temp recordings and
  dumps are deleted; no health values in logs.
- Health data leaves the phone only to the owner's PC and configured agent.
- Neutral Latin American Spanish; the digest is not medical advice and says so.
- Code/comments/commits in English; zero-dependency server; APK versionCode
  above 48.

## Tasks

- [x] T1 Server: ADB connection manager (saved targets, mDNS connect discovery,
  last-known LAN/Tailscale host:port, reconnect, status) + hidden-display
  reader (scrcpy discovery, launch, wake display, dump, parse, cleanup, lock,
  timeouts) with per-app profiles (Huawei Health; Honor Health tentative) +
  health store + routes (`POST /api/v1/health/samples`, `GET
  /api/v1/health/summary`, `GET /api/v1/health/sources`, `POST
  /api/v1/health/refresh`, ADB pair/connect helpers) + scheduler (every 3 h) +
  `idupi-phone health` CLI + docs + tests.
- [ ] T2 App sources + Health Connect: detect installed health apps/devices and
  their route (Health Connect / hidden display / not supported); Health Connect
  permissions and reader (steps, distance, calories, heart rate, resting HR,
  sleep sessions/stages, exercise, weight, SpO2, with data origins);
  digital wellbeing extras (total screen time, unlocks, night use); reporter to
  the PC; tests.
- [ ] T3 AI digest + UI: server daily digest through the configured agent
  (health + wellbeing + agenda, neutral Spanish, not medical advice, cached per
  day); app "Salud" drawer screen (today per metric with source, 7-day trends,
  digest, "Actualizar ahora") and Ajustes > SALUD (sources, Health Connect
  permissions, PC/ADB status); tests.
- [ ] T4 Huawei Health Kit groundwork: `idupi-server/docs/huawei-health-kit.md`
  step-by-step registration guide (AppGallery Connect project, HUAWEI ID,
  Health Kit scopes, test users, review) + disabled connector config shape.
- [ ] T5 Deliver: version bump, APK, server restart, live check (sources,
  Huawei hidden read with phone locked, Health Connect data, digest).

## Acceptance

- `idupi-phone health sources` lists the installed health apps and their route.
- With the phone locked, `idupi-phone health refresh` reads Huawei Health
  without lighting the screen and stores today's values.
- Health Connect data from Samsung Health / Mi Fitness reaches the PC.
- Salud shows today's metrics, trends and the daily digest.

## Checks

- Server: `node --test idupi-server/test/<file>.test.mjs` per file.
- App: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config).

## Delivery

Forecast ~3-4k authored lines; one stacked PR after #24, commits by task;
then the owner wants the whole chain merged to main.

## Progress

- Branch created from feat/phone-calendar a8ef150. Wireless ADB paired and
  connected; scrcpy installed (winget Genymobile.scrcpy 5.0.1).
- T1 done in seven commits: a2aee2f (ADB manager + reader), 4bc1442 (profiles),
  f7784c6 (store + routes + scheduler), 12b0d40 (CLI), e2435c9 (wiring),
  31c9ea3 (docs), fb735ac (deadline + split step block follow-up).
  - `lib/adb-connection.mjs`: `IDUPI_ADB` > SDK path > PATH; scrcpy via
    `IDUPI_SCRCPY` > PATH > newest winget copy; targets in
    `data/adb-targets.json` (max 8); ensureConnected order devices -> mDNS
    `_adb-tls-connect` -> saved serials -> 30000-49999 probe on saved hosts
    (200 sockets, 300 ms, 45 s cap); pair/connect/status; execFile only.
  - `lib/hidden-display-reader.mjs`: fixed scrcpy argv, `New display ... id=N`
    parse, wake with `-d N`, dump+cat+rm, one retry, global mutex, 60 s cap,
    temp `.mkv` and remote `.xml` deleted in `finally`; allowlist
    `com.huawei.health` + `com.hihonor.health`.
  - `lib/health-profiles.mjs`: Huawei "Hoy" parser (`step_text`,
    `title_text/value_text/goal_text/value_des`, `function_set_items_*`),
    localized numbers, units, missing -> null; Honor reuses it tentative.
  - `lib/health-store.mjs` + `lib/health-routes.mjs`: metric schema + ranges,
    latest capture per source/day wins, 120-day retention, summary with
    per-metric sources, sources + installedApps, in-memory refresh jobs,
    ADB routes, 3 h scheduler with no overlap and quiet ADB skips.
  - App wire shape (T2) documented in `idupi-server/docs/phone.md`
    (`POST /api/v1/health/samples` + `installedApps`).
  - Tests: new suites for adb-connection, hidden-display-reader,
    health-profiles, health-store, health-routes + CLI cases. Full server
    suite: 72 files, 0 failures; `node --check idupi-server/index.mjs` OK.
  - Still to verify live (T5/owner): real pair/connect through
    `idupi-phone health adb`, a hidden-display read with the phone locked, and
    a stored real "Hoy" sample; Honor prefix on a real Honor device.
- Live (parent, phone locked, screen OFF): first server refresh failed
  (app_not_visible: with the phone locked scrcpy --start-app leaves the
  launcher on the sleeping display) and left a temp .mkv (Windows lock).
  de353da relaunches the resolved launcher with `am start --display N` after
  waking the display, retries the unlink, and keeps the collector reason in the
  job. 0d8c77f pairs Huawei values by column (two-column cards leaked heart
  rate into weight), reads off-screen cards ([0,0][0,0]) in document order,
  uses a 1080x4200 display so Sleep/SpO2 are laid out, 3 attempts; realistic
  synthetic fixture. Live refresh: 17 metrics (steps, sleep, HR, resting HR,
  exercise, water, weight, SpO2, stress, wake/bed goals, workout), no temp
  files left. Server suite green per file.
