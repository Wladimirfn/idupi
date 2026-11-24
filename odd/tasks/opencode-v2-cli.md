# OpenCode v2 CLI compatibility

Locator: `odd/tasks/opencode-v2-cli.md` · Engram mirror: `odd/opencode-v2-cli/tasks`
Branch: `fix/opencode-v2-cli` (from `main`)

## Objective

Make the bridge server work with OpenCode v2 (verified with v2.4.1): OpenCode session listing,
session counts, "latest session" lookup and per-provider model lists stopped working.

## Problem (verified 2026-11-23)

- `opencode db <sql> --format json` no longer exists in v2 (`db` subcommand removed) -> every
  `execOpenCodeDb` caller fails ("Unrecognized flag: --format"): `fetchOpenCodePage`,
  `countOpenCodeSessions` (index.mjs ~1509-1600) and the latest-session lookup (~5444, `execSync`
  string command).
- `opencode models <provider>` fails ("Unexpected positional argument"); v2 `opencode models` takes
  no provider and prints every `provider/model` line (716 lines here) (~4143).
- The legacy `session`/`message` tables in `~/.local/share/opencode/opencode.db` are frozen since
  2026-09-22; v2 writes `session_v2` / `session_message`.
- v2 offers `opencode session list [--max-count N] --format json` (top-level sessions of the
  current project, newest first; fields id, title, updated, created, projectId, directory),
  ~0.5 s per call.

## Design

- Listing/count/latest: `opencode session list --format json --max-count <N>` via execFile (no
  shell) with `cwd` = project directory; cursor pagination and paging applied in JS on the
  returned list (stable order: updated DESC, id DESC); short cache per directory.
- Message counts: optional, read-only `node:sqlite` (`DatabaseSync`, readOnly) on
  `session_message` grouped by session_id for the listed ids; if the DB/table/column is missing or
  locked, counts are null (never fabricated) and the noise filter (>2 messages) is skipped.
- Models: one cached `opencode models` call, filtered by `<provider>/` prefix.
- Keep response shapes of the existing routes unchanged.

## Tasks

- [x] T1 Sessions via `session list` (+ optional counts), latest-session lookup, models by prefix;
      tests with injected execFile/sqlite; update `sessions-runtime` and
      `opencode-model-passthrough` tests that assert the old `db` shape.
- [x] T2 Live check against the real OpenCode v2 + server restart (owner OK if PTYs are open).

## Checks

- Server TDD off (`openspec/config.yaml:24`); runner `node --test idupi-server/test/<file>`.
- Full suite: `node --test idupi-server/test/*.test.mjs` (screen-stream may flake under full-suite
  load; rerun alone).

## Delivery

- Single PR to `main` (forecast < 400 authored lines).

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T1 | 1120294 | Writer idu-pi work-1: new `lib/opencode-cli.mjs` (cached `session list --format json`, JS cursor paging, read-only node:sqlite counts with bound params, models prefix filter), `execOpenCodeDb` + SQL builders removed; 17 new tests; opencode-model-passthrough / sessions-runtime / session-noise updated (41/41). Parent: reviewed readOnly + bound params; full suite 543/543 excluding screen-*; screen-stream + screen-helper 24/24 alone (full-suite load flake). |
| T2 | 1120294 | Server restarted (no PTYs). Live: `/api/v1/orchestrator/providers/opencode-go/models` 200 -> 32 models; `/api/v1/sessions?engine=opencode` 200 with no errors (0 sessions: default "Proyecto Actual" path is the server cwd `idupi-server`, which has 0 OpenCode sessions vs 51 at the repo root — pre-existing registry behavior on main, not part of this fix). Worker smoke vs repo root: 42 filtered / 48 total, exact msgCount. |

## Next step

PR to main; owner decides merge. Follow-up (out of scope): default project path = server cwd (`idupi-server`) instead of the repo root.
