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
- [x] T5 — Project files: canonicalize with `realpath` (case-insensitive on win32); skip symlinks/junctions in tree walk.
- [x] T6 — Add `error` handlers on spawned children and their stdin (pi, claude, opencode, terminals); guard ScreenHelper stdout decode.
- [x] T7 — 1 MB body cap for POST routes (enforced from headers in `guardedRequest`; see rationale).
- [x] T8 — Cross-audit of the phase diff by an idu-pi audit slot; fix confirmed findings.

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
| T5 | `172a36e` | `project-file-guard.test.mjs` RED→GREEN (8/8) with a real NTFS junction + cycle | RDD off |
| T6 | `11e4889` | `child-guards.test.mjs` (real EPIPE kills node without guard — verified) 3/3; `screen-helper.test.mjs` 2 new RED→GREEN | RDD off |
| T7 | `2687da8` | `request-guard.test.mjs` 2 new RED→GREEN (6/6) | RDD off |
| all | — | full suite 431/432 (only the known env failure); live server smoke on :18799: status 200, no-auth 401, 2 MB → 413, chunked → 411, profile traversal save/delete/apply → 400, `__proto__` → 404, file-content `../../.ssh/id_rsa` → 403 | — |

Rationale notes:
- T2: `Object.hasOwn` on presets because `__proto__` passes the id allowlist.
- T3: also fixed a pre-existing bypass — `C:/Windows/...` (forward slashes) was not protected.
- T4: `--` end-of-options is required; without it Claude 2.1.330 rejects a prompt starting with `-`
  (`unknown option`). The `cmd.exe /c <shim>` fallback was removed (cmd re-parses argv); an
  unparseable shim now falls through to the existing CLAUDE_NOT_INSTALLED error.

- T6: Claude and OpenCode already had `child.on("error")`; the audit overstated that part. Added
  stream guards to all children. The OpenCode sidecar only listens `once("error")` during startup —
  deferred to the phase 2 sidecar rewrite.
- T7: a per-route `readJsonBody` was rejected: `handleRequest` awaits `handleScreenRoute` before the
  other routes attach readers, so a global `data` listener would drop bytes. Limits are enforced
  from `Content-Length` (413) and chunked-without-length (411); the Android client (Ktor
  `TextContent`) always sends Content-Length. Auth runs first, so this was authenticated-only DoS.
- Deferred (out of phase 1 scope): `recentActivity` key eviction (low), sidecar error listener (phase 2).

### T8 — cross-audit (idu-pi `audit-5`, pi + kimi-k3.1-pro)
| # | Finding | Verdict | Action |
|---|---|---|---|
| 1 | Other users' trees deletable below level 1 | confirmed | fixed `d75616c` |
| 2 | Junctioned home loses protections | confirmed | fixed `d75616c` |
| 3 | System roots hardcoded to `C:` | confirmed | fixed `d75616c` (env-derived roots) |
| 4 | `//?/` forward-slash namespace paths | plausible | fixed `d75616c` (fail closed) |
| 5 | Protected delete still reports ok + unregisters | confirmed, pre-existing contract | **phase 3** (app/server contract) |
| 6 | Stale helper `close` rejects replacement's requests | confirmed (RED test), introduced by T6 | fixed `781573e` |
| 7 | Stdout uses shared `this.decode` | confirmed, pre-existing | fixed `781573e` |
| 8 | realpath check/use TOCTOU via junction swap | suspected; needs local write access | deferred |
| 9 | UNC projects undeletable | by design (fail closed) | kept |
| 10 | Direct children of home undeletable | by design | kept — user informed |
| 11 | POSIX `/var` `/opt` blanket | server is Windows-only | not applicable |
| 12 | Regex `$` accepts trailing newline | **false** — verified `/^…$/.test("strong\n") === false` in JS | rejected |
| 13 | Windows-path tests fail on POSIX | server is Windows-only; pre-existing style | not applicable |

Evidence after T8: full suite 436/437 (only the known env failure); idu-pi postflight passed.
Diff `e37def6..HEAD`: 18 files, +806/−113 (about half tests). Above the ~400-line heuristic:
delivery strategy `ask-on-risk` → chain/PR strategy to be chosen by the user.

## Next step
User decision on delivery (merge/PR of `chore/gitignore-cleanup` + `fix/phase1-security-crashes`),
then phase 2 (engines: OpenCode v2 rework, Claude catalog, gentle-shell).
