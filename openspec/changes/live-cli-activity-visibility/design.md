# Design: Live CLI Activity Visibility (Change A only)

Excludes B `pi-select-ui-requests`: no PendingSelectRegistry/ui_request/response route/UiRequest state+card/decision replay/sendUiResponse. No Sessions, `AGENT_CLI_TIMEOUT_MS`, `taskkill`.

## Seq1: CLI activity publish
```
CLI ──stderr/JSON──▶ runXxxCli / PiRpcManager.handleRpcLine
  redactActivity + MCP classify
  ▼
ActivityRegistry.start/update/terminalize(id,streamId)
  encodeActivity(bounds+redact)
  ▼
publish(type,data) ──filter by context──▶ SSE
  ▼
SseFrameParser ──▶ ChatEvent.*Activity* ──▶ ChatViewModel
```

## Seq2: heartbeat / terminal cleanup
```
start(id): setInterval(15000) ─▶ activity_heartbeat{inflight:true}
end/agent_end/close/parse-error/timeout:
  terminalize(id): terminal=true → clearInterval ONCE → publish ONE terminal
  → tombstone(id,+120000) → entries.delete(id) → writer no-ops
req.on("close"): drop subscriber; clear subscriber queue+timer only (not heartbeat)
```

## D1 Schema & lifecycle
Per op `id`, per invocation `streamId`. Types: `activity_start{engine,kind,name,server?,detail?,startedAt}`, `activity_update{server?,detail?,lastUpdateAt}`, `activity_heartbeat{elapsedMs,sinceLastUpdateMs,inflight:true}`(15000ms), `activity_end{ok,server?,detail?}`, `activity_failure{errorClass:tool|parser|child_error|child_close|restart|kill}`, `activity_timeout`(300000ms watchdog). Epoch-ms; order preserved. Heartbeat reports ONLY `inflight`; terminal stops it; gap ≤20s. `activity_timeout` OBSERVES existing timeout/kill terminal path — unchanged `AGENT_CLI_TIMEOUT_MS`(`index.mjs:3304`) and `taskkill /F /T`(`:1879-1880`) — adds NO competing kill/timer.

## D2 Structural MCP (zero allowlist)
| Engine | Detection |
|---|---|
| Claude | `tool_use.name` matches `^mcp__([^_]+)__(.+)$`; `ToolSearch` generic |
| OpenCode | `toolName` at `tool_execution_start`; `result.details.server` additive end |
| Pi | `statusKey=mcp` → generic MCP; `result.details.server` additive |

Evidence (immutable; no static-scan substitute for runtime absence): Pi `extension_ui_request.method=setStatus,statusKey=mcp`+`tool_execution_end.result.details.server`(exploration "### Pi" L2-L3,136-140); RPC Cancellation PASS, start `2026-08-19T09:30:16.862Z`, stdout `e20dbd1d7d8f8192003271ab5d474c2bce4109a4507f2467d50feb7a69779a3d`, stderr empty `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`. OpenCode `tool_execution_start.toolName`+`tool_execution_end.result.details.server`(exploration "### OpenCode" L2-L3,120-124).

## D3 Redaction (raw stderr+metadata, 3 surfaces)
Replace raw `console.log/error` in `runClaude`,`runOpenCode`,`PiRpcManager.handleRpcLine` with `redactActivity`/bounded stderr. Strip env dumps, tokens, `Authorization`, URL/query secrets, fs/user/profile paths, bodies. Truncate by Unicode points; verify `Buffer.byteLength(utf8)<=cap`; drop malformed/binary; total ≤4096B; never drop ids/enums/timestamps; order bearer→key/token/secret→paths→URL-query. `server-bridge/index.mjs`+terminal-manager stderr SEPARATE, OUT OF SCOPE.

## D4 Authenticated isolation
After `requireAuth`, server generates server-private opaque context key (or keyed digest) from engine+project+current session identity; no raw path/id on wire. Subscriber binds server-side current context snapshot — NEVER `req.query`. Client `?projectId&engine` untrusted hints vs snapshot; mismatch ⇒ reject. Context change invalidates/filters old subscriber+replay. Events tagged same identity; `publish` filters before enqueue/replay; terminal cleanup drops leaked entries.

## D5 Bounded queue
Per-subscriber bounded queue (cap N; overflow drops oldest). `req.on("close")` removes subscriber and clears ONLY subscriber-owned queue + keepalive/drain timer/resources; MUST NOT stop operation-owned `ActivityRegistry` 15s heartbeat (ends only via idempotent `terminalize(id)` on operation terminal cause). `terminalize` clears heartbeat interval once.

## D6 Old APK
`SseFrameParser` `else->null` ignores unknown `activity_*`; old APK alive, no route breakage, no ACK.

## D7 Unknown-MCP harness (A-only)
New `harness-unknown-mcp.mjs`(Node): `mkdtemp <X>/agent`; write `settings.json`+`mcp.json` new MCP name/tool absent production. Auth: IDUPI server gets generated `IDUPI_TOKEN`+free `PORT`; SSE client uses token; `OPENCODE_API_KEY` NEVER IDUPI auth. Pi provider credential only via harness-only provider env in child memory, never copied/mutated/logged vs real `~/.pi/agent/auth.json`; ABORTS pre-launch if absent. Isolation only `PI_CODING_AGENT_DIR=<X>/agent`(no HOME/APPDATA redirection, no second Pi-dir override; verified `node <PI_CLI_JS> --mode rpc`, exploration RPC Cancellation 300-301/conflict repro 241, avoids real session writes(<X>/agent no prior session)). Pre/post SHA-256 of real `~/.pi/agent/{settings,auth,mcp}.json`+production `index.mjs`/`chat-events.mjs` match. `try/finally`+`SIGINT` removes `<X>`, kills tree, deletes marker. SSE proves start/update+terminal, zero detection edits. No extension/fork/node_modules mutation.

## D8 RED first
`activity-encode.test.mjs`(3 stderr paths, secrets/paths/URL-query, Unicode+UTF-8 cap, malformed/binary, structural 3 engines, 15s heartbeat injectable clock, terminal paths, queue overflow, context isolation/reconnect); `replay-isolation.test.mjs`(match-receive, wrong-project/engine/session none, missing-params none, no-broadcast); `harness-unknown-mcp.mjs`(zero-edits, hashes-identical, temp+marker removed, tree-killed); `SseFrameParserTest`/`ChatViewModelTest`(parse/ignore unknown; started→ended, heartbeat open, recent<20s / stale>=20s in-flight; gap<=20s, timeout terminalizes, duplicate id ignored).

## D9 Terminal mapping & single idempotent cleanup
Every terminal cause — `restart`,`kill`,`abort`,`exit`,`error`(errorClass `tool|parser|child_error|child_close|restart|kill`),`timeout` — flows one `terminalize(id)`: `terminal=true`,`clearInterval` ONCE, publish ONE terminal frame, `tombstone(id,+120000)`, `entries.delete(id)`, free subscriber queue/timer. Single idempotent cleanup; no leak on any terminal cause.

## Files (12, A-only)
- `idupi-server/lib/activity.mjs` Create: registry,`terminalize`,`encodeActivity`,`redactActivity`,stderr caps,structural MCP
- `idupi-server/index.mjs` Modify: wire at 3 stderr sites; Pi `statusKey=mcp`/end mapping; context bind
- `idupi-server/chat-events.mjs` Modify: add `activity_*`; per-subscriber context+bounded queue; isolated replay
- `idupi-server/test/activity-encode.test.mjs` Create
- `idupi-server/test/replay-isolation.test.mjs` Create
- `idupi-server/test/harness-unknown-mcp.mjs` Create
- `app/.../domain/model/ChatEvent.kt` Modify: 6 `Activity*` variants
- `app/.../data/remote/SseFrameParser.kt` Modify: activity `when` branches
- `app/.../viewmodel/ChatViewModel.kt` Modify: map activity→UI state
- `app/.../ui/screens/ChatScreen.kt` Modify: render indicator
- `app/src/test/.../SseFrameParserTest.kt` Modify(extend)
- `app/src/test/.../ChatViewModelTest.kt` Modify(extend)

No Sessions, no `UiRequest.kt`/`UiRequestCard.kt`/`RealIduPiClient.sendUiResponse`, no response route, no `AGENT_CLI_TIMEOUT_MS`/`taskkill`.

## Risks / Rollback / Proof
| Risk | Mitigation |
|---|---|
| Cross-session/project leak | Opaque server-derived binding; filter before enqueue/replay; terminal cleanup |
| Redaction bypass | Deterministic redaction at 3 stderr sites + metadata bounds |
| False liveness | Heartbeat only `inflight`; stops at terminal; 15s<20s gap |
| Runtime schema drift | Exact immutable fixtures (Pi stdout/stderr hashes; OpenCode L3 `result.details.server`; Pi setStatus `statusKey=mcp` L2-L3); structural tolerates future literals |
| Harness auth failure | Preflight abort if Pi provider credential absent; `IDUPI_TOKEN`/`PORT` generated server-side; NOT OpenCode key |

Rollback: revert server+Android activity together; old APKs ignore unknown events.

| Layer | Proof |
|---|---|
| Unit | activity-encode, SseFrameParser, ChatViewModel, replay-isolation |
| Integration | structural MCP detection (3 engines) |
| Real SSE | harness proves start/update while running + terminal after result over real Pi MCP |
| Android | parser unknown-event compatibility + ViewModel rendering |
