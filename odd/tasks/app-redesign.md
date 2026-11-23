# App redesign: terminals first, deliveries, processes, system dashboard

Locator: `odd/tasks/app-redesign.md` · Engram mirror: `odd/app-redesign/tasks`
Branch: `feat/app-redesign` (stacked on `feat/apk-delivery`)

## Objective

The live interactive terminals replaced chat, the SDD orchestrator and labs for the owner.
Reshape the app around what is actually used: keep the same side-drawer app, remove the
dead surfaces, give terminals and deliveries their own entries, rename the CLI console to
"Procesos", and turn the Dashboard into a PC health monitor.

## Owner decisions (2026-11-23)

- Keep the current drawer navigation ("la misma app"); no bottom bar.
- Remove: Chat, Orquestador, Sesiones, and every Dashboard part tied to chat/engines.
- "Terminales" gets its own drawer entry (list of live PTYs + new; tap opens the terminal).
- "Entregas" gets its own drawer entry.
- Console ("Terminal CLI") is renamed "Procesos" and opens directly on the process list.
- Dashboard redesign: RAM, CPU, GPU usage; power draw (W); CPU/GPU/RAM temperature.

## Constraints / findings

- explore-1 map (2026-11-23): `Screen.CHAT` is the start screen and the universal Back
  target (`AppNavigation.kt:53,96-105`); Welcome/Connection continue to CHAT; Sessions only
  resumes chats; the only live-PTY list is `ConsoleViewModel.interactivePtys`.
- `/api/v1/chat/stream` and `cancelCurrentTask` are still consumed by Console; keep them.
- Server code for chat/orchestrator routes is NOT deleted in this feature (app-only removal).
- Sensors on the owner's PC: RTX 3060 via `nvidia-smi` (usage, temp, W, VRAM). Ryzen 5600X
  exposes no temperature/power through WMI (`MSAcpi_ThermalZoneTemperature` count 0) and no
  LibreHardwareMonitor is installed. DDR4 has no temperature sensor in general. Metrics are
  nullable; the UI shows "no disponible" with a hint instead of fake numbers. Optional
  source: LibreHardwareMonitor web server (`data.json`) when running.
- RTSS discovery (Engram `hardware/sensor-source`): RTSS's `LHMDataProvider.exe` loads
  LibreHardwareMonitorLib 0.9.5 and shares sensor XML via `LHMDPSharedMemory`. Probe: the
  DLL lists Ryzen `Core (Tctl/Tdie)` and `Package` power, but values are 0 without admin.
  Owner approved an elevated helper (2026-11-23). The elevated task must not run a
  user-writable script (privilege-escalation vector): install copies it to ProgramData.

## Tasks

- [x] T1 Remove Chat, Orchestrator and Sessions (screens, VMs, chat-only components, tests);
      new start/Back target; Welcome/Connection continue to Terminales.
- [x] T2 Drawer entries Terminales (new PTY list screen) and Entregas; Console renamed
      Procesos, opening directly on the process list (PTY launcher and Entregas card removed).
- [x] T3 Server `GET /api/v1/system/metrics` (behind auth): CPU %, RAM used/total, GPU via
      nvidia-smi, optional LibreHardwareMonitor source for CPU temp/W; nulls when unknown.
- [x] T4 Dashboard redesign on the metrics (polling while visible) + tests.
- [x] T6 Elevated sensor helper: loads LibreHardwareMonitorLib (same DLL RTSS's
      LHMDataProvider uses), writes CPU temp/power (+ DIMM temp if any) JSON every 2 s to
      `%ProgramData%\IDUPI\sensors.json`; installed as logon scheduled task with highest
      privileges from an admin-only copy in ProgramData; server metrics prefer it.
- [x] T5 Bump version, build, deliver with `idupi-push`, owner check.
- [ ] T7 Dashboard spacing: no overlapping/cramped text in the metric cards (owner feedback on 1.34).
- [ ] T8 Storage card (4th card): every fixed disk (C:, D:, ...) with used/free/total, swipeable
      pager between disks; server adds `disks` to `/api/v1/system/metrics`.
- [ ] T9 Settings: rename "Fondo chat"; app theme system (3 color presets, dark, light, custom accent
      + text colors) applied app-wide; remove auto-approval and other dead chat/orchestrator settings;
      reorganize sections.
- [ ] T10 Processes: list ALL PC processes (not only server-managed ones) with working category
      filters (Apps / Segundo plano / Windows / Agentes / IDUPI), memory and CPU %, search, and end
      process with confirmation (critical system processes protected); server endpoint.

## Acceptance criteria

- No Chat/Orquestador/Sesiones entries or code paths remain in the app; app builds, tests pass.
- App opens on Terminales; Back from any drawer screen returns to Terminales.
- Terminales lists every live PTY, creates new ones, opens one full-screen, Back returns.
- Procesos shows the process list immediately.
- Dashboard shows live CPU/RAM/GPU usage, GPU temp and W; unavailable sensors are labelled.

## Checks

- App: `./gradlew :app:testDebugUnitTest :app:assembleRelease` (TDD mode: off for server per
  `openspec/config.yaml:24`; app tests run as ordinary functional checks).
- Server: `node --test idupi-server/test/<file>`.

## Delivery

- Forecast: large deletion (~5000 lines removed, mostly whole files) + ~800 authored lines.
  Stacked PRs: PR-H = T1 (deletions), PR-I = T2, PR-J = T3+T4+T5.

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T1 | 4bbaed1, 4aaaf68 | Writer idu-pi work-1. 33 files, -8812/+50 (16 main + 13 test files deleted). Worker: testDebugUnitTest 250/0 + assembleDebug. Parent: leftover "Orquestador" labels reworded (4aaaf68). IduPiClient chat/orchestrator methods kept on purpose (wire tests; Console still uses connectChat/cancelCurrentTask). |
| T2 | dd44267 | Writer idu-pi work-1. TerminalsScreen/VM (3 s visible polling, create, open, Back -> Terminales), drawer order Terminales/Entregas/Procesos/Dashboard/Proyectos/Alertas/Remoto/Ajustes, Console -> "Procesos" list-first (log stream behind app-bar toggle). Worker 257/0 (7 new); parent rerun testDebugUnitTest EXIT 0. |
| T3 | 59f7695 | Writer idu-pi work-1. system-metrics 16/16, delivery-routes 10/10, node --check OK; wired after requireAuth (parent verified index.mjs:3168/3179). Real smoke: Ryzen 5600X 5.9 %, RAM 75 %, RTX 3060 39.7 C 13 W; CPU temp/W null + LibreHardwareMonitor hint. Live server not restarted (owner PTY alive). |
| T4 | 92ac9ec | Writer idu-pi work-1. DashboardViewModel 2 s visible polling, 2-col cards, thresholds, hints card. testDebugUnitTest 268/0 (11 new). Note: openspec app block says strict_tdd true; tests written alongside, no RED evidence (disclosed). |
| T6 | afb3677, b8465b5, 654997e, 2c26d41 | Writer idu-pi work-1 (helper, installer, server source, packaging). Parent: elevated path ignores user env (DLL hijack); zero readings -> null + driver hint. Root cause of 0 readings: RTSS's LHM copy (0.9.5-pre392) predates PawnIO. Packaged (owner: "others must use it without problems"): `scripts/sensors-deps.json` pins LHM v0.9.6 zip (sha256 086d9f1b...) and PawnIO 2.2.0 setup (sha256 1f519a22..., Authenticode namazso verified); installer installs PawnIO if missing, extracts 9 DLLs to admin-locked `%ProgramData%\IDUPI\lhm`; nothing binary in the repo; docs `idupi-server/docs/sensors.md`. Live (owner accepted UAC): PawnIO service RUNNING, helper elevated libVersion 0.9.6.0, CPU 60.1 C / 39.2 W; server /system/metrics 200 -> cpu 58.1 C 45 W, gpu 43 C 14.7 W, sources.sensors "sensors-helper", hints 0. system-metrics 22/22. RAM temp null (no DIMM sensor). |
| T5 | 0ad6b92 | Parent: testDebugUnitTest + assembleRelease EXIT 0, APK 1.34 versionCode 35 same cert, pushed via idupi-push (dlv-55b95ef97aed); owner installed and used the live dashboard (reported 404 before restart, then live RAM values). |

## Next step

T7-T10 (owner feedback 2026-11-23): server first (disks + processes endpoint), then app.
