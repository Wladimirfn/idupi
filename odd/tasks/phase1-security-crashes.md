# Phase 1 — Security and crash fixes (idupi-server)

## Objective
Close the confirmed security holes and process-crash paths found by the 2026-11-22 cross-audit
(idu-pi audit-1 / audit-3, orchestrator spot-verified) before updating engines (phase 2),
the app/server contract (phase 3) and the idu-pi integration (phase 4).

## Problem / why
The server is reachable from the phone over the LAN/tunnel. Several routes trust client-supplied
ids/paths, and several spawned children have no `error` handlers, so one bad request or a missing
CLI binary can delete files or take the whole server down.

## Scope
- `idupi-server/index.mjs`, `idupi-server/lib/**` only.
- Out of scope: OpenCode v2 rework, model catalogs, Android changes (later phases).

## Constraints
- Branch `fix/phase1-security-crashes` (stacked on `chore/gitignore-cleanup`).
- One work-unit commit per task, Conventional Commits, tests alongside behavior.
- TDD: **off** for idupi-server (source: `openspec/config.yaml:24` `strict_tdd: false`).
  Runner: `node --test idupi-server/test/*.test.mjs`. New tests are still added per fix.
- Known environmental failure on base: `delayed OpenCode sessions query does not stall an active
  SSE event flow` (OpenCode v2.3.2 removed `opencode db --format`; fixed in phase 2).
- Delivery strategy: `ask-on-risk`. Forecast ~300-400 authored lines.

## Tasks
- [x] T1 — Import `mkdirSync`/`unlinkSync` in `index.mjs` (profiles save/delete ReferenceError).
- [x] T2 — Validate `profileId` (safe id + containment) in profiles save/delete/apply (path traversal).
- [x] T3 — Harden `projects/remove` `deleteFiles`: protect server cwd + ancestors and user top-level/dot dirs.
- [x] T4 — Claude argv: pass raw prompt to `-p` (no `JSON.stringify`); refuse `cmd.exe /c` fallback with untrusted args.
- [ ] T5 — Project files: canonicalize with `realpath` (case-insensitive on win32); skip symlinks/junctions in tree walk.
- [ ] T6 — Add `error` handlers on spawned children and their stdin (pi, claude, opencode, terminals); guard ScreenHelper stdout decode.
- [ ] T7 — Shared `readJsonBody` with a 1 MB cap for POST routes.

## Acceptance criteria
- Each fix has a test that fails on the old behavior or exercises the new guard.
- Full suite: no new failures beyond the known environmental one.
- idu-pi postflight matches expected files; cross-audit of the final diff by an idu-pi audit slot.

## Progress / evidence
| Task | Commit | Checks | Review tier |
|---|---|---|---|
| — | — | baseline 409/410 (1 known env failure) | — |
| T1 | `8dd876c` | new `fs-imports.test.mjs` RED→GREEN; module-scope green | RDD off (clone-local) |
| T2 | `11d2de1` | new `profile-path.test.mjs` RED→GREEN; orchestrator-* 54/54 | RDD off |
| T3 | `54fab55` | `system-paths.test.mjs` 6 new RED→GREEN (11/11); real-guard sanity on host paths | RDD off |
| T4 | `ccfa07d` | `agent-cmdline.test.mjs` RED→GREEN (16/16); e2e spawn of claude.exe 2.1.330 with `-p -- "-x…\n…"` → `PONG` | RDD off |

Rationale notes:
- T2: `Object.hasOwn` on presets because `__proto__` passes the id allowlist.
- T3: also fixed a pre-existing bypass — `C:/Windows/...` (forward slashes) was not protected.
- T4: `--` end-of-options is required; without it Claude 2.1.330 rejects a prompt starting with `-`
  (`unknown option`). The `cmd.exe /c <shim>` fallback was removed (cmd re-parses argv); an
  unparseable shim now falls through to the existing CLAUDE_NOT_INSTALLED error.

## Next step
T5.
