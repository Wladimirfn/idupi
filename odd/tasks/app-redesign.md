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

## Tasks

- [ ] T1 Remove Chat, Orchestrator and Sessions (screens, VMs, chat-only components, tests);
      new start/Back target; Welcome/Connection continue to Terminales.
- [ ] T2 Drawer entries Terminales (new PTY list screen) and Entregas; Console renamed
      Procesos, opening directly on the process list (PTY launcher and Entregas card removed).
- [ ] T3 Server `GET /api/v1/system/metrics` (behind auth): CPU %, RAM used/total, GPU via
      nvidia-smi, optional LibreHardwareMonitor source for CPU temp/W; nulls when unknown.
- [ ] T4 Dashboard redesign on the metrics (polling while visible) + tests.
- [ ] T5 Bump version, build, deliver with `idupi-push`, owner check.

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

## Next step

T1 via idu-pi work-1.
