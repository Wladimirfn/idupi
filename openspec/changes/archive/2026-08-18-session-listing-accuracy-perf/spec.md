# Session Listing Accuracy and Performance — Specification

Four new capabilities (no prior specs exist for this area): `session-listing-pagination`,
`session-listing-nonblocking-scan`, `session-listing-robustness`,
`sessions-screen-single-refresh`.

**Non-goal**: grouping/de-duplicating Pi's `parentSession`-chained per-hop session files
into one logical conversation is explicitly out of scope for this change.

## session-listing-pagination

### Requirement: Per-Engine Bounded Listing

`GET /api/v1/sessions` MUST, when called with an `engine` query param plus `limit` and an
opaque `cursor`, return sessions from only that engine, paginated independently over that
engine's own full session set for the project — never truncated by another engine's
volume.

Pagination MUST be cursor-based, not `offset`-based. Rationale: sessions are created
while the user is browsing, so a positional offset shifts between requests and silently
duplicates or skips rows at every page boundary. An opaque cursor is required to satisfy
the no-gaps/no-duplicates guarantee below. The cursor MUST include a tiebreaker (session
identity in addition to the ordering timestamp) so that sessions sharing a timestamp are
neither dropped nor repeated, and MUST encode the last **consumed** item per engine.

The `engine` param domain is exactly `all`, `pi-cli`, `opencode`, `claude`. These are the
existing wire values on both sides and MUST NOT be renamed by this change: the server
compares `engineFilter` against them (`idupi-server/index.mjs:852,942,972`) and the client
sends them from its filter chips (`SessionsScreen.kt:97,104,111`), including the `pi-cli`
spelling (not `pi`). A session record with a null `engine` is treated as `pi-cli`, matching
existing client behavior (`SessionsScreen.kt:80`).

#### Scenario: Engine-scoped request returns the true per-engine set

- GIVEN project `Sistema_de_mantencion` has 9 Claude sessions
- WHEN a client requests `engine=claude&limit=150&offset=0`
- THEN the response MUST include all 9 Claude sessions

#### Scenario: Independent pagination per engine

- GIVEN OpenCode has 1086 sessions for a project
- WHEN a client requests `engine=opencode&limit=20`, then repeats with the returned cursor
- THEN each page MUST return the next 20 OpenCode sessions in that engine's own
  chronological order, unaffected by Pi's or Claude's counts

### Requirement: True Per-Engine Counts

Per-engine session counts (chip counts) MUST be computed independently of any truncated
listing payload and MUST reflect the true number of matching sessions on disk.

#### Scenario: Claude count reflects the true total

- GIVEN Claude has 9 sessions for a project
- WHEN a client requests the per-engine count
- THEN the response MUST report 9, never a count derived from a globally-sliced list

### Requirement: Chronological "All" View With Pagination

The combined all-engines view MUST return sessions in strict descending chronological
order (newest first) across engines and MUST support cursor pagination rather than
silently truncating beyond a fixed page.

#### Scenario: "Todos" tab loads more on scroll

- GIVEN 1170 total sessions across engines for a project
- WHEN a client requests the first page (`limit=30`) then requests again with the
  returned cursor
- THEN both pages MUST continue in the same global recency order with no gaps or
  duplicates across the boundary

### Requirement: Ordering Key Obtainable Without Reading File Bodies

The timestamp used to order and to paginate sessions MUST be obtainable without reading a
session file's body, so that ordering cost does not scale with file size.

Rationale (measured): Claude session files on the reference project have a median size of
288 KB and a maximum of 14 MB, and the current code derives its ordering timestamp from
the **last** entry in the file, which requires reading the file end to end. Any ordering
key that depends on the file body therefore reintroduces the very cost this change exists
to remove.

#### Scenario: Large session file does not inflate listing cost

- GIVEN a project containing a 14 MB Claude session file
- WHEN a page of sessions is listed
- THEN the ordering timestamp for that session MUST be obtained without reading its body

### Requirement: No Fabricated Session Metadata

Every field the listing reports MUST be either accurate or absent. The listing MUST NOT
report a value that a bounded read cannot substantiate.

This applies specifically to the per-session message count, which today is produced by
fully parsing every line of the session file. Under bounded reads an exact count is not
obtainable for Pi and Claude sessions, so the listing MUST NOT display a fabricated or
silently-truncated count. It MAY omit the count, or report it only for an engine where an
exact value is available at no extra cost (OpenCode exposes one via SQL aggregation).
A precise count MAY still be shown in the session detail view, where the full file is
already being read.

#### Scenario: Count is omitted rather than guessed

- GIVEN a 14 MB Claude session whose message count cannot be determined from a bounded read
- WHEN that session appears in the listing
- THEN the listing MUST NOT display a count implying a full parse occurred

### Requirement: Per-Page Bounded Work

Work performed to answer a listing request, **once a per-project per-engine index is
warm**, MUST be bounded by the requested page size, not by the total sessions on disk
(baseline today: ~4 minutes for the full scan, unconditionally, on every request).

**Owner-ratified deviation:** indexes MAY be retained for up to 15 seconds (TTL), so
listings and counts MAY be stale during that window. On a cache miss (including startup
or TTL expiry), rebuilding the index MAY cost up to O(that engine's session count for
the project), but each rebuild unit MUST itself stay bounded (e.g. a file stat or a
small bounded read — never a full-file read). A rebuild MAY occur at TTL expiry even
when no source change is detected. The measured cold rebuild MUST be 527 ms, or 88x
below the 46,392 ms unbounded baseline, in the reference measurement environment.
Warm requests inside the TTL MUST remain bounded by the requested page size.

#### Scenario: Small page from a large session set, index warm

- GIVEN a project with 1086 OpenCode sessions on disk and a warm index
- WHEN a client requests `engine=opencode&limit=20`
- THEN the response MUST NOT scale in cost with total sessions on disk

#### Scenario: TTL permits bounded freshness staleness

- GIVEN a per-project per-engine index was built less than 15 seconds ago and source files changed
- WHEN a client requests a listing or count within that TTL
- THEN the response MAY be up to 15 seconds old, but returned records and counts MUST remain internally accurate and MUST preserve cursor, ordering, and metadata contracts

#### Scenario: TTL expiry permits periodic rebuild

- GIVEN an index has reached its 15-second TTL and no source change is detected
- WHEN a client requests a listing or count
- THEN the index MAY rebuild, each rebuild unit MUST remain bounded, and the request MUST NOT require a full-file scan

#### Scenario: Cold index build stays far below the unbounded baseline

- GIVEN a project whose index is being built for the first time
- WHEN the index build reads file metadata and bounded content windows instead of full
  file bodies
- THEN the measured cold-build cost MUST be 527 ms, or 88x below the 46,392 ms
  unbounded-scan baseline it replaces

## session-listing-nonblocking-scan

### Requirement: Non-Blocking OpenCode Query

Querying OpenCode sessions MUST NOT block concurrent request handling, including an
in-progress chat SSE stream, for the duration of the query.

#### Scenario: Concurrent SSE stream unaffected

- GIVEN an active chat SSE stream is open
- WHEN a sessions listing request queries OpenCode
- THEN the SSE stream MUST keep delivering events without a multi-second stall
  attributable to the sessions query

## session-listing-robustness

### Requirement: Correct Session-to-Project Attribution

The system MUST attribute a session to a project using exact path matching only and
MUST exclude sibling directories that merely share a name prefix.

#### Scenario: Sibling worktree excluded

- GIVEN project directory `Sistema_de_mantencion` and sibling
  `Sistema_de_mantencion-worktrees/*`
- WHEN sessions are listed for `Sistema_de_mantencion`
- THEN sessions belonging only to the sibling worktree directory MUST NOT be included

#### Scenario: Session without a usable `cwd`

- GIVEN a session record has no `cwd` usable for exact-path comparison
- WHEN matching is attempted
- THEN the system MUST NOT include the session via a fallback comparison that can never
  produce a match; unmatched sessions MUST be excluded, not silently miscounted

### Requirement: Observable Engine Failure

A failure while scanning one engine's sessions MUST be observable and MUST NOT be
indistinguishable from that engine legitimately having zero sessions.

#### Scenario: Failing engine reports failure, not emptiness

- GIVEN the Claude session scan encounters an error for a project
- WHEN the sessions listing request completes
- THEN the failure MUST be recorded/logged such that it is distinguishable from "0
  Claude sessions"

## sessions-screen-single-refresh

### Requirement: Single Initial Load Per Screen Entry

Opening the Sessions screen MUST trigger exactly one sessions-listing request, not
duplicate concurrent requests, while the manual refresh action MUST remain available.

#### Scenario: Single request on screen open

- GIVEN the user navigates to the Sessions screen
- WHEN the screen finishes its initial load
- THEN exactly one sessions request MUST fire (no duplicate `[Sessions DB] Cargadas`
  log lines)

#### Scenario: Manual refresh still works

- GIVEN the Sessions screen is already loaded
- WHEN the user taps manual refresh
- THEN exactly one additional sessions request MUST fire
