# Interactive terminal (ConPTY + xterm.js)

Locator: `odd/tasks/interactive-terminal.md` · Engram mirror: `odd/interactive-terminal/tasks`
Branch: `feat/interactive-terminal` (stacked on `phase1/04-crash-guards-body-limit`)

## Objective

Give the phone a real, fully interactive terminal on the PC: TUIs such as `gentle-ai`
(Bubbletea) and `idu-pi` must render and accept keystrokes, and stopping a terminal must
kill its whole process tree. If the result is clear and usable, it may replace the planned
idu-pi orchestrator screen (owner decision after testing).

## Problem

- Terminals are spawned as `powershell -Command -` with `shell: true` behind a `cmd.exe`
  wrapper and plain pipes: no console, so TUIs cannot render (`index.mjs:1004`).
- `closeTerminal` only signals the `cmd.exe` wrapper; the PowerShell child survives and the
  route always answers ok (`index.mjs:1136`, route `:3387`). Observed: PIDs 30776, 33080,
  19128 each kept a live PowerShell child (killed manually with `taskkill /T /F`).
- `execCommand` without a terminal id silently routes the command into the Pi RPC chat
  (`index.mjs:1061-1068`).
- App renders ANSI-stripped log lines polled every 1.5 s (`ConsoleViewModel.kt:60`).

## Design

- **PTY host (Go, zero deps):** new `pty` mode in `idupi-server/screen-helper`
  (`idupi-screen.exe pty --cols C --rows R --cwd DIR -- <cmd> [args]`). Uses kernel32
  `CreatePseudoConsole`/`ResizePseudoConsole`/`ClosePseudoConsole` via `syscall.NewLazyDLL`
  (same style as `win32.go`). Child runs inside a Job Object with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, so killing the host kills the tree.
  - stdin: frames `u32be len || kind(1) || body`; kind `I` = input bytes, `R` = resize JSON
    `{"cols":C,"rows":R}`.
  - stdout: raw VT output bytes from the pseudo console.
  - Exit: when the child exits the host prints `{"exit":code}` on stderr and exits.
- **Node (zero deps):** `lib/pty-manager.mjs` (one host per terminal, 256 KiB raw ring buffer
  for replay, subscribers, input/resize, tree kill with real result) + `lib/pty-routes.mjs`
  behind the existing single auth choke point:
  - `POST /api/v1/pty` `{cols,rows,cwd?}` → `{id,pid}`
  - `GET /api/v1/pty` → list
  - `GET /api/v1/pty/{id}/stream` → chunked `application/octet-stream`: replay buffer, then live
  - `POST /api/v1/pty/{id}/input` `{data}` (UTF-8 string)
  - `POST /api/v1/pty/{id}/resize` `{cols,rows}`
  - `DELETE /api/v1/pty/{id}` → tree kill, 404 when unknown
  - No WebSocket: follows the existing chunked-stream + POST pattern (`screen-routes.mjs`).
- **App:** xterm.js (vendored, offline) in a WebView from `app/src/main/assets/terminal/`;
  Kotlin owns the authenticated HTTP stream and pushes bytes into JS (token never reaches
  JS). JS `onData` → Kotlin → batched POST input. Compose extra-keys bar: Esc, Tab, Ctrl,
  Alt, arrows, PgUp/PgDn.

## Tasks

- [x] T1 Go ConPTY host mode (`pty_windows.go`, frame parser + tests, `main.go` dispatch).
- [x] T2 Node PtyManager + routes + tests; legacy `closeTerminal` tree-kill with honest
      result; `exec` without a terminal id no longer routes into Pi chat.
- [x] T3 App: vendored xterm.js assets, `PtyTerminalView`, client methods, extra-keys bar,
      Console screen integration.
- [ ] T4 Version bump, signed APK, live test with the owner (gentle-ai, idu-pi, stop).

## Acceptance criteria

- `gentle-ai` TUI renders and is navigable from the phone; `idu-pi status` prints in color.
- Stopping a terminal leaves no orphan process (verified by PID check).
- Reconnecting to a terminal replays recent output.
- Auth required on every `/api/v1/pty` route (401 without token).

## Checks

- TDD: off for idupi-server (source: `openspec/config.yaml:24`). Runner:
  `node --test idupi-server/test/*.test.mjs`; Go: `gofmt -l . && go vet ./... && go test ./...`
  in `idupi-server/screen-helper`; app: `gradlew :app:testDebugUnitTest :app:assembleDebug`.

## Delivery

- Strategy: stacked PRs (cached from phase 1). Forecast > 400 authored lines (vendored
  xterm.js excluded). Planned slices: PR-A = T1, PR-B = T2, PR-C = T3+T4.

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T1 | 5a30171 | gofmt clean (new files), go vet no new output, go test ok (-count=20 Pty stable); smoke: echo/exit codes, taskkill host kills powershell child. Audit audit-5 (pi/kimi-k3.1-pro): no blockers; fixed #2 (fail() closes pipes before ClosePseudoConsole) and #5 (startup-error test bounded); #1/#3/#4 refuted (stdin EOF is the parent-death watchdog; Read 0 = broken pipe). Note for T2: chunk input frames well under 1 MiB. |
| T2 | 26a9c1c | Writer idu-pi work-1 (opencode/deepseek-v4.1-flash). pty tests 31/31 + request-guard/fs-imports/screen-routes-auth = 40/40; node --check ok; full suite per-file: all pass except sessions-runtime (local-env hang + known OpenCode failure; passes in a clean worktree with T2 code). Live smoke on :8789: 401 without token, 201 create, PS prompt + ANSI, input evaluated (PTY-42), DELETE kills host + 3 children, second DELETE 404. Audit audit-1 (agy/gemini-4-argon-high): no blockers; fixed cwd resolve + UNC reject, LIMIT TOCTOU, startup error visibility (300 ms settle), stream backpressure cap 1 MiB, subscribe race, sync host kill on exit. Parent fix: legacy killProcessTree killed cmd.exe before taskkill could walk the tree (re-orphaning PowerShell) -> direct kill now runs in the taskkill callback. |
| T3 | (this commit) | Writer idu-pi work-1 (opencode/deepseek-v4.1-flash). gradlew :app:testDebugUnitTest BUILD SUCCESSFUL (352 tests, 26 new); :app:assembleDebug BUILD SUCCESSFUL with assets packaged; node --check terminal.js ok. Parent review: WebView JS on, file/content/domStorage off, navigation blocked, token stays in Kotlin. Live WebView proof deferred to T4. |

## Next step

T4: signing config, version bump, signed APK, live test with the owner.
