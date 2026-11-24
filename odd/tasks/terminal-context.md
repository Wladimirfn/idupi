# Terminal context: project, branch and running agent

Locator: `odd/tasks/terminal-context.md` · Engram mirror: `odd/terminal-context/tasks`
Branch: `feat/terminal-context` (from `main`)

## Objective

Each interactive terminal (PTY) must show WHERE it is and WHAT runs in it: project name, git
branch and the running agent (Claude Code, OpenCode, Codex, ...), instead of the static launch
data (`powershell.exe -NoLogo`, `C:\Users\elmas`) that makes two terminals indistinguishable.

## Problem (verified 2026-11-24)

- `PtyManager.#info` reports the launch `cwd`, `shell` and the host `pid`
  (`idupi-screen.exe pty`); after `cd` + `claude`/`opencode` nothing changes.
- Owner example: two PTYs both shown as `powershell.exe -NoLogo · C:\Users\elmas`, one running
  OpenCode and one Claude Code in different projects.

## Design

- **Live cwd (Go helper):** new subcommand `idupi-screen.exe proc-cwd <pid>...` prints JSON
  `{ "<pid>": "<cwd>" | null }` read from the process PEB
  (NtQueryInformationProcess -> RTL_USER_PROCESS_PARAMETERS.CurrentDirectory via
  ReadProcessMemory). Same-user processes need no elevation; WOW64/unreadable -> null.
  The helper exe is built locally (`go build`, gitignored).
- **Process tree + agent detection (server):** one process sample (name, pid, parent, command
  line) -> descendants of each PTY host pid; agent = deepest descendant matching the known agent
  CLIs (claude, opencode, codex, cmdc, mcode, kimi, qwen, gemini, pi; node-based ones recognised
  by command line). Context process = agent if any, else the shell.
- **Project + branch:** `git -C <cwd> rev-parse --show-toplevel --abbrev-ref HEAD` via execFile;
  project = top-level folder name (or cwd folder name outside git); detached HEAD -> short sha.
- **API:** `GET /api/v1/pty` items gain `context: { cwd, project, branch, agent: { name, pid } | null }`,
  cached ~3 s and computed only while PTYs exist. Existing fields unchanged.
- **App:** Terminales cards show project (title), branch chip, agent badge, live cwd; the
  terminal screen header shows `project · branch · agent` instead of the PTY id.

## Tasks

- [ ] T1 Go `proc-cwd` subcommand + Go test (own process cwd round-trip).
- [ ] T2 Server PTY context (process tree, agent detection, cwd via helper, git project/branch,
      cache) + tests with injected sampler/execFile.
- [ ] T3 App: model + Terminales cards + terminal header; tests.
- [ ] T4 Build APK, deliver, PR.

## Checks

- Go: `go test ./...` in `idupi-server/screen-helper`; `go build -o idupi-screen.exe .`.
- Server TDD off (`openspec/config.yaml:24`); `node --test idupi-server/test/<file>`.
- App: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

## Delivery

- Single PR to `main` unless the running count clearly exceeds ~400 authored lines (then slice
  server vs app).

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|

## Next step

T1+T2 via idu-pi work-1.
