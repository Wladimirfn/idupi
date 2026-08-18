# Design: Session Listing Accuracy and Performance Fix

## Round 2 Traceability — Fixes Applied After the Second Fresh-Context Validation

The second validation (`fail`) confirmed the architecture needs no redesign —
6 of 10 round-1 findings were genuinely resolved — but found 6 new blocking
defects in the rework itself. Applied directly, not re-delegated, since each
had the exact fix already specified in the validator's report:

| # | Finding | Fix applied |
|---|---|---|
| 1 | `"done"` exhaustion rule reintroduced the 9→2 bug: a short fetch alone marked an engine exhausted, permanently dropping any of its items crowded out of the page | Rewrote as an ordered 4-case rule (§ Combined-page algorithm, step 5): exhausted requires "fewer than `limit` **and** every fetched item emitted"; a worked 9-Claude-vs-183-newer-OpenCode counterexample is now in the doc as a regression guard |
| 2 | `{id, timestamp}` index can't resolve a Pi file path (`meta.id` ≠ filename basename in 1106/1106 measured files) | Added `filePath` to the cached index record; documented why Pi specifically needs it |
| 3 | `nextCursor` had two incompatible wire types (opaque string vs. raw JSON object) across the document | Every occurrence now shows the base64url opaque string; the decoded per-engine object is documented as server-internal only |
| 4 | `/counts` had three incompatible shapes across the document | Consolidated into one shape with its own `partial`/`failures` fields; failed engines are omitted from `counts`, never reported as `0` |
| 5 | `SessionCounts(piCli: Int, ...)` can't deserialize a `"pi-cli"` JSON key under default `kotlinx.serialization` | Added `@SerialName("pi-cli")`; all count fields made nullable so an omitted (failed) engine is representable |
| 6 | "measured 50 ms for 1106 OpenCode-scale files" traced to no real measurement, and conflated a filesystem scan with a SQL aggregate | Replaced with an actual benchmark run on this machine: full-scan baseline 46 392 ms vs. this design's cold-path worst case 527 ms (12 ms enumerate + 40 ms stat + 475 ms Pi 8 KB reads) — an 88x reduction, now cited wherever the deviation from strict per-page-bounded work is justified |

Also cleaned up while in the file: the false "`;` never appears in a Windows
path" premise (now stated as a deliberate over-rejection with a diagnosable
502, not a factual claim); the dead-code `SessionCard.kt` duplicate that would
have rendered `"null mensajes"` if ever wired up; the missing `node --test`
run command.

## Round 3 Traceability

The third validation passed 5 of the round-2 fixes outright and found the
round-2 exhaustion-rule rewrite still had a gap: cases 2–4 were not
collectively exhaustive. The state `n == limit && m == n` (a full fetch,
fully emitted — the ordinary outcome of single-engine tab pagination, and a
recurring one in `engine=all` as engines sequentially reach `"done"`) matched
none of them, leaving no defined action. Fixed by broadening rule 3's
condition from "at least one emitted AND some left unconsumed" to simply
"at least one emitted" (`m ≥ 1`), evaluated after the stricter rule 2
(`n < limit && m == n`) — this makes the four rules collectively exhaustive
over every `(n, m)` pair, which the design now states and proves inline. See
the "Combined-page algorithm," step 5.

## Technical Approach

Product decision: **per-engine, on demand**. `GET /api/v1/sessions` splits into
independent per-engine cursor pages (`engine=pi-cli|opencode|claude`) plus one
chronological k-way merge for `engine=all`, with true counts served by a
separate `GET /api/v1/sessions/counts`. This removes the shared root cause —
global merge-then-slice — behind both the 9→2 Claude undercount and the
~4-minute load: every engine gets bounded work and its own result slots,
never competing for another engine's slots.

Every ordering key is now obtained without reading a session file's body
(mtime for Claude, an already-bounded 8 KB head read for Pi, a SQL column for
OpenCode). Cursor pagination (never `offset`) uses a `(timestamp, id)`
tiebreaker and tracks the last **consumed**, not last fetched, item per
engine, so the no-gaps/no-duplicates guarantee is provable rather than
asserted (see "Cursor & Pagination Contract").

## Architecture Decisions

| # | Decision | Alternatives considered | Rationale |
|---|---|---|---|
| 1 | Split `GET /api/v1/sessions` into per-engine cursor pages and an `engine=all` k-way merge; new `GET /api/v1/sessions/counts` for true totals | Global merge-then-slice (status quo — root cause); client-side filter of one payload (defeats "on demand") | Matches product decision; bounds work to page size |
| 2 | Claude: order/paginate by `statSync(file).mtimeMs` (filesystem metadata, zero content bytes read); match by directory-name encoding only (already zero-content) | Order by the file's last-entry timestamp (status quo — requires reading to EOF; violates the amended spec's "Ordering Key Obtainable Without Reading File Bodies") | Satisfies the new ordering-key requirement exactly; mtime is already read once per file for other purposes, no new I/O class |
| 3 | Pi: repair the `--wrapper`-stripping bug, then use directory-name match as a **necessary** readdir-only pre-filter; per-file `cwd` (from the existing 8 KB bounded head read) remains the **sufficient** match/order authority when present | Scan every Pi session file globally (status quo); drop the pre-filter entirely | Fact 6 (100% of 1106 files: name encodes `cwd`) justifies pruning by name before opening any file; `cwd` still decides individual membership, so behavior narrows, it does not change |
| 4 | Content hydration (title/preview) happens **only for the page being returned**, via a bounded head+tail read, decoupled from the match/order decision | Read full file to build title/preview for every scanned candidate (status quo — H1) | Removes "reject/order costs a full read"; hydration cost is O(page size), not O(candidates) |
| 5 | OpenCode: resolve `opencode.cmd`'s real target once (verified below — a native `opencode-ai` binary, not JS), cache the path, call it with `execFile(resolvedExePath, argv, {timeout:4000})` | `execFile("opencode", …)` / `execFile(".../opencode.cmd", …)` (status quo attempt — measured `ENOENT`/`EINVAL`, fact 1); `exec(cmd, {shell:true})` (works, but reopens a shell-metacharacter surface for no added benefit) | `execFile` is already async (not blocking); the prior failure was invoking the wrong target (a `.cmd` shim, subject to Node's Windows batch-file hardening), not a limitation of `execFile` itself |
| 6 | OpenCode SQL: push `directory` filter and the cursor predicate into `WHERE`; drop `GROUP BY` over the whole table in favor of a correlated per-row `COUNT` subquery scoped by the same `WHERE` | Fetch all 1086+ rows across all projects then filter/slice in JS (status quo — undetected extra defect: today's query has **no** `directory` filter at all) | SQLite does the filtering; JS never sees rows outside the requested page |
| 7 | Cursor pagination with `(timestamp, id)` tiebreaker; combined cursor is a per-engine object keyed by the frozen wire values (`pi-cli`/`opencode`/`claude`), encoding the last **consumed** item (or an explicit `"done"` sentinel) per engine | `offset` (rejected by the amended spec — unstable under concurrent writes); a single global watermark across engines (loses per-engine "last consumed" precision required when an engine is crowded out of a page) | Provable no-gaps/no-duplicates (see dedicated section); `"done"` sentinel avoids re-querying an exhausted engine forever |
| 8 | `messageCount` is `Int?` on the wire and in the model: populated (exact) for OpenCode via SQL aggregation, `null` for Pi/Claude in the **list**; the session-detail view (already a full read) may still show an exact count | Show a count derived from the bounded window (fabricated/misleading — rejected by the amended "No Fabricated Session Metadata" requirement); always omit for all engines (throws away a value that is genuinely free for OpenCode) | Matches the orchestrator decision encoded in the amended spec exactly |
| 9 | In-memory per-`(projectPath, engine)` **lightweight index** cache: `{id, timestamp, filePath}` records only, not content; retained for up to **15 seconds** (in-memory TTL), then dropped and rebuilt. The cached record carries `filePath` so page-scoped hydration never re-scans directories | No cache (status quo, dead `sessionsCacheMap`); a cache of full hydrated content (bloats memory, goes stale faster); `{id, timestamp}` without `filePath` (an earlier version of this design — unimplementable for Pi, whose `meta.id` never equals its filename basename, measured 0/1106) | Building the index is O(that engine's matching file count) but each unit is bounded (a file stat for Claude, an 8 KB head read for Pi); storing the id/timestamp/path index alongside building it is near-free, and the TTL keeps warm requests at O(page size) between rebuilds — exactly the data pagination, hydration, and counts all need |

## OpenCode Invocation — Verified Resolution

**Fact 1 said `execFile("opencode", …)` fails.** Design-time verification (this
session, reading the actual files) explains *why* and *what to target
instead*:

`C:\Users\dev\AppData\Roaming\npm\opencode.cmd` (verbatim, read directly):
```
@ECHO off
GOTO start
:find_dp0
SET dp0=%~dp0
EXIT /b
:start
SETLOCAL
CALL :find_dp0
"%dp0%\node_modules\opencode-ai\bin\opencode.exe"   %*
```

This is a plain npm-generated Windows batch shim. Its real target —
confirmed to exist via a filesystem glob — is:

```
C:\Users\dev\AppData\Roaming\npm\node_modules\opencode-ai\bin\opencode.exe
```

**This corrects a premise in the rework brief.** OpenCode's npm package
(`opencode-ai`) ships a **native, platform-specific compiled binary**
(`opencode.exe`, selected via optional dependency —
`opencode-windows-x64`/`opencode-windows-x64-baseline` — at install time),
**not a JS entry point**. There is nothing to hand to `process.execPath` the
way `PI_CLI_JS` is (`spawn(process.execPath, [PI_CLI_JS, …])`,
`index.mjs:1430`) — Pi CLI is a JS script; OpenCode is not. The candidate
"resolve the real JS entry and invoke through `process.execPath`" does not
apply here; the working analog is "resolve the real **executable** and spawn
it directly."

### Chosen mechanism

1. **Resolve once, cache, re-resolve lazily on failure** (never crash server
   startup on a bad resolution):
   ```js
   let _openCodeExePath = null;
   function resolveOpenCodeExePath() {
       if (_openCodeExePath && existsSync(_openCodeExePath)) return _openCodeExePath;
       const shimPath = join(homedir(), "AppData", "Roaming", "npm", "opencode.cmd");
       const shimContent = readFileSync(shimPath, "utf8");
       const m = shimContent.match(/"%dp0%\\(.+?)"\s+%\*/);
       if (!m) throw new Error(`Cannot parse OpenCode shim at ${shimPath}`);
       const resolved = join(dirname(shimPath), m[1]);
       if (!existsSync(resolved)) throw new Error(`Resolved OpenCode path missing: ${resolved}`);
       _openCodeExePath = resolved;
       return resolved;
   }
   ```
2. **Invoke via `execFile`, not `execSync`, not raw `spawn`**:
   ```js
   execFile(resolveOpenCodeExePath(), ["db", sql, "--format", "json"],
            { timeout: 4000, encoding: "utf8" },
            (err, stdout) => { /* … */ });
   ```

### Honest evaluation of the two real candidates

| Candidate | Non-blocking? | Shell involved? | Timeout | Verdict |
|---|---|---|---|---|
| **A (chosen)**: `execFile(resolvedExe, argv, {timeout})` | Yes — `execFile` was never the blocking call; `execSync` was | No — argv array, direct exe | Built-in `timeout` option, matches existing 4000 ms | Removes the shell-metacharacter injection layer entirely; native `.exe` is not subject to Node's Windows batch-file (`.cmd`/`.bat`) hardening, so it does not reproduce the measured `ENOENT`/`EINVAL` |
| B: `exec(shimCommand, {shell:true, timeout:4000})` | Yes | **Yes** — cmd.exe interprets the string | Built-in | Works, but stacks a shell-metacharacter surface on top of the SQL-string surface (finding 2) for no offsetting benefit versus A |

**The mandatory outcome — not blocking the event loop — is delivered by
switching off `execSync`, independent of which candidate is chosen.**
Candidate A is selected because it also removes a whole class of injection
surface at zero extra cost, not because it is claimed to make the SQL string
itself safe (it does not — see next section).

**Residual/fragility risk, stated plainly:** the regex parses a fixed,
currently-observed two-token shim shape (`"%dp0%\<relative>" %*`). If a
future `npm`/`opencode-ai` upgrade changes the shim's generated format or the
package's internal `bin/` layout, `resolveOpenCodeExePath()` throws, the
OpenCode branch reports failure (per the Failure/Partial-Success Contract —
502 for a per-engine request, `failures:[...]` for `engine=all`), and the
server keeps running. It never silently returns `[]` and never blocks.

## SQL Safety Contract

**`opencode db` takes exactly one positional SQL string; there is no
parameter binding (fact 2).** Every value that reaches the query is
interpolated into SQL text by the caller. This is stated as a constraint to
design around, not a solved problem — a prior version of this design falsely
claimed the injection surface was removed; it was not.

### Escaping rule (applies to every interpolated string: `directory`, cursor `id`)

1. Reject the value if it contains a NUL byte (`\u0000`) — return 502, do not
   query.
2. Reject the value if it contains a `;` — defense-in-depth against
   statement chaining. Note `;` is a legal NTFS filename character, so this
   is a deliberate over-rejection, not a claim that such paths cannot exist:
   a project directory whose path happens to contain `;` becomes unlistable
   under this rule. Return 502 with a clear reason (not a silent empty
   result) so the failure is diagnosable rather than mysterious; do not
   query.
3. Escape every `'` by doubling it (`'` → `''`) — the standard SQL string
   literal escaping rule, applied **after** steps 1–2, immediately before
   interpolation. A project path containing a single quote (e.g.
   `C:\Users\O'Brien\proj`) round-trips correctly: `'C:\Users\O''Brien\proj'`.
4. Numeric values (`time_updated` from a cursor, `limit`) are validated as
   `typeof v === "number" && Number.isFinite(v)` (and, for `limit`, an
   integer within `[1, 200]`) **before** being interpolated unquoted. A
   non-numeric value is rejected (502/400), never coerced or interpolated as
   a string.

### Where these values come from

- `directory` is never taken directly from the request; it comes from
  `resolveProject(projectIdParam || activeProjectId).path`, i.e. the
  server-side project registry (added via `addProject`/`browseDirectory`,
  owner-controlled), not raw request text.
- Cursor `id`/`ts` **are** client-roundtripped and therefore attacker-reachable
  even though the cursor is "opaque" — nothing signs it. This design
  therefore applies the exact same validation (steps 1–4) to a decoded
  cursor's `id`/`ts` before they reach SQL, which the prior version did not
  address.

### Residual risk — stated plainly, not eliminated

Quote-doubling plus the `;` denylist is **string-construction safety, not a
parameterized query**. If `opencode db`'s underlying execution path ever
permits multi-statement execution through some encoding this denylist does
not anticipate, this scheme would not catch it. This risk is accepted, not
solved, because (a) the dominant source of `directory` is server-controlled,
not raw network input, and (b) the residual surface (cursor `id`) is now
type- and character-validated before interpolation. A stronger fix (e.g. an
allowlist regex for `id` shapes, or moving off `opencode db`'s string
interface entirely) is out of scope for this change and is not required by
the spec.

## Cursor & Pagination Contract

### Ordering total order (used consistently in SQL and in-memory sort)

`ORDER BY timestamp DESC, id DESC`. The ordering `timestamp` is **normalized to
epoch milliseconds** before any comparison, sort, or cursor encoding. It is
already epoch ms for two engines but **not** for Pi, so the normalization is
load-bearing rather than cosmetic:

| Engine | On-disk / on-row form | Normalization |
|---|---|---|
| OpenCode | `time_updated`, verified epoch ms (`1786803563192`) | none needed |
| Claude | `statSync(file).mtimeMs`, epoch ms by definition | none needed |
| Pi | `meta.timestamp` is an **ISO-8601 string** (`"2026-07-30T14:17:02.436Z"`) — measured in **1106 of 1106** real session files; zero are numeric | `Date.parse(...)` → epoch ms, at index-build time |

Pi's normalization MUST happen when the lightweight index is built, before the
value ever reaches a cursor. The cursor's numeric type validation (see SQL
Safety Contract) rejects non-numeric `ts` values, so an un-normalized ISO
string would be rejected as malformed input rather than paginating — a silent
dead end for the entire Pi engine. A `NaN` result from `Date.parse` MUST be
treated as a missing timestamp and fall back to `stat.mtime`.

`id` is a string tiebreaker compared lexicographically (SQLite's default
`BINARY` collation matches JS string `<`/`>` for the ASCII-range ids all three
engines generate).

### Per-engine cursor

Opaque token = `base64url(JSON.stringify({ ts, id }))`. Absent/omitted
cursor = first page. Query predicate once decoded:

```
WHERE (timestamp < cursor.ts) OR (timestamp = cursor.ts AND id < cursor.id)
ORDER BY timestamp DESC, id DESC
LIMIT <limit>
```

For OpenCode this is a literal SQL `WHERE` clause (escaped/validated per the
SQL Safety Contract above). For Pi/Claude it is applied against the
in-memory lightweight index (see Cache & Index Contract).

### `offset=0` compatibility note

The amended spec's own example scenario reads
`engine=claude&limit=150&offset=0`. This design treats `offset=0` (or an
absent `offset`) as a synonym for "no cursor, first page" for literal
compatibility with that scenario text. Any other `offset` value is **not** a
supported pagination mechanism: it is ignored and the request is served as a
first page. `cursor` is the only mechanism that advances a listing.

### Combined (`engine=all`) cursor — encodes the last consumed item per engine

```json
{
  "pi-cli": { "ts": 1786803598000, "id": "abc123" } | "done" | null,
  "opencode": { "ts": 1786803563192, "id": "s_9f2" } | "done" | null,
  "claude": null
}
```

Base64url-encoded as one opaque `nextCursor` string, keyed by the **frozen
wire engine values** (`pi-cli`/`opencode`/`claude` — not the abbreviated
`pi`/`oc`/`cl` a prior version used, which risked leaking the forbidden `pi`
spelling onto the wire). `"done"` is a sentinel meaning that engine's own
listing is fully exhausted; it is never re-queried on subsequent pages once
set, bounding total combined-view work over the lifetime of a scroll session.
`null` means "not started" (equivalent to no per-engine cursor).

### Combined-page algorithm (k-way merge, provably correct for one page)

1. Decode the combined cursor into three sub-cursors (all `null` on page 1).
2. For every engine **not** marked `"done"`, fetch up to `limit` items using
   that engine's own cursor predicate (§ above), in parallel
   (`Promise.all`).
3. **Correctness argument for fetching exactly `limit` per engine:** the top
   `limit` items of the union of N sorted-descending streams can never
   require more than `limit` items from any single stream — if a stream
   contributed more than `limit` items to the result, the other streams
   contributed zero, in which case that stream's own first `limit` already
   is the answer. Fetching `limit` per live engine (≤ 3·`limit` total) is
   therefore sufficient and still bounded by page size, not by totals on
   disk.
4. Merge-sort the fetched candidates by `(timestamp DESC, id DESC, engine)` —
   `engine` (alphabetical: `claude` < `opencode` < `pi-cli`) is a final,
   fully deterministic tiebreaker for the theoretical case of an identical
   `(timestamp, id)` pair across two different engines. Take the top
   `limit`.
5. **Next cursor per engine.** These cases are **priority-ordered — the first
   one that matches wins** — for each engine `E`. Let `n` = the number of
   items `E`'s fetch returned and `m` = the number of those items emitted in
   this page's output (`0 ≤ m ≤ n ≤ limit`). Order matters: an earlier
   formulation listed them as unordered, non-exhaustive bullets, which left
   the state `n == limit && m == n` (a full fetch, fully emitted — the
   ordinary case for single-engine tab pagination, and a recurring one in
   `engine=all` as other engines reach `"done"`) matching none of them.

   1. **`E`'s fetch failed** (engine error, not an empty result): sub-cursor
      **unchanged**, and `E` is *not* marked `"done"`. A failure returns
      `n = 0`, which must never be mistaken for exhaustion. See the
      partial-failure contract below.
   2. **`n < limit && m == n`** (the fetch came up short *and* every item it
      returned was emitted — nothing was left unconsumed): `E` is genuinely
      exhausted — set its sub-cursor to `"done"`.
   3. **`m ≥ 1`** (at least one item from `E` was emitted this page —
      covers both `m == n` with a full fetch, and `m < n` with some items
      crowded out): `E`'s new sub-cursor = `{ts, id}` of the **last**
      (lowest-rank) *emitted* item from `E`. Any un-emitted remainder
      (`n − m` items) is strictly older than that watermark, so it stays
      reachable on the next page — this also correctly leaves `E` **not**
      `"done"` even when `n < limit`, if some of those `n` items were
      crowded out (`m < n`): a short fetch is not exhaustion by itself,
      only rule 2's stricter "short *and* fully consumed" is.
   4. **`m == 0`** (fetched but nothing from `E` made the merged cut —
      fully crowded out this page, including a fetch of `n = 0` that isn't
      already caught by rule 1 or rule 2): sub-cursor left **unchanged**
      from the input — never advanced to "last fetched". Advancing would
      silently drop `E`'s un-emitted items; leaving it unchanged makes them
      candidates again.

   These four cases are collectively exhaustive over every `(n, m)` pair with
   `0 ≤ m ≤ n ≤ limit`, given a fetch that did not fail: rule 2 claims
   `n < limit && m == n`; rule 3 claims every remaining state with `m ≥ 1`
   (which necessarily includes `n == limit && m == n`); rule 4 claims what's
   left, `m == 0`. No `(n, m)` pair falls through.

   **Why rule 2 must require full consumption.** Marking `E` exhausted on a
   short fetch alone reintroduces the exact defect this change exists to
   remove. Worked counterexample using the measured data: with `limit=30`,
   Claude's fetch returns all 9 of its sessions (9 < 30) while Pi and
   OpenCode each return 30. Of the 69 candidates the merge emits the top 30,
   and because 183 OpenCode sessions are newer than Claude's 3rd-newest
   (`exploration.md`), only Claude's 2 newest are emitted (`n=9, m=2`). A
   short-fetch-only rule would mark Claude `"done"` permanently and its
   remaining 7 sessions would never be returned — the original 9→2 bug,
   restored by its own fix. `n=9, m=2` fails rule 2's `m == n` clause, so it
   correctly falls to rule 3 instead, which keeps those 7 reachable.
6. Response `nextCursor` = `null` only when every engine in scope is
   `"done"`; otherwise the encoded combined object. For a **single-engine**
   request the same rules apply with one engine in scope, so `nextCursor` is
   `null` exactly when that engine reached rule 2 — the ordinary termination
   case for a tab that has been paged to its end.

This gives a no-gaps/no-duplicates guarantee that survives a concrete
counterexample rather than resting on assertion: every item any engine
fetched is either emitted in this page or remains reachable on the next,
because an engine is only ever closed after its fetched set was fully
emitted.

## Ordering Key & Content-Free Matching (per engine)

| Engine | Match key (membership) | Order/paginate key | Bytes of file content read to decide match+order |
|---|---|---|---|
| Claude | Directory-name encoding only — exact, reproducible, **zero content** (fact 5, `index.mjs:981-982`, unchanged) | `statSync(file).mtimeMs` — filesystem metadata, **zero content** | **0 bytes** |
| Pi | Directory-name pre-filter (necessary, readdir-only) + per-file `cwd` from a flat **8 KB** head read (sufficient, when present) | `meta.timestamp` from the same 8 KB read, falling back to `stat.mtime` when absent | ≤ 8 KB per candidate file |
| OpenCode | SQL `WHERE directory = ?` | SQL `time_updated` column | **0 bytes** (no file at all — SQLite row) |

**8 KB for Pi is a measured, not assumed, figure**: fact 6 states every one
of 1106 real Pi session files exposes a parseable `type:"session"` line
(which carries both `cwd` and `timestamp`) within the first 8 KB — a 100%
observed success rate, so a flat (non-doubling) 8 KB window is justified
without added complexity.

### Match-key-not-found vs. file-too-large — the two failure modes are now distinct, and a session is never dropped merely for being large

- **Claude never depends on content for match**, so "file too large" cannot
  cause a Claude session to be excluded — the previous design's biggest
  defect (would have dropped 8 of 9 real Claude sessions) cannot recur
  structurally, not just by a bigger constant.
- **Pi's match key not found within the 8 KB window** (theoretically
  possible for a future/atypical file; not observed in the measured 1106):
  bounded parsing only — there is **no escalation to a full-file read**,
  consistent with the ratified requirement that every rebuild unit stays
  bounded and never performs a full-file read. If the usable `cwd` is not
  determinable within the bounded 8 KB window, the session is excluded from
  the match set and the existing observable structured diagnostic is emitted
  (`console.error("[<Engine> Sessions Scan Error]", projPath, err.message)`),
  so the omission is diagnosable rather than silent — not a fabricated match
  and not a full-file read. Because the bounded window succeeds on 100% of
  the 1106 measured files, this exclusion path is expected to trigger ~0% of
  the time, matching the safety margin fact 6 implies.
- **Hydration (title/preview) not found within its bounded window** (see
  next section): never drops the session either — it falls back to the
  existing placeholder pattern (`Sesión Claude (${sid.slice(0,8)})`), which
  is visibly a placeholder, not a fabricated value.

### Content hydration (title/preview) — page-scoped, honestly unmeasured window

Match/order decisions above need no content (or a measured 8 KB) for Pi/
Claude. **Title and preview still need content**, but only for the `limit`
items actually being returned this page — never for the full candidate set.

Design direction adopted: a bounded **head** read (title = first user
prompt, near file start) plus a positioned bounded **tail** read (preview =
last reply, near file end), avoiding a full read of files up to 14 MB.

**Honesty note, as required:** unlike the 8 KB Pi match window, no
measurement exists for how many bytes are needed to reliably reach the first
user prompt or the last reply — the exploration only measured the match-key
line (line 1), not title/preview depth. Rather than assert a false-precision
number, the window is **adaptive with an explicit, generous, labeled
ceiling**: start at 8 KB (reusing the one number that is measured to work
for this file family), double on a miss, cap at **256 KB** per side
(head and tail independently). The ceiling is chosen as a safety bound, not
a claim of sufficiency: it fully covers the measured Claude median (288 KB
is close to, and often under, 2×256 KB combined head+tail coverage) while
reading ≤ ~3.7% of the measured 14 MB maximum. **This ceiling should be
validated against real title/preview depths during implementation/tasks; if
found insufficient, only the ceiling constant changes, not the architecture.**
On ceiling exhaustion: use the existing placeholder text, do not drop the
session, do not fabricate a title from partial/truncated content.

**UTF-8 boundary handling:** a byte-offset read does not align with `\n` or
multi-byte UTF-8 character boundaries except at position 0. Head reads keep
all fully-terminated lines and discard a possibly-partial trailing line.
Tail reads (start position `max(0, size - window)`) discard a possibly-partial
leading line (the first line after that byte offset may start mid-line or
mid-character) and keep the rest.

## Response Envelope — exactly one shape

Every successful (`2xx`) response to `GET /api/v1/sessions`, for **every**
`engine` value including `all`, has this exact shape:

```json
{
  "sessions": [ /* SessionItem[] */ ],
  "nextCursor": "opaque-string-or-null",
  "partial": false,
  "failures": []
}
```

- `partial`/`failures` are always present (empty on full success) rather
  than conditionally shaped, so the Android client deserializes one
  documented contract regardless of `engine`.
- A **per-engine** request whose single engine fails to scan does **not**
  use this envelope — it returns HTTP 502 `{ "engine": "...", "error": "..." }`
  (see Failure/Partial-Success Contract). This is a different status code on
  a different code path, not a second success shape.
- `engine=all` with 1–2 engines failing still returns HTTP 200 using the one
  envelope above, with `partial: true` and populated `failures`, carrying
  the surviving engines' real data.

`GET /api/v1/sessions/counts` is a **separate endpoint with its own single
shape** (defined below), not folded into this envelope. It carries its own
`partial`/`failures` fields, mirroring this envelope's partial-success
semantics, because a counts response can also be partial when one engine fails.

## Session Metadata Accuracy (`messageCount`)

Per the amended spec's "No Fabricated Session Metadata" requirement:

- **List row:** `messageCount` is `Int?`. Populated (exact) only for
  OpenCode, via the SQL correlated `COUNT` subquery — already free, no extra
  I/O. `null` for Pi and Claude list rows — a bounded read cannot
  substantiate an exact count, so none is shown (the badge is omitted from
  the UI entirely when `null`, not rendered as `"null mensajes"` or a guess).
- **Detail view (`GET /api/v1/sessions/:id/history`):** unaffected by this
  change — it already reads the full session file, so an exact count MAY
  still be computed and shown there for any engine, out of scope of the
  listing's bounded-read requirement.

## Cache & Lightweight Index Contract

- **Key:** `(normalizedProjectPath, engine)`.
- **Cached value:** the **lightweight index** — an array of
  `{id, timestamp, filePath}` records sorted `(timestamp DESC, id DESC)` for
  that engine's full matching set, **not** hydrated content. This single cache
  serves three needs at once: (a) resolving a cursor predicate without
  re-scanning, (b) true per-engine counts (`items.length`), (c) the k-way
  merge's `Promise.all` fan-out.

  **`filePath` is mandatory, not redundant.** An earlier version cached only
  `{id, timestamp}` and assumed a session id could reconstruct its file path.
  That is false for Pi: measured across all **1106** real Pi session files,
  `meta.id` equals the filename basename in **0** cases. Filenames are
  `<ISO-timestamp>_<uuid>.jsonl` while `meta.id` carries only the `<uuid>`
  (e.g. file `2026-07-30T14-17-02-436Z_019fb362-c4e4-7cae-8a08-771a7439834e.jsonl`
  vs. id `019fb362-c4e4-7cae-8a08-771a7439834e`). Without the stored path,
  page-scoped hydration for Pi would be unimplementable — it would have to
  re-scan the directory per row to find the file, reintroducing the very cost
  this design removes. Claude happens to satisfy `id === basename`
  (`index.mjs:1004`) and OpenCode has no file at all, but the index stores
  `filePath` uniformly (`null` for OpenCode) so hydration never has to
  special-case path recovery.
- **Freshness / invalidation — 15-second in-memory TTL (owner-ratified, replaces
  freshness-token recomputation):** the cached index is retained for up to **15
  seconds** after it was built. On a read, if the entry is absent (cold start) or
  its wall-clock age exceeds 15 s, it is dropped and a fresh index is built on
  that request; otherwise the cached entry is returned as-is. **Listings and
  counts MAY be stale by up to 15 seconds** — this is the explicit accepted
  freshness window, not a defect. A rebuild **MAY occur at TTL expiry even when
  source files did not change**; each rebuild unit is bounded (a file `stat` for
  Claude, a small bounded 8 KB head read for Pi, a cheap SQL aggregate for
  OpenCode), so the periodic rebuild is cheap: measured on the target machine,
  enumerating all session directories takes **12 ms** and `statSync` on all
  1181 files takes **40 ms** — a single project is a fraction of that. The warm
  path between rebuilds stays at O(page size).
- **Why TTL instead of mtime/source-change-aware invalidation:** a freshness
  token such as `max(mtimeMs)`/`MAX(time_updated)` is a byproduct of the same
  full scan that builds the index; there is no cheaper standalone way to
  recompute just the token with the implemented index builders. A literal
  per-request "recompute token ≠ stored → drop" test would therefore cost a
  full rebuild on every request, defeating the "per-page bounded work once warm"
  guarantee the cache exists to satisfy. The 15-second TTL is the simpler,
  correct-enough contract at this scale and is the owner-ratified behavior
  (proposal + "Per-Page Bounded Work" spec).
- **Known limitation, stated plainly:** because invalidation is time-based, an
  index can serve records that are up to 15 seconds old relative to disk —
  including a session created, deleted, or renamed within that window — until
  the next rebuild fires. This is the explicit accepted trade (the spec's
  TTL-staleness scenario permits it). New or deleted sessions are naturally
  picked up by the next periodic rebuild; no source-change detection (content
  hashing, filesystem watch) is needed or implemented. Closing the staleness
  fully would require shortening the TTL or adding a watch, both out of scope
  here. Final verification (sdd-verify) MUST describe the freshness contract as
  TTL-bounded and MUST NOT claim strict mtime/source-change-aware invalidation.
- **Deviation from a literal "bounded by page size" reading, stated plainly
  and now measured:** building the Pi/Claude index on a cache miss costs
  O(that engine's matching file count for the project) — for Pi, up to ~75
  bounded 8 KB reads for `Sistema_de_mantencion`; for Claude, 9 `statSync`
  calls (no content). This is a one-time cost per TTL expiry or cache miss, not paid per
  page, and each unit of that cost is itself bounded (8 KB, or 0 bytes for
  Claude) — never a full-file read. **OpenCode — the dominant volume engine
  in this codebase (1086 vs. 75 vs. 9) — is not subject to this at all**: its
  `WHERE`+cursor+`LIMIT` query is scoped at the database layer with no JS-side
  full-scan ever, cached or not.

  **Measured magnitude of the deviation** (target machine, all projects at
  once — a single project is a fraction):

  | Operation | Cost |
  |---|---|
  | Current implementation: full `readFileSync` + per-line parse of every session file | **46 392 ms** (9 640 MB read, 1 509 215 lines parsed) |
  | This design's worst case: enumerate + `statSync` all 1181 files + 8 KB head read of all 1106 Pi files | **527 ms** (12 + 40 + 475 ms) |

  That is an **88x** reduction, and the 527 ms figure is the *cold* path — the
  warm path skips it entirely. This is why the deviation is acceptable: the
  spec's intent is that a listing must not cost what a full scan costs, and a
  cold index build is three orders of magnitude away from the 4-minute
  baseline the requirement was written against. The spec is being amended to
  scope its bounded-work requirement to per-page work after index warm-up, so
  this is a ratified deviation rather than a unilateral one.

  A persisted index (SQLite via `node:sqlite`, or a JSON index file) was
  reconsidered against these numbers and again rejected: it would reduce
  527 ms to roughly 50 ms while adding a new persisted-state class with its own
  staleness, corruption, and migration failure modes.   Under the TTL approach the periodic rebuild already re-enumerates the directories (measured at 12 ms) every ≤15 s, so a persisted index would not improve the staleness contract — it would only shave the already-acceptable 527 ms cold build to ~50 ms — Revisit only if the cold path grows by orders of
  magnitude, or if a capability that genuinely needs an index (cross-project
  search, aggregate counts for all projects at once) is requested — that would
  be a capability decision, not a performance one.
- Content (title/preview, exact OpenCode `msg_count`) is **not** cached; it
  is fetched fresh per page request, bounded to the `limit` items returned.

### `GET /api/v1/sessions/counts?projectId=...` — the one counts shape

Reads the same three per-engine lightweight-index cache entries. Exactly one
success shape, with the same partial-success semantics as the listing envelope:

```json
{
  "counts": { "pi-cli": 75, "opencode": 1086, "claude": 9, "all": 1170 },
  "partial": false,
  "failures": []
}
```

- `counts` keys are the **frozen wire engine values**, plus `all`. `all` is the
  sum of the engines that succeeded — so when `partial` is `true`, `all` is a
  lower bound, not a total. The client MUST NOT present a partial `all` as
  authoritative.
- When 1–2 engines fail: HTTP 200, `partial: true`, `failures:[{engine,message}]`,
  and the failing engines' keys are **omitted** from `counts` rather than
  reported as `0` — a failure must never be indistinguishable from "this engine
  has zero sessions" (spec: Observable Engine Failure). The client shows no
  count badge for an omitted engine, not a zero.
- When all three fail: HTTP 502 `{ "error": "..." , "failures":[...] }`,
  consistent with the listing endpoint's all-engines-failed path.

Kotlin side (note the required `@SerialName` — the JSON key is `pi-cli`, which
is not a legal Kotlin identifier, so a bare `piCli` property would silently fail
to deserialize):

```kotlin
@Serializable
data class SessionCountsResponse(
    val counts: SessionCounts,
    val partial: Boolean = false,
    val failures: List<SessionEngineFailure> = emptyList()
)

@Serializable
data class SessionCounts(
    @SerialName("pi-cli") val piCli: Int? = null,
    val opencode: Int? = null,
    val claude: Int? = null,
    val all: Int? = null
)
```

Counts are nullable precisely so an omitted (failed) engine is representable as
absent rather than as `0`.

## Sequence Diagrams

**Per-engine page (e.g. Claude tab), cache warm:**
```
Client -> Server: GET /sessions?engine=claude&cursor=C&limit=30
Server -> IndexCache: lookup(project, claude)          -- hit, within TTL
Server: apply cursor predicate to cached {id,timestamp,filePath} index  -- in-memory, O(limit)
Server -> FS: bounded head+tail read x <=30 candidates  -- title/preview only
Server --> Client: { sessions[<=30], nextCursor, partial:false, failures:[] }
```

**Per-engine page, cache cold (first request after TTL expiry / cold start):**
```
Client -> Server: GET /sessions?engine=pi-cli&cursor=null&limit=30
Server -> IndexCache: lookup(project, pi-cli)           -- miss (TTL expired or cold)
Server -> FS: readdir(sessionsBaseDir)                  -- subdir names only
Server -> FS: filter subdirs by repaired wrapper-stripped name match
Server -> FS: readdir(matched subdirs) + 8KB head read x ~75 files  -- match+order key
Server -> IndexCache: store({id,timestamp,filePath}[])
Server: apply cursor predicate                          -- first 30
Server -> FS: bounded head+tail read x 30                -- title/preview only
Server --> Client: { sessions[30], nextCursor, partial:false, failures:[] }
```

**Combined "Todos" page:**
```
Client -> Server: GET /sessions?engine=all&limit=30       -- page 1: cursor omitted entirely
   (page 2 sends cursor=<opaque-base64url> returned as nextCursor; the decoded
    per-engine sub-cursor object is server-internal and never on the wire)
Server -> PiIndex(cursor["pi-cli"], 30) \
Server -> ClaudeIndex(cursor["claude"], 30) }-- Promise.all, parallel, index-cache-backed
Server -> OpenCodeQuery(cursor["opencode"], 30) /
Server: merge-sort <=90 candidates by (ts desc, id desc, engine); take 30
Server: compute per-engine next sub-cursor (last CONSUMED, or unchanged, or "done")
Server -> FS: bounded head+tail read x 30 (only the emitted items) -- title/preview
Server --> Client: { sessions[30], nextCursor:"<opaque-base64url>", partial:false, failures:[] }
```

**OpenCode call no longer blocks chat SSE (resolved-exe path):**
```
t0   Client A -> Server: GET /chat/stream                     (SSE, stays open)
t0   Client B -> Server: GET /sessions?engine=opencode
t0   Server: resolveOpenCodeExePath()                          -- cached after first call
t0+  Server -> execFile(resolvedExe, ["db", sql, "--format","json"], {timeout:4000})
                                                                 async, event loop free
t1   Server --> Client A: SSE event                            NOT blocked (was 0-4s under execSync)
t4   execFile callback -> Server --> Client B: page
```

**OpenCode exe resolution fails (shim format drifted on upgrade):**
```
Client -> Server: GET /sessions?engine=opencode
Server: resolveOpenCodeExePath() throws (no shim match / exe missing)
Server: console.error("[OpenCode Sessions Scan Error]", err.message)
Server --> Client: 502 { engine: "opencode", error: "..." }   -- never blocks, never silent []
```

## File Changes

| File | Action | Description |
|---|---|---|
| `idupi-server/index.mjs:829-1047` | Modify | Replace `getProjectSessions` with per-engine bounded scan/index-build/pagination; remove dead `sessionsCacheMap`; add `resolveOpenCodeExePath` |
| `idupi-server/lib/sessions.mjs` | Create | Importable scan/match/order/cursor/merge/cache/SQL-escaping functions for `node:test` (exceeds proposal's stated Affected Areas — justified: this is where the new pure logic must live to be testable at all, since `idupi-server` has no test runner today) |
| `idupi-server/index.mjs:1856-1868` | Modify | `engine`/`cursor` params (accept `offset=0` as first-page alias only); one response envelope; new `GET /api/v1/sessions/counts` route (exceeds proposal's stated Affected Areas — justified: required by the amended spec's "True Per-Engine Counts" requirement) |
| `idupi-server/test/sessions.test.mjs` | Create | `node:test` suite against fixture directories + injectable OpenCode query runner + fixture shim-content string (using the verbatim `opencode.cmd` content captured in this design) |
| `app/.../domain/repository/IduPiClient.kt:38` | Modify | `getSessions()` → `getSessions(engine: String = "all", cursor: String? = null, limit: Int = 30): SessionsPage`; add `getSessionCounts(): SessionCountsResponse` |
| `app/.../domain/model/SessionItem.kt` | Modify | `messageCount: Int` → `messageCount: Int?` |
| `app/.../domain/model/SessionsPage.kt` | Create | `data class SessionsPage(sessions: List<SessionItem>, nextCursor: String?, partial: Boolean, failures: List<SessionEngineFailure>)`; `data class SessionEngineFailure(engine: String, message: String)`; `data class SessionCountsResponse(counts: SessionCounts, partial: Boolean, failures: List<SessionEngineFailure>)`; `data class SessionCounts(@SerialName("pi-cli") piCli: Int?, opencode: Int?, claude: Int?, all: Int?)` — see Cursor & Pagination Contract's `/counts` section for why every field is nullable and why `piCli` needs `@SerialName` |
| `app/.../data/remote/RealIduPiClient.kt:214-215` | Modify | Build query string from `engine`/`cursor`/`limit`; deserialize `SessionsPage`; implement `getSessionCounts()` against `/api/v1/sessions/counts`, deserializing `SessionCountsResponse` |
| `app/src/test/java/com/example/idupi/FakeIduPiClient.kt:113` | Modify | Match new `getSessions(...)` signature and `SessionsPage` return type; add `getSessionCounts()` fake — required so the 83-test suite keeps compiling |
| `app/src/test/java/com/example/idupi/viewmodel/SessionsViewModelTest.kt` | Modify | `sampleSession()`/`fake.sessionsToReturn` currently assume a bare `List<SessionItem>`; update to the `SessionsPage` shape and the new `messageCount: Int?` — otherwise this file fails to compile against the changed fake, silently breaking the 83-test success criterion |
| `app/.../viewmodel/SessionsViewModel.kt` | Modify | `init{}` stays the single trigger; add `counts` `StateFlow`, per-engine `selectEngine()`/`loadMore()` that call the server instead of filtering a local list |
| `app/.../ui/screens/SessionsScreen.kt` | Modify | Remove duplicate `LaunchedEffect(Unit)`; chips call `viewModel.selectEngine()` (network), not `.filter()`; chip counts read from `counts` `StateFlow`, showing no badge for an engine key absent from a partial counts response; scroll-triggered `loadMore()`; local `SessionCard`'s message-count badge renders only when `messageCount != null` |
| `app/.../ui/components/SessionCard.kt:91` | Modify | Dead-code duplicate composable (unused — `SessionsScreen.kt:153` uses its own local `SessionCard`, not this one) also interpolates `messageCount` directly (`"${session.messageCount} mensajes · ..."`); update it to the same null-omits-badge rule for consistency even though it does not affect the shipped screen, so the codebase has no lingering call site that would render `"null mensajes"` if ever wired up |

## Failure / Partial-Success Contract

- Silent `catch {}` blocks now log
  `console.error("[<Engine> Sessions Scan Error]", projPath, err.message)`.
- Per-engine request: scan failure → HTTP 502 `{ engine, error }`, never a
  silent `[]`.
- `engine=all`: HTTP 200 with the one documented listing envelope,
  `partial: true`, populated `failures:[{engine,message}]` when 1–2 engines
  fail — surviving engines' real data still ships. A failed engine's cursor
  entry is left unchanged (see Cursor & Pagination Contract), so retrying the
  same page naturally resumes once the failure clears.
- `/counts`: same `partial`/`failures` semantics, but in its own shape (see
  the `/counts` section) — a failed engine's key is omitted from `counts`
  entirely, never reported as `0`.

## Testing Strategy

Run command: `node --test idupi-server/test/sessions.test.mjs` (Node's built-in
runner, part of `node:test` — zero new dependencies). `config.yaml`'s
`node_server.test_command: null` reflects that no runner exists *today*; this
change is what introduces one, so `sdd-tasks`/`sdd-apply` should update that
field to this command once the suite lands.

| Layer | What | Approach |
|---|---|---|
| Unit (`node:test`) | wrapper-stripped Pi directory match, 8 KB Pi match+order extraction, Claude mtime-only order (assert no `readFileSync` call during scan phase via a spy), SQL escaping (quote-doubling, `;`-reject, numeric-type validation), cursor encode/decode round-trip incl. combined per-engine object and the `"done"` sentinel, k-way merge no-gaps/no-duplicates property test across 3+ simulated pages, `resolveOpenCodeExePath` regex against the verbatim shim content captured above, `messageCount` null-omission | Fixture dirs with synthetic `.jsonl`; OpenCode branch tested via an injectable query-runner, no real CLI/DB; shim resolver tested via an injectable file-read function, no dependency on the real npm install path |
| Not unit-testable | real `opencode db` output shape, real Windows `execFile` behavior against the actual resolved `.exe` | Manual smoke script in `scratch/`; `node --check` on touched files |
| Android | trigger de-dup, per-engine load state, `messageCount == null` badge omission, `SessionsPage`/`SessionCounts` deserialization | `./gradlew :app:testDebugUnitTest` (83 tests, kept compiling per the File Changes table) + new cases for `selectEngine`/`loadMore`/counts |

## Threat Matrix

Process-integration and SQL-construction boundaries are **Applicable**.

| Boundary | Applicability | Design response |
|---|---|---|
| Shell command construction | Applicable | `execFile(resolvedExePath, argv, {timeout})` — argv array, no shell, direct native exe target (not the `.cmd` shim) |
| SQL string construction | Applicable | No parameter binding exists (fact 2); quote-doubling + `;`-denylist + NUL-rejection + numeric-type validation on every interpolated value, applied to **both** `directory` and client-roundtripped cursor `id`/`ts`. Explicitly **not** claimed to be equivalent to a parameterized query — residual risk documented above |
| Process blocking | Applicable | `execFile` is inherently async; the actual prior defect was `execSync`, not the invocation shape. Built-in `timeout:4000` preserved |
| External shim/binary resolution | Applicable | `opencode.cmd`'s target is parsed once, cached, and re-resolved lazily on failure; resolution failure is logged and surfaced as an engine failure (502 / `failures[]`), never a crash or a silent empty result |
| Git/PR/VCS automation | N/A — none in this change | — |
| Executable-file classification | N/A — no new executable artifacts introduced by this change (the resolved `.exe` already exists on disk from the `opencode-ai` npm install) | — |

RED tests: argv-array construction (no string concat of `projPath`/SQL into
a shell command), SQL escaping unit tests (quote-doubling, `;`-reject,
non-numeric cursor rejection), shim-regex parsing against the verbatim
captured shim content, timeout preserved, SSE keeps flowing during an
OpenCode call in a mocked harness.

## Known Deviations & Residual Risks (consolidated)

- Cursor-driven cache-miss index build for Pi/Claude is O(that engine's
  session count for the project), not O(page size), on the first request
  after TTL expiry or cache miss — see Cache & Lightweight Index Contract for the full justification
  and why OpenCode (the dominant-volume engine) is unaffected.
- Listings and counts MAY be stale by up to 15 seconds; a periodic rebuild MAY fire at TTL expiry even with no source change — accepted per the "Per-Page Bounded Work" spec's TTL scenarios; no mtime/source-change-aware invalidation is claimed (see Cache & Lightweight Index Contract).
- SQL injection surface is reduced, not eliminated — see SQL Safety Contract.
- OpenCode shim-parsing is coupled to today's observed two-line `.cmd`
  format; a future `npm`/`opencode-ai` layout change requires updating the
  regex, not the architecture — fails loud (502/`failures[]`) if it drifts.
- The hydration byte-window ceiling (256 KB) is a labeled safety bound, not
  a measured sufficiency figure — see Ordering Key & Content-Free Matching.

## Areas Beyond the Proposal's Stated Affected Areas

- `idupi-server/lib/sessions.mjs` (new module) and `GET /api/v1/sessions/counts`
  (new route) both exceed the proposal's original `Affected Areas` table.
  Both are required by the amended spec (testable pure logic location; true
  per-engine counts as a first-class requirement) and are recorded here
  rather than silently expanding scope.

## Migration / Rollout

No data migration. Batches A–D each land as their own commit; the repo has
zero prior commits, so each commit is itself the revertible unit via
`git revert`. Batches touch disjoint code paths (server scan/cache vs.
Android triggers), so a regression in one reverts independently of the rest.

## Blocking Findings — Resolution Map

| # | Finding | Where resolved in this document |
|---|---|---|
| 1 | OpenCode invocation not implementable as designed | "OpenCode Invocation — Verified Resolution" — real target verified by reading the shim; `execFile` (not raw `spawn`) on the resolved `.exe` |
| 2 | SQL string safety | "SQL Safety Contract" — explicit escaping rule + residual risk stated |
| 3 | "Fail closed" replaced | "Ordering Key & Content-Free Matching" — match-undetermined vs. too-large distinguished; no session ever dropped for size |
| 4 | Cursor pagination, tiebreaker, last-consumed | "Cursor & Pagination Contract" |
| 5 | Ordering key obtainable without content | "Ordering Key & Content-Free Matching" table |
| 6 | No `LIMIT`/`OFFSET` for OpenCode while rejecting offset elsewhere | "Cursor & Pagination Contract" — same predicate style used everywhere |
| 7 | One `engine=all` envelope | "Response Envelope — exactly one shape" |
| 8 | Full client blast radius in File Changes | "File Changes" table, including `FakeIduPiClient.kt` and `SessionsViewModelTest.kt` |
| 9 | `messageCount` decided, not deferred | "Session Metadata Accuracy" |
| 10 | Cursor tiebreaker + last-consumed semantics (no-gaps proof) | "Cursor & Pagination Contract" — correctness argument + per-engine advance rule |

## Open Questions

None outstanding. Default page size is decided (30, per the sequence
diagrams above); `messageCount` display is decided (Session Metadata
Accuracy section). The hydration byte-window ceiling (256 KB) is flagged
above as worth validating during implementation, but does not block moving
to `sdd-tasks` — the fallback behavior on a miss is already fully specified.
