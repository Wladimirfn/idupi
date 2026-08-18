# Exploration — `session-listing-accuracy-perf`

Feature under investigation: `GET /api/v1/sessions` (session-history listing across the
three CLI engines) and the Android screen that consumes it.

Status: complete. Ready for proposal.

## Reported symptoms

1. Opening the Sessions screen takes roughly **4 minutes**.
2. For project `Sistema_de_mantencion`, Claude CLI has ~8 sessions on disk but the app
   displays only **2**.
3. Server logs show per-project totals of 1170 and 1268 sessions, which looked
   implausibly high and suggested cross-project contamination.
4. The same `[Sessions DB] Cargadas ...` log line repeats 3–4 times per screen open.

## Code under investigation

| Location | Role |
| --- | --- |
| `idupi-server/index.mjs:833-1047` | `normalizePathForCompare`, `getProjectSessions` (all three engine branches, sort, slice) |
| `idupi-server/index.mjs:1856-1868` | `GET /api/v1/sessions` handler; default `limit` is **150** |
| `app/.../viewmodel/SessionsViewModel.kt:34-35` | `init { refreshSessions() }` |
| `app/.../ui/screens/SessionsScreen.kt:47-48` | `LaunchedEffect(Unit) { viewModel.refreshSessions() }` |

## Ground truth measured on the developer machine

All counts below are real, read-only measurements — not estimates.

| Engine | Sessions for `Sistema_de_mantencion` | How measured |
| --- | --- | --- |
| OpenCode | 1086 | `opencode db "SELECT directory, COUNT(*) ... GROUP BY directory"` |
| Pi CLI | 75 | top-level `.jsonl` files in its session subdirectory |
| Claude CLI | 9 | top-level `.jsonl` files in its project directory |
| **Total** | **1170** | matches the server log line exactly |

**Conclusion: symptom 3 is not a defect.** The 1170 total is genuine. The recently
introduced exact-path matching is correct and is already excluding the sibling
`Sistema_de_mantencion-worktrees/*` directories, which the previous substring
(`includes()`) matching did incorrectly absorb. Contamination existed and is fixed.

Two contributing factors make large genuine totals normal here:

- OpenCode accumulates a very high session count per project (1086 in this case).
- Pi CLI creates a **new top-level session file per orchestrator/subagent hop**, chained
  through a `parentSession` field, so one logical conversation can produce many files.

## Root cause of the 8 -> 2 undercount (CONFIRMED)

The originally suspected cause — Claude persisting `message.content` as an array of
content blocks while the parser only handles `typeof content === "string"` — was
**refuted**. Sampled real session files on this machine store `message.content` as a
plain string, and no code path drops a whole session for array-shaped content (it would
only degrade the title/preview).

The actual mechanism is the interaction between cross-engine merging and the final slice:

1. All three engines are scanned and merged into one `allSessions` array.
2. `allSessions.sort((a, b) => b.rawTimestamp - a.rawTimestamp)` sorts by recency across
   engines, so engines compete for the same slots.
3. `.slice(offset, offset + limit)` is applied last, with `limit` defaulting to 150.

Measured consequence for `Sistema_de_mantencion`:

- Claude's 2 newest sessions have timestamps `1786803598000` and `1786803584000`.
- **0** OpenCode sessions are newer than Claude's newest, so those 2 survive the cut.
- **183** OpenCode sessions are newer than Claude's 3rd-newest (`1786073971000`), and
  Pi contributes more on top of that.
- The 150 available slots are therefore exhausted before Claude's remaining 7 sessions
  are reached.

Result: exactly **2** Claude sessions are returned. This reproduces the reported symptom
precisely.

**Design consequence:** the accuracy defect and the performance defect share a single
root — pagination is applied at the wrong place, after a global cross-engine merge.
A correct fix must bound work and allocate result slots **per engine**, not globally.
Fixing pagination properly fixes both symptoms; fixing only the scan speed would leave
the undercount in place.

## Performance root cluster (all CONFIRMED)

| ID | Finding | Evidence |
| --- | --- | --- |
| H1 | Rejecting a non-matching session still costs a **full file read**: `readFileSync(file, "utf8")` loads the entire `.jsonl` just so line 1 can be parsed for `cwd`. | Code trace of the Pi branch |
| H2 | Pagination is **not real**: `limit`/`offset` are applied via `.slice()` only after the complete scan, sort, and parse. Requesting 20 rows costs the same as requesting 1170. | `index.mjs:1046` |
| H3 | **No caching exists.** `sessionsCacheMap` is declared at `index.mjs:831` and never read or written anywhere else in the file — confirmed dead code. | Repo-wide search returns exactly one occurrence |
| H6 | OpenCode is queried with `execSync(..., {timeout: 4000})`, which **blocks Node's single-threaded event loop** for up to 4s per request, stalling every other request — including the chat SSE stream. | Code + Node execution semantics |

With >1000 sessions per project, H1 + H2 + H3 together mean every screen open re-reads
hundreds of megabytes from disk and fully parses it, only to discard most of the result.

## Accuracy / robustness findings

- **Pi CLI fallback match is dead code.** When a session record carries no `cwd`, the
  fallback compares a normalized directory name against `normFull`. Pi's real directory
  names carry a literal `--` prefix and suffix (verified:
  `--C--Users-dev-OneDrive-Escritorio-Mis proyectos-Sistema_de_mantencion--`), which
  normalization preserves, while `normFull` has no such wrapper. The two strings can
  never be equal. This is currently masked because sampled session files do record
  `cwd`.
- **Silent failure surfaces.** The Claude and OpenCode branches wrap their whole scan in
  bare `catch {}` / `catch (e) {}` blocks with no logging, so a systematically failing
  engine is indistinguishable from "this project has no sessions". The Pi branch does
  log its scan errors, so the three engines are inconsistent. Numerous per-file
  `catch (e) {}` blocks have the same effect at row granularity.

## Duplicate-request root cause (CONFIRMED)

Two independent trigger sites both call `refreshSessions()` on first entry to the
screen: `SessionsViewModel`'s `init` block and `SessionsScreen`'s `LaunchedEffect(Unit)`.
At least 2 requests per screen open are structurally guaranteed. The ViewModel is
correctly scoped once in `AppNavigation.kt`, so this is a duplicated-trigger problem,
not a ViewModel-recreation problem. Additional observed repeats are most plausibly
route re-entry rather than a third call site; no third call site exists under
`app/src/main/java`.

Because each request is so expensive, this multiplies the worst problem by 2x or more.

## Approaches considered

### Performance and pagination

1. **Per-engine bounded scan + header-only reads + real pagination at the source, with
   an mtime-aware in-memory index.** Stop reading whole files to make a match decision;
   read only what is needed for the match and the preview. Bound and allocate work per
   engine so no engine can crowd out another. Cache keyed on directory/file mtime.
   *Pros:* closes H1, H2, H3 and the 8→2 undercount at their shared root; no new
   dependencies; no new persisted state. *Cons:* requires cache-invalidation logic.
   *Effort:* medium.
2. **Make the OpenCode query non-blocking** (`spawn`/async `exec` instead of
   `execSync`). *Pros:* directly closes H6; small and isolated; also relieves the chat
   SSE stream. *Cons:* does nothing for H1–H3. *Effort:* low.
3. **Persisted index (`node:sqlite` or a flat JSON index file).** *Pros:* best long-term
   scaling. *Cons:* introduces a new persisted-state class and a staleness contract for
   a problem that approaches 1 + 2 already solve at current scale. *Effort:* high.

**Recommended:** approaches 1 and 2 together. Approach 3 is not justified yet and would
grow the system rather than shrink it.

### Accuracy and robustness

1. Keep the exact-path matching (verified correct); repair or delete the dead Pi
   fallback rather than leaving an unreachable branch.
2. Add structured logging to the silent Claude and OpenCode catches so a failing engine
   reports failure instead of emptiness.
3. **Product decision, explicitly out of scope for a bug fix:** whether Pi's
   `parentSession`-chained per-hop session files should be grouped or filtered so one
   logical conversation appears once. This changes what the user sees and needs its own
   decision; it must not be smuggled into a correctness fix.

### Client

Collapse the duplicate trigger to a single source of truth for the initial load, keeping
the explicit manual-refresh button intact.

## Testability constraint

`idupi-server` has **no test runner and no `package.json`** (zero-dependency by design);
only `node --check` syntax validation exists today. `scratch/test-*.mjs` are manual
smoke scripts with no assertions. None of the fixes above are meaningfully verifiable
under the current setup.

Recommendation: introduce a `node:test` suite in the same change. `node:test` is part of
the Node standard library, so it adds zero dependencies and preserves the project's
design constraint. The matching, per-engine allocation, and pagination logic are pure
enough to test against small fixture directories.

Android-side changes remain covered by the existing verified suite
(`./gradlew :app:testDebugUnitTest`, 83 tests).

## Risks carried into proposal

- The Pi per-hop session-file behavior means "session count" is not a stable product
  concept across engines; the grouping question needs an explicit decision.
- Cache invalidation based on mtime can miss in-place edits that do not update mtime;
  the invalidation contract must be stated, not assumed.
- The repository currently has **zero git commits**; all work is untracked, so there is
  no baseline to diff against or revert to.
