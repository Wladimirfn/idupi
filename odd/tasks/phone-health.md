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
- [x] T2 App sources + Health Connect: detect installed health apps/devices and
  their route (Health Connect / hidden display / not supported); Health Connect
  permissions and reader (steps, distance, calories, heart rate, resting HR,
  sleep sessions/stages, exercise, weight, SpO2, with data origins);
  digital wellbeing extras (total screen time, unlocks, night use); reporter to
  the PC; tests.
- [x] T3 AI digest + UI: server daily digest through the configured agent
  (health + wellbeing + agenda, neutral Spanish, not medical advice, cached per
  day); app "Salud" drawer screen (today per metric with source, 7-day trends,
  digest, "Actualizar ahora") and Ajustes > SALUD (sources, Health Connect
  permissions, PC/ADB status); tests.
- [x] T4 Huawei Health Kit groundwork: `idupi-server/docs/huawei-health-kit.md`
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
- T2 done in five commits: 488103c (sources + Health Connect reader),
  c8757a4 (digital wellbeing), c47bc39 (reporter + client), 4af93b7 (Ajustes >
  SALUD), fdb67e8 (background Health Connect access). 40 files, +4240/-1.
  - Dependency: `androidx.health.connect:connect-client:1.1.0` (last stable;
    1.2.x is alpha). compileSdk 36 / minSdk 29 already satisfied it; no
    Gradle, AGP, Kotlin or SDK change.
  - `lib/health` equivalent in the app is `data/health`: catalog of 14 known
    apps (`com.sec.android.app.shealth`, Mi Fitness x2, Zepp Life, Zepp,
    Google Fit, Fitbit, Garmin, Withings, Polar, Oura, Strava ->
    `health_connect`; Huawei/Honor -> `hidden_display`), plus Health Connect
    data origins -> `installedApps` entries with route `health_connect`
    (PackageManager label, package name fallback). Manifest uses per-package
    `<queries>` (no QUERY_ALL_PACKAGES) and a MAIN/HOME query so launchers can
    be excluded from screen time.
  - Health Connect: read permissions for Steps, Distance, Total/Active
    calories, HeartRate, RestingHeartRate, SleepSession, ExerciseSession,
    Weight, OxygenSaturation; `READ_HEALTH_DATA_IN_BACKGROUND` for the 3 h
    background report; `ACTION_SHOW_PERMISSIONS_RATIONALE` activity +
    `VIEW_PERMISSION_USAGE` alias with a neutral-Spanish rationale screen.
    Aggregates use the aggregate API (steps, distance, calories, HR avg/min/
    max, resting HR AVG) so overlapping writers are de-duplicated by Health
    Connect. Sleep/workouts/weight/SpO2 are raw reads bucketed by owner-local
    day: sleep by end day (a night reported by two writers is merged, not
    summed), workouts by start day, latest weight/SpO2. Partial grants and
    per-record failures degrade to the remaining metrics.
  - Wellbeing: `UsageStatsManager` events -> per-day screen time, unlocks
    (`KEYGUARD_HIDDEN`), night minutes (00-06 local, intervals split at
    midnight) and top 5 apps; IDUPI + launchers excluded; no usage access ->
    no wellbeing samples (not a day of zeros).
  - Reporter: `POST /api/v1/health/samples` with 7 days + `installedApps`;
    3 h loop hosted by the inbox listener/accessibility service/MainActivity
    (shared idempotent instance), start/resume at most every 30 min, and
    "Enviar ahora". Client trio adds `postHealthSamples`,
    `getHealthSummary(days)`, `getHealthSources`, `refreshHealth` (job id)
    and `getHealthRefresh` for T3.
  - Ajustes > SALUD: HC state (available / needs update / missing with
    Play Store), permission count + request, detected sources with route
    labels, last report and manual send; re-checked on every resume.
  - Verification: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease
    -q` OK; 840 unit tests, 0 failures (94 suites); release APK built.
    Merged release manifest carries the 10 record permissions, the
    background permission, the rationale alias and the package queries.
  - Still to verify on the owner's phone (T5): the real permission dialog and
    partial-grant flow, background reads on Android 16, Samsung Health/Mi
    Fitness data actually arriving, data-origin labels for apps hidden by
    package visibility, and the Play Store/Health Connect intents. No
    Robolectric: the Android adapters are compile-checked only, the rules are
    covered by the pure tests.
  - Deviation: the reporter has no on/off switch (not requested for T2); it
    sends only when a PC profile is saved. `sourceApp` is empty for both
    sources; origin packages travel in `installedApps`, never in metrics.
- T3 server done in three commits: 730b66d (Huawei Kit source, shared with
  T4), 903bf0b (digest prompt/cache), 1842534 (routes, wiring, CLI, docs).
  - `lib/health-digest.mjs`: prompt built from the last 7 merged days, the
    latest wellbeing day (screen time, unlocks, night screen, top apps) and
    today/tomorrow agenda; day numbers, app labels and event titles travel in
    `<untrusted_health_data>`, `<untrusted_wellbeing>` and `<untrusted_agenda>`
    blocks, delimiter-shaped text neutralized with `sanitizeUntrusted`. The
    prompt asks for exactly `{summary, highlights, recommendations,
    agendaNotes}`, demands neutral Latin American Spanish, that numbers come
    only from the data, and states it is not medical advice / never diagnoses.
    Store `data/health-digests.json` keeps 30 days; interrupted `analyzing`
    becomes `failed` on load. Cache key = owner-local date + SHA-256 of inputs;
    a ready record with the same hash is served without an agent.
  - Routes: `GET /api/v1/health/digest?date=` (cached state, never runs the
    agent; past dates readable) and `POST /api/v1/health/digest {date?,
    refresh?}` (202 analyzing, 200 cached/ready; only today can be generated).
    `available:false` when the window has no health data, so no agent runs on
    nothing. `GET /sources` gained `futureSources`.
  - Automatic regeneration fires after a successful hidden read or a samples
    POST, at most once every 3 h (`maybeAutoGenerate` throttles; unchanged
    inputs skip). The service reuses the inbox runner/config (`inbox-config.json`
    agent/model/effort) and its own serial agent queue.
  - CLI: `idupi-phone health digest [--refresh] [--json]` (poll 120 s when
    refreshing) and the sources list prints future sources.
  - Tests: `health-digest.test.mjs` 23 cases (delimiter injection, units,
    parse, hash, store retention/corruption, cache, failure/retry, no-data,
    only-today, 3 h throttle, routes, sources, collect inputs) and CLI cases.
  - Docs: `docs/phone.md` routes table + "Daily digest (T3)" section.
- T3 app done in two commits: d4e13f9 (Salud screen + client, 11 files,
  +1874) and 5a297f2 (Ajustes extension, 5 files, +358).
  - `data/health/HealthSaludModels.kt`: pure mappers to today cards (Pasos with
    goal progress, Sueño with stages, Frecuencia + reposo, Estrés, SpO₂, Peso,
    Ejercicio, Agua, Pantalla + desbloqueos + noche, Top apps), source labels
    from the per-metric `sources` map, four 7-day series (steps/pantalla bars,
    sueño/pulso sparklines) and deterministic neutral-Spanish formatting.
    Only the day matching today feeds the cards, so yesterday is never shown as
    today; a day without samples renders the empty state pointing to
    Ajustes > SALUD.
  - `ui/screens/HealthSaludScreen.kt`: header with the date and "Actualizar
    ahora" (spinner + "Leyendo Huawei Health desde el PC…"), Canvas charts, and
    the digest card with summary/highlights/recommendations/agenda notes,
    "Generar resumen" / "Actualizar resumen", "Analizando…", error + "Reintentar"
    and the "No es consejo médico." footer.
  - `viewmodel/HealthSaludViewModel.kt` + `data/health/HealthRefreshRun.kt`:
    loads summary + cached digest; refresh = POST /health/refresh → poll every
    1 s up to 60 attempts → phone-side `HealthReporting.reportNow()` → reload
    summary + digest; digest generation polls up to 240 s (the agent timeout is
    180 s). Errors map to retry copy instead of inventing success.
  - Client: `getHealthDigest`, `requestHealthDigest` (body built by hand so an
    absent date is omitted) and `getHealthAdbStatus`; `HealthSummary` models
    gained the digest, ADB and future-source shapes.
  - Navigation: `Screen.HEALTH("Salud")` in the drawer right after Agenda, with
    its own ViewModel instance.
  - Ajustes > SALUD: PC block with ADB connected/serial, saved target, scrcpy
    version, a short explanation of the hidden reading and "Leer Huawei Health
    ahora" (same refresh loop, then the phone-side report, then sources + ADB
    reload). Health Connect controls unchanged.
  - Tests: `HealthSaludModelsTest` (8 cases) and `HealthSaludViewModelTest`
    (12 cases: load, summary failure, refresh done/failed/timeout, reading
    message, double-tap guard, digest ready/failed/throw/poll, clear error),
    plus 6 HealthSettings cases and the ADB copy test.
  - Verified: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`
    OK; 96 suites, 867 tests, 0 failures; release APK built. Server:
    74 test files, 0 failures; `node --check idupi-server/index.mjs` OK.
  - Still to verify on the owner's phone/PC (T5): the real Salud screen render
    and drawer entry, a real "Actualizar ahora" against a paired ADB target,
    the real digest with the configured agent (tests inject a fake runner;
    nothing was generated against a real model), and the Ajustes ADB block on a
    phone with/without a saved target. `NeutralSpanishCopyTest` is green.
- T4 done in 730b66d: `docs/huawei-health-kit.md` walks the owner through the
  HUAWEI Developer account (identity verification, ~3 workdays, unverified),
  AppGallery project/app, HUAWEI ID + Health Service Kit scopes (steps, sleep,
  heart rate, stress, SpO₂, weight; exact strings must be copied from the
  official scope reference at registration), review requirements (privacy URL,
  use case, screenshots; up to ~15 workdays, test mode ~100 users, both marked
  unverified), OAuth 2.0 authorization-code flow, the
  `https://health-api.cloud.huawei.com/healthkit/v1/...` family (e.g.
  `sampleSet:polymerize`, marked unverified) and the disabled
  `data/huawei-health-kit.json` shape with `IDUPI_HUAWEI_CLIENT_SECRET`.
  `lib/huawei-health-kit.mjs` exports `huaweiHealthKitStatus()` →
  `{enabled, configured, reason}` and `futureHealthSources()`; no network call.
  9 tests cover missing/corrupt/complete configs, the secret never in the file,
  custom env names and the future-source shape. `GET /health/sources` lists
  `huawei_health_kit` in `futureSources` with no captures.
  - Deviation: `reason` distinguishes `registered_but_disabled` and
    `missing_config:<fields>` beyond the required three fields; no scope
    strings are validated because the official list is checked at registration.
- T3/T4 deviation summary: the digest POST supports only today (GET reads
  cached past days); the API adds `available` so no agent runs without data;
  the digest is forced by the app button and throttled only for automatic runs;
  no real agent run, ADB read or device render happened in this session.
- Line heuristic: T3+T4 authored ~4.6k lines including tests; the tasks exceed
  the advisory 400-line planning heuristic, and the commits are the coherent
  work units requested (server digest, app screen, Ajustes, Huawei docs/stub).
- T5 (partial): 8f5d7d6 digest durations as h/min; version 49 / 1.48-salud,
  app 867 tests / 0 failures, cert d8ba0fc9, delivered dlv-4bec7ce2da55;
  server restarted (no PTYs). Live: `health sources` lists Huawei (captured),
  Health Connect/Samsung/Mi Fitness/wellbeing pending the phone report, Huawei
  Health Kit as a disabled future source; `health digest --refresh` with
  opencode/deepseek-v4.1-flash produced a neutral-Spanish digest from the real
  data (week comparison, recommendations, agenda notes, "No es consejo
  médico"). Pending on the device: install 1.48, Health Connect permissions,
  wellbeing/HC samples reaching the PC, Salud screen.
