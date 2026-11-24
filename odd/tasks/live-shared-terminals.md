# Live shared terminals (phone <-> PC)

Locator: `odd/tasks/live-shared-terminals.md` · Engram mirror: `odd/live-shared-terminals/tasks`
Branch: `feat/live-shared-terminals` (stacked on `feat/interactive-terminal`)

## Objective

The same interactive terminal must be usable live from the phone and from the PC at the
same time, in both directions (what is typed on one side shows on the other), the way
MiniMax Code mirrors its sessions. The owner found the interactive terminal already covers
chat, SDD orchestrator and labs use cases; this feature makes it the primary surface.

## Problem

- PTYs (`lib/pty-manager.mjs`) already support many subscribers and a 256 KiB replay ring,
  but the only client is the Android app: there is no way to see or drive them on the PC.
- The server was started from a Claude Code background shell and died with that session
  ("Background shell command didn't finish"). It must run independently and survive reboots.

## Design

- **Server independence:** `idupi-server/scripts/start-server.ps1` starts `node index.mjs`
  detached (hidden window, logs in `idupi-server/logs/`, no-op when port 8788 already
  listens); `install-autostart.ps1` registers it to run at user logon. `logs/` gitignored.
- **Browser view (option A):** `GET /terminal/*` static page served before auth, loopback
  only (127.0.0.1 / ::1), whitelisted files, reusing the vendored xterm.js from
  `app/src/main/assets/terminal/vendor`. Tabs for every PTY, create, kill, live stream via
  `fetch` ReadableStream. Token passed in the URL fragment (`#token=...`, never sent to the
  server), kept in `sessionStorage`, sent as `Authorization: Bearer`.
- **Windows Terminal CLI (option B):** `idupi-term` (Node, zero deps): `ls`, `new`,
  `attach <id>`, `web` (opens the browser page with the token fragment). Raw stdin ->
  `/input`, stream -> stdout, `resize` on attach and on window resize, `Ctrl+]` detaches
  without killing.
- **Size policy:** one PTY has one size; the last client to resize wins (tmux "latest").
  Clients resize on attach and on focus.
- Owner compares A and B live and keeps the best.

## Tasks

- [x] T1 Server independence: start script, autostart at logon, gitignore logs.
- [x] T2 Browser view at `/terminal` (loopback-only static route + page + tests).
- [x] T3 `idupi-term` CLI for Windows Terminal (ls/new/attach/web + tests for pure parts).
- [ ] T4 Live test with the owner (phone + PC on the same PTY, both directions); decide A vs B.

## Acceptance criteria

- Typing on the phone shows on the PC view instantly and vice versa, on the same PTY.
- A PTY created on the PC appears in the phone's terminal list and can be attached.
- The server keeps running after Claude Code exits and starts after a reboot/logon.
- `/terminal` static files are refused from non-loopback addresses; every `/api/v1/pty`
  route still requires the bearer token.

## Checks

- TDD: off for idupi-server (source: `openspec/config.yaml:24`). Runner:
  `node --test idupi-server/test/*.test.mjs`; `node --check` on new scripts.

## Delivery

- Strategy: stacked PRs (cached). Forecast ~600 authored lines -> slices: PR-D = T1+T2,
  PR-E = T3.

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T1 | 8de0db4 (gitignore), b670923 | start-server.ps1 no-op when listening (observed), -Restart tree-killed node and relaunched (PID 10040, listening); both scripts parse with 0 errors; UTF-8 BOM fixes PS 5.1 accents; Startup shortcut installed and read back. |
| T2 | 83bff8f | Writer idu-pi work-1 (opencode/deepseek-v4.1-flash). Worker: 45/45 + 16/16 pipeline tests, headless smoke on ephemeral port. Parent: 24/24 rerun; live on :8788: /terminal/ 200, app.js 200, vendor 200, `/terminal/../index.mjs` 401 (normalized, falls to auth), /api/v1/pty 401, LAN 192.168.1.6 /terminal/ 403. ~1300 lines (page UI) -> size:exception note for PR-D. |
| T3 | db7a73e | Writer idu-pi work-1. Worker: 35/35 (term-cli 17 incl. 4 integration vs fake PTY API). Parent: 17/17 rerun; `idupi-term ls` against real server OK. `idupi-server/bin` added to user PATH. Note: CLI reads IDUPI_PORT, server reads PORT (default 8788 matches). |
| T4 (in progress) | f052663, a1c50a8 | Owner test: phone<->web live OK; PC claude window not mirrored (separate process, explained). Bug: no touch scroll on phone; request: horizontal pan. Writer idu-pi work-1: capture-phase touch -> synthetic wheel + inertia, wide mode 80 cols ("Ancho" key) with horizontal pan. Worker Chromium check at 412 px; parent: node --check, :app:testDebugUnitTest + assembleRelease OK, APK 1.31 versionCode 32 same cert. Pending owner retest. |

## Next step

T4: owner live test (phone + browser + idupi-term on one PTY), then keep the best PC view.
