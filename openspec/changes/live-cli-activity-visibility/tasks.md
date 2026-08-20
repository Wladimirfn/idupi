# Tasks: Live CLI Activity Visibility (Change A)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Lines | ~650-900 |
| Risk | High |
| Chained | Yes |
| Delivery | auto-chain |
| Chain | pending |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

Orchestrator must collect chain strategy (stacked-to-main | feature-branch-chain) before apply; no split decision gate.

### Work Units (3 cohesive slices)

| U | PR | Goal | Focused test | Runtime harness | Rollback |
|---|----|------|--------------|-----------------|----------|
| 1 | 1 | Server registry + SSE isolation | `node --test test/activity-encode.test.mjs test/replay-isolation.test.mjs` | N/A (lib/SSE; proven by U2 harness) | `lib/activity.mjs`,`chat-events.mjs` |
| 2 | 2 | Engine wiring + unknown-MCP harness | `node --test test/activity-encode.test.mjs test/replay-isolation.test.mjs` + `node test/harness-unknown-mcp.mjs` | Pi MCP SSE start/update+terminal, pre/post SHA-256, cleanup | `index.mjs`,`harness-unknown-mcp.mjs` |
| 3 | 3 | Android parser+VM+UI | `./gradlew :app:testDebugUnitTest` | app run: long op shows heartbeat (recent<20s / stale>=20s) | `ChatEvent.kt`,`SseFrameParser.kt`,`ChatViewModel.kt`,`ChatScreen.kt` |

## Phase 1: Server lib + RED encode (files 1,4)

- [x] 1.1 RED `test/activity-encode.test.mjs`: secrets/`Authorization`/URL-query/paths stripped; UTF-8 `Buffer.byteLength<=cap`, malformed dropped, <=4096B; Claude `mcp__s__t` MCP, `ToolSearch` generic; OpenCode `toolName`+additive `server`; Pi `statusKey=mcp` generic+additive; injectable 15s heartbeat `inflight:true` only; terminal stops it.
- [x] 1.2 Create `lib/activity.mjs`: `ActivityRegistry`(per-op `id`, per-inv `streamId`); operation-owned 15000ms heartbeat(`inflight` only); `encodeActivity`; `redactActivity`(bearer→key/token/secret→paths→URL-query, UTF-8 cap); structural MCP classify; idempotent `terminalize(id)`(terminal=true, clearInterval ONCE, ONE frame, tombstone+120000ms, delete).

## Phase 2: SSE isolation RED + production (files 3,5)

- [x] 2.1 RED `test/replay-isolation.test.mjs`: same opaque-bound sub receives; wrong-project/engine/session → 0; missing params → 0; no broadcast; reconnect re-binds context; queue overflow drops oldest deterministically, preserves newest+order, never leaks across wrong project/engine/session.
- [x] 2.2 Modify `chat-events.mjs`: add `activity_*`; per-sub server-derived opaque context(never `req.query`); bounded queue(cap N, drop oldest); isolated replay by context; `req.on("close")` clears ONLY subscriber-owned queue+keepalive/drain timer/resources — MUST NOT stop operation-owned `ActivityRegistry` 15s heartbeat (ends only via idempotent `terminalize(id)` on operation terminal cause).

## Phase 3: Engine wiring + harness (files 2,6)

- [ ] 3.1 RED `test/harness-unknown-mcp.mjs`: zero detection-code edits; pre/post SHA-256 of `~/.pi/agent/{settings,auth,mcp}.json`+`index.mjs`/`chat-events.mjs` identical; temp+marker removed; tree killed.
- [ ] 3.2 Modify `index.mjs`: replace raw `console.log/error` at runClaude/runOpenCode/PiRpcManager.handleRpcLine with `redactActivity`/bounded stderr; Pi `statusKey=mcp`+additive `result.details.server` end; context bind after `requireAuth`.
- [ ] 3.3 Implement `harness-unknown-mcp.mjs`: `mkdtemp <X>/agent`; new MCP name/tool; generated `IDUPI_TOKEN`+free `PORT`; SSE token client; provider-auth preflight abort if Pi credential absent; isolation `PI_CODING_AGENT_DIR=<X>/agent` ONLY; pre/post hashes; `try/finally`+`SIGINT` removes `<X>`, kills tree, deletes marker; SSE proves start/update+terminal.

## Phase 4: Android model + parser (files 7,8,11)

- [ ] 4.1 RED extend `SseFrameParserTest.kt`: parse 6 `Activity*`; ignore unknown(old APK alive, no ACK); started→ended; heartbeat open; recent<20s / stale>=20s in-flight; gap<=20s; timeout terminalizes; duplicate id ignored.
- [ ] 4.2 Modify `ChatEvent.kt`: add 6 `Activity*`(stable `id`+`streamId`, `elapsedMs`/`sinceLastUpdateMs`, `inflight`).
- [ ] 4.3 Modify `SseFrameParser.kt`: `when` decodes `activity_*`; `else->null` ignores unknown.

## Phase 5: Android VM/UI (files 9,10,12)

- [ ] 5.1 RED extend `ChatViewModelTest.kt`: activity→UI; heartbeat open; recent<20s / stale>=20s in-flight; gap<=20s; timeout terminalizes; duplicate ignored.
- [ ] 5.2 Modify `ChatViewModel.kt`: map activity→UI(in-flight/recent/stale/terminal).
- [ ] 5.3 Modify `ChatScreen.kt`: render indicator(stable id, elapsed, recent/stale/terminal).
