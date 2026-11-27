# Phone on the Dashboard + "optimiza mi teléfono"

Locator: `odd/tasks/phone-dashboard.md` · Engram mirror: `odd/phone-dashboard/tasks`
Branch: `feat/phone-dashboard` (stacked on `feat/assistant-key`, PR #31)

## Objective

The Dashboard shows the phone next to the PC (swipe sideways or chips at the
top), and the owner can ask "optimiza mi teléfono" (by voice or text, from the
bubble or a button on the phone page) so the agent frees storage and RAM,
asking approval for anything it deletes.

## Evidence (exploration 2026-11-26, explore-1)

- `ui/screens/DashboardScreen.kt:83` shows PC metrics only (CPU, RAM, GPU,
  disks via `/api/v1/system/metrics`, 2 s poll); no device pager or selector.
- The phone already collects battery, signal, network, RAM (`MemoryInfo`),
  storage (`StatFs`), foreground app and top apps today
  (`data/phone/PhoneStatusCollectors.kt`), reported every 60 s to
  `POST /api/v1/phone/status`; the app never displays it.
- `StorageStatsManager` (per-app size and cache) is used nowhere; it needs
  usage access, which the app already declares and the owner granted.
- Bubble agent: Claude only, `idupi-phone` CLI (status, screen, tap, type,
  scroll, global, open, wait), max 15 CLI calls per turn
  (`idupi-server/lib/phone-agent.mjs:59`). "eliminar/borrar/delete" taps are
  gated (destructive) and are not voice-approvable today (T23 = messaging only).

## Owner decisions (2026-11-26)

- Optimization mode: the agent acts and asks approval for anything it deletes
  (option 1). Requests can be by voice or text.
- Layout: PC · Teléfono chips at the top plus sideways swipe (owner described
  both).

## Design

- Phone page reads locally on the phone (no server round trip): storage,
  RAM, battery, network, largest apps (app + cache size), most used today.
- "Optimizar" button starts a bubble turn with "Optimiza mi teléfono" (same as
  asking the bubble), so progress and approvals show in the bubble.
- Agent playbook (server prompt): read status incl. largest apps; Samsung
  "Cuidado del dispositivo" -> "Optimizar ahora" (other OEMs: their cleaner or
  Settings > Storage); clear cache of the largest caches via Settings > Apps >
  <app> > Almacenamiento > "Borrar caché"; T6: "Borrar datos", deleting
  files/photos/folders and uninstalling are allowed only when the owner asked
  for that deletion in the request, through an approval that says what is
  lost, and never on the agent's own initiative during an optimization;
  suggest uninstalling unused apps and only do it with approval; finish with
  the space freed (status before/after).
- Approvals: during an owner bubble turn, destructive cache/uninstall steps
  are asked in the bubble (voice + Aprobar/Rechazar buttons) naming the app and
  size; money never by voice; anything else keeps the notification.

## Tasks

- [x] T1 App: phone snapshot for the dashboard (reuse collectors) + per-app
      storage via `StorageStatsManager` (largest apps with app/cache bytes,
      pure mapping/sorting) + tests.
- [x] T2 App: Dashboard "PC · Teléfono" chips + `HorizontalPager`; phone page
      cards (storage, RAM, battery, network, largest apps, most used today);
      "Optimizar" button starting the bubble turn; pure state tests.
- [x] T3 Server: phone status gains largest apps; optimization playbook in the
      phone agent prompt; CLI budget per turn raised for optimization; tests.
- [x] T4 App: bubble approval for owner-turn cache/uninstall steps (voice,
      naming app and size) + Aprobar/Rechazar buttons on every bubble approval
      prompt; money never voiced; tests.
- [x] T6 Owner correction (2026-11-26): no deterministic hard block for data
      wipes. "Borrar datos", "Borrar almacenamiento", deleting files/photos or
      the Downloads folder are allowed when the owner asks for it, through an
      approval that names what is erased and what is lost ("¿Estás seguro?"),
      voice/buttons in the bubble during the owner's own turn. The agent never
      wipes data on its own initiative (playbook rule; the approval still
      applies). Money stays notification-only. Also: the server suite hung for
      20+ minutes after T3 (worker reported 16 s); find and fix the hang.
- [ ] T5 Release: version bump, assembleRelease, install, owner live check
      ("optimiza mi teléfono" by voice and by text), PR.

## Acceptance criteria

- Swiping or tapping "Teléfono" on the Dashboard shows live phone metrics.
- "Optimiza mi teléfono" (voice, text or button) runs the optimization; every
  deletion is approved by the owner in the bubble; the answer says how much
  space was freed.
- Nothing is deleted without approval; "Borrar datos" is tapped only when the
  owner explicitly asked for that deletion and approved it in the bubble (T6:
  reasoned approval, not a deterministic block).

## Checks

- App: `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`.
- Server: `node --test idupi-server/test/*.test.mjs`.
- On-device check over adb.

TDD: on (source: project practice; runners: JUnit via Gradle, node:test).

Delivery: forecast ~1200 authored lines; strategy `exception-ok` (project
precedent), one PR stacked on #31.

## Progress

- 2026-11-26: exploration done; owner chose "act and ask approval"; branch
  created on `feat/assistant-key`.

- 2026-11-26: T1+T2 done (writer idu-pi work-1): `PhoneDashboardSnapshot`,
  `PhoneDashboardCollector` (StorageStatsManager per launchable app, 60 s
  cache), `PhoneDashboardViewModel` (5 s poll), `DashboardPager` +
  `DashboardPhonePage`, `BubbleRequestHost` (Optimizar sends through the
  bubble controller so the turn is owner-registered). RED: compile failures on
  the new types. GREEN: 141 suites, 1306 tests, 0 failures (orchestrator
  re-run). Commit `9955539`. Device check pending (T5). Engram mirror pending:
  mem_save refused with "multiple active runtime sessions".
- 2026-11-26: while preparing T3 found that "desinstalar"/"uninstall" are not
  gated by the risk classifier (only eliminar/borrar/delete); added to T3 with a
  hard block for "Borrar datos"/"Borrar almacenamiento".

- 2026-11-26: T3+T4 (writer work-1) and T6 (writer work-1, owner correction:
  no hard block; data wipe = gated destructive approval with riskKind
  data_wipe, allowed only when the owner asked). Uninstall now gated (was not).
  Approvals carry ownerTurnId; all destructive approvals in an owner turn are
  asked in the bubble (voice + Aprobar/Rechazar), money notification-only.
  Server suite hang: two runner tests relied on the real 10-minute timer;
  injected short clocks + 5 s per-test timeouts. Orchestrator runs: server
  1037/1037 in 14 s, app 142 suites / 1340 tests / 0 failures. Commits
  `cf6c331` (server), `8d34df1` (app); 1.61-dashboard-telefono (versionCode
  62, `6c6758b`) installed over adb; server restarted 22:57 with the new rules.

## Next step

T5: owner live check of the Dashboard phone page and "optimiza mi teléfono"
(voice and text), then PR stacked on #31. Engram mirror still pending.
