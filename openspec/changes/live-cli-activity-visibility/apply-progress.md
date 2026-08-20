# Apply Progress — live-cli-activity-visibility (Work Unit 1 / Slice 1)

- **Change**: live-cli-activity-visibility
- **Slice**: 1 of 3 (owner-resolved: three sequential LOCAL commits on `master`; no remote/main, no push/PR)
- **Mode**: Strict TDD (owner-activated; cached 2026-08-17 node capability note is stale for this slice)
- **Store**: hybrid (OpenSpec tasks.md + this artifact + Engram)
- **Status**: 4/13 tasks complete. Ready for next batch (Work Unit 2) after orchestration checks. NOT verified yet.

## Completed Tasks
- [x] 1.1 RED `test/activity-encode.test.mjs`
- [x] 1.2 Create `lib/activity.mjs`
- [x] 2.1 RED `test/replay-isolation.test.mjs`
- [x] 2.2 Modify `chat-events.mjs`

## TDD Cycle Evidence
| Task | Test File | Layer | RED (written first) | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|---------------------|-------|-------------|----------|
| 1.1 | `test/activity-encode.test.mjs` | Unit | ✅ Written before impl; `ERR_MODULE_NOT_FOUND` (lib/activity.mjs missing) | ✅ 14/14 pass after 1.2 | ✅ 14 cases: redact×7, classifyMcp×4, encode×1, heartbeat/terminal×2 | ✅ Clean |
| 1.2 | (impl; proven by 1.1) | Unit | n/a | ✅ 14/14 | n/a | ✅ Clean |
| 2.1 | `test/replay-isolation.test.mjs` | Unit | ✅ Written before impl; `SyntaxError: does not provide an export named 'enqueueBounded'` | ✅ 6/6 pass after 2.2 | ✅ isolation + overflow + ownership | ✅ Clean |
| 2.2 | (impl; proven by 2.1) | Unit | n/a | ✅ 6/6 | n/a | ✅ Clean |

## Exact RED Evidence
- **1.1**: `node --test idupi-server/test/activity-encode.test.mjs`
  `Error [ERR_MODULE_NOT_FOUND]: Cannot find module 'C:\Users\dev\AndroidStudioProjects\IDUPI\idupi-server\lib\activity.mjs' imported from ...\test\activity-encode.test.mjs` → fails 1, pass 0.
- **2.1**: `node --test idupi-server/test/replay-isolation.test.mjs`
  `SyntaxError: The requested module '../chat-events.mjs' does not provide an export named 'enqueueBounded'` → fails 1, pass 0.

## Exact GREEN / Verification Evidence
- `node --check idupi-server/lib/activity.mjs` → ok
- `node --check idupi-server/chat-events.mjs` → ok
- `node --check idupi-server/test/activity-encode.test.mjs` → ok
- `node --check idupi-server/test/replay-isolation.test.mjs` → ok
- `node --test idupi-server/test/activity-encode.test.mjs` → tests 14, pass 14, fail 0, duration 77ms
- `node --test idupi-server/test/replay-isolation.test.mjs` → tests 6, pass 6, fail 0, duration 73ms
- `node --test idupi-server/test/activity-encode.test.mjs idupi-server/test/replay-isolation.test.mjs` → tests 20, pass 20, fail 0, duration 75ms
- **Mandatory regression** `node --test idupi-server/test/sessions.test.mjs idupi-server/test/sessions-runtime.test.mjs` → tests 65, pass 65, fail 0 (sessions 59 + runtime 6). Unchanged.

## Work Unit Evidence
| Evidence | Value |
|----------|-------|
| Focused test command + exact result | `node --test idupi-server/test/activity-encode.test.mjs idupi-server/test/replay-isolation.test.mjs` → 20 pass / 0 fail. |
| Runtime harness command/scenario + exact result | N/A for this slice — server lib/SSE is unit-tested; the falsifiable unknown-MCP runtime harness is U2 (`harness-unknown-mcp.mjs`). SSE API compatibility is proven by the 65/65 regression (sessions-runtime imports `subscribe/publish/CHAT_EVENTS` from `chat-events.mjs`). |
| Rollback boundary | Revert exactly 4 paths: `idupi-server/lib/activity.mjs` (new), `idupi-server/chat-events.mjs` (modified), `idupi-server/test/activity-encode.test.mjs` (new), `idupi-server/test/replay-isolation.test.mjs` (new). No other file touched; reverts cleanly without removing unrelated work. |

## Files Changed
| File | Action | What Was Done |
|------|--------|---------------|
| `idupi-server/lib/activity.mjs` | Created | `ActivityRegistry` (per-op id, per-inv streamId; operation-owned 15000ms `inflight`-only heartbeat; idempotent `terminalize`), `encodeActivity`, `redactActivity` (bearer→key/token/secret→paths→URL-query, UTF-8 byte cap, <=4096B, malformed dropped), `classifyMcp` (structurally zero-allowlist for claude/opencode/pi), `ACTIVITY_TYPES`. |
| `idupi-server/chat-events.mjs` | Modified | Added `activity_*` handling with per-subscriber server-derived opaque context (never `req.query`), `enqueueBounded` (pure drop-oldest), per-context ring replay buffer, isolated filtered publish (no broadcast; no identity ⇒ nobody). `req.on("close")` clears ONLY subscriber-owned queue + drain listener; the operation-owned registry 15s heartbeat is untouched. Preserved `CHAT_EVENTS`/`subscribe`(2-arg)/`publish`/`subscriberCount` API. |
| `idupi-server/test/activity-encode.test.mjs` | Created | 14 unit tests: redaction, structural MCP, encode identity, heartbeat `inflight`-only + terminal stop, duplicate-start/terminalize idempotency. |
| `idupi-server/test/replay-isolation.test.mjs` | Created | 6 unit tests: same-context receive, wrong project/engine/session ⇒ 0, missing-params ⇒ 0, reconnect re-bind, `enqueueBounded` overflow, explicit proof subscriber disconnect does NOT stop the operation heartbeat. |

## Deviations from Design
None — implementation matches design.md D1/D2/D3/D4/D5/D9 and tasks.md 1.1/1.2/2.1/2.2.

## Issues / Discoveries
- `chat-events.mjs` `subscribe`/`publish`/`CHAT_EVENTS` API is load-bearing for `sessions-runtime.test.mjs` (imports them) and `index.mjs`. Kept exports + 2-arg `subscribe` + non-activity broadcast identical. `req.on("close")` must NOT call `res.removeListener` unconditionally (mocks lack it) → used optional chaining so regression (mock without `removeListener`) and production (real `res`) both work.
- `node --test` hangs if an SSE subscriber stays connected (the 20s `: ping` keepalive `setInterval` keeps the event loop alive). Tests must disconnect subscribers (here via `try/finally`). This is the reason the first 2.1 GREEN run appeared to hang and why unique sessionIds per test + `finally` cleanup were required.

## Remaining Tasks (out of scope for this batch)
3.1, 3.2, 3.3 (Work Unit 2 — engine wiring + unknown-MCP harness); 4.1–4.3, 5.1–5.3 (Work Unit 3 — Android). Do NOT implement here.

## Next Recommended
`apply` (Work Unit 2) after orchestration checks and the single local commit for this slice. This batch is NOT committed.
