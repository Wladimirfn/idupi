# Exploration: Pi Select UI Requests

**Status: Success — dependent Change B boundary extracted from verified Change A evidence.** This artifact preserves the existing Pi decision evidence and the unresolved implementation blockers without rerunning CLI sessions or changing Change A.

## Result first

Change B owns the complete Pi-only `ask_user_question` `select` decision channel: authenticated HTTP/RPC response contract, `PendingSelectRegistry`, deadline cancellation, old-APK behavior, replay/session isolation, Android state/rendering/terminal states, and the existing orphan `sendUiResponse` defect. Change A `live-cli-activity-visibility` owns session-bound authenticated SSE/activity infrastructure and shared event safety; it owns no `ui_request`/`select` code after this split.

No custom Pi extension, fork, configuration mutation, timeout injection, `-e`, `--no-extensions`, or extension filtering is permitted. Cancellation uses documented correlated `extension_ui_response {cancelled:true}`.

## Verified evidence preserved from Change A

### Genuine Pi select and exact-value success cycle

Source: `openspec/changes/live-cli-activity-visibility/exploration.md`, Pi decision capture and RPC cancellation verification; Engram `sdd/live-cli-activity-visibility/explore` (observation #5171). Installed Pi: `@earendil-works/pi-coding-agent` `0.84.0`.

Exact command and boundary:

```text
node <PI_CLI_JS> --mode rpc
```

Normal installed discovery was used: no temporary extension, `-e`, `--no-extensions`, or config mutation. Cwd was the IDUPI repository. The harness sent one harmless prompt requesting exactly one A/B `ask_user_question` select, captured stdout/stderr separately, and cleaned up only after `agent_settled`.

Sanitized decisive excerpt:

```text
{"type":"tool_execution_start","toolName":"ask_user_question"}
{"type":"extension_ui_request","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","method":"select","title":"[Choose] Which option do you choose?","options":["1. A — Choose option A.","2. B — Choose option B.","3. Type something."]}
{"type":"extension_ui_response","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","cancelled":true}
{"type":"tool_execution_end","toolName":"ask_user_question","result":{"content":[{"type":"text","text":"User declined to answer questions"}],"details":{"answers":[],"cancelled":true}},"isError":false}
{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"No selection was made, so I’ll stop here."}]}}
{"type":"agent_end"}
{"type":"agent_settled"}
```

Capture timings (second run): start `2026-08-19T09:30:16.862Z`; select request `9,717 ms`, with no `timeout` field; response `10,470 ms` (`753 ms` later), exactly `{"type":"extension_ui_response","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","cancelled":true}`; tool end `10,474 ms`; resumed text `13,550 ms`; `agent_end` and `agent_settled` `13,556 ms`; post-terminal process check `14,060 ms`, `alive:true`, `exitCode:null`, `killed:false`. Deliberate `SIGTERM` occurred at `14,060 ms` only after terminal state; no orphan remained.

Hashes: stdout SHA-256 `e20dbd1d7d8f8192003271ab5d474c2bce4109a4507f2467d50feb7a69779a3d`; empty stderr SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

The earlier exact-value answer cycle is also retained in Change A: the harness answered the exact rendered option value (not display label), `tool_execution_end` reported `cancelled:false` and answer `A`, then resumed text and emitted `agent_end`/`agent_settled`; corrective stdout hash `8F720E8E476FCEA48474A48034338D92C43C71F35920F8A4E0D231219F72B552B`, empty stderr hash `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.

### Historical unanswered run

This is restored provenance, **not a new run**. Original duration: `300708 ms`; harness timed out waiting for a human answer. No `tool_execution_end` or `agent_end` appeared before cleanup; cleanup left no orphan.

```text
{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","toolCall":{"name":"ask_user_question","arguments":{"questions":[{"question":"Choose exactly A or B before I continue?","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}}}
{"type":"tool_execution_start","toolCallId":"<redacted-id>","toolName":"ask_user_question","args":{"questions":[{"question":"Choose exactly A or B before I continue?","header":"Selection","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}
{"type":"extension_ui_request","method":"select","title":"[Selection] Choose exactly A or B before I continue?","options":["1. A","2. B","3. Type something."]}
```

Historical capture SHA-256: `9f51e3a1ddcd9318bae1a58f1f830142f66f3e999b08d55bcb9d8872b3e9d79f`.

### Installed Pi timeout and cancellation semantics

Directly inspected installed files, not inferred from prompts:

| Evidence | Exact verified fact |
|---|---|
| `.../@earendil-works/pi-coding-agent/package.json:3` | version `0.84.0` |
| `dist/modes/rpc/rpc-mode.js:47-78`, `createDialogPromise` | `setTimeout` exists only inside `if (opts?.timeout)`; absent/undefined means no native timer |
| `rpc-mode.js:84`, `select` | forwards `timeout: opts?.timeout`; auto-resolve default is `undefined` |
| `rpc-mode.js:611-622`, `handleInputLine` | `extension_ui_response` is correlated by exact pending request ID |
| `docs/rpc.md:1150-1153` | timeout behavior is conditional on a dialog `timeout` field |
| `docs/rpc.md:1171-1186` | `select` timeout is milliseconds and native expiry returns `undefined` |
| `idupi-server/index.mjs:3304`, `:1864-1888` | existing `AGENT_CLI_TIMEOUT_MS = 300000` backstop/taskkill remains a last resort |

Therefore Change B must own a positive decision deadline strictly below `300000 ms`, suspend/defer the existing backstop while pending, and resume it after answer/cancel/error. Pi remains authoritative for cancellation meaning and continuation.

### Duplicate runtime blocker

Required reproduction used normal discovery plus a temporary same-name extension, without `--no-extensions`:

```text
node C:\Users\dev\AppData\Roaming\npm\node_modules\@earendil-works\pi-coding-agent\dist\cli.js --extension <TEMP_OPENCODE>\idupi-pi-conflict-extension.mjs --mode rpc
```

Sanitized argv: `["C:\\Program Files\\nodejs\\node.exe","<PI_CLI_JS>","--extension","<TEMP_OPENCODE>\\idupi-pi-conflict-extension.mjs","--mode","rpc"]`; cwd `<REPO>`. Corrected attempt exited during startup with code `1` after `4,224 ms`; stdout was empty, and no UI/tool/agent events occurred.

```text
Error: Failed to load extension "<USER_HOME>\\.pi\\agent\\npm\\node_modules\\@juicesharp\\rpiv-ask-user-question\\index.ts": Tool "ask_user_question" conflicts with <TEMP_OPENCODE>\\idupi-pi-conflict-extension.mjs
Hint: Start without extensions using "pi -ne".
```

Hashes: empty stdout `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`; stderr `1B357E8503DB6E07610EB2EEC943588992F1DEAFE20FE538CB35D77FCC07E037`. The read-only `--exclude-tools ask_user_question` check reproduced the same error/code `1` in `3,660 ms` with the same stderr hash. Verdict: `BLOCKER_REPRODUCED`; runner first-wins is not silent because resource-loader diagnostics make startup fatal. This is why Change B must not add an extension.

### Existing Android scaffolding and defect

Verified orphan scaffolding:

- `app/src/main/java/com/example/idupi/domain/model/UiRequest.kt`: `UiRequest`/`UiRequestMethod` model exists.
- `app/src/main/java/com/example/idupi/ui/components/UiRequestCard.kt:23-114`: renders confirm or select buttons and calls `onResponse`; it has no terminal-state model.
- `app/src/main/java/com/example/idupi/viewmodel/ChatViewModel.kt`: `activeUiRequest` is exposed but no current server event produces `UiRequestReceived` or a request registry.
- `app/src/main/java/com/example/idupi/ui/screens/ChatScreen.kt`: collects `activeUiRequest`, but the visible message list renders `ChatBubble`; no active request card is wired into the screen.
- `app/src/main/java/com/example/idupi/domain/repository/IduPiClient.kt:56`: declares `sendUiResponse(requestId, value)`.

Pre-existing defect: `app/src/main/java/com/example/idupi/data/remote/RealIduPiClient.kt:466-471` posts to `/api/v1/chat/ui-response/$requestId` with JSON content type but sends `setBody(value.toString())`; this is not a JSON `{value: ...}` or `{cancelled:true}` contract and targets a route that does not yet exist in the verified server behavior. Change B owns correcting both contract and client serialization.

## Change B blockers to preserve

1. **Pending registry:** correlate ID, authenticated session, project, `engine=pi-cli`, current Pi session path, exact issued option values, deadline, and terminal tombstone.
2. **Exactly-once terminality:** answer-vs-deadline must be atomic; every answer, deadline, write failure/synchronous throw, child error/close, restart, taskkill, and settled path clears timer/state and publishes one terminal result. Late/duplicate responses must be rejected before stdin; cancellation is never retried.
3. **HTTP/RPC authority:** validate auth/context/JSON/keys/value before stdin; preserve route `POST /api/v1/chat/ui-response/{id}` and distinguish malformed, unauthorized, unknown/wrong-context, terminal, unissued option, and unavailable/write-failure outcomes.
4. **Replay isolation:** pending replay is point-to-subscriber only, after authenticated context snapshot, matching session + project + engine + request ID; mismatched or missing context receives no title/message/options. Android deduplicates replay by ID.
5. **Old APK and terminal UI:** an APK that ignores unknown `ui_request` must still be cancelled by the deadline; Android needs `Map<id, UiRequestState>`, `PENDING|ANSWERED|EXPIRED|ERROR` rendering, disabled terminal controls, and idempotent terminal updates.
6. **Shared infrastructure dependency:** consume Change A's authenticated, session-bound SSE/activity substrate and bounded/redacted event safety; do not duplicate or broaden Change A's activity ownership.

## Evidence manifest

| ID | Provenance | Evidence retained |
|---|---|---|
| B-E1 | Change A exploration lines 286-330; Engram #5171 | real Pi select, correlated `cancelled:true`, exact ID, `753 ms`, cancelled tool result, empty answers, resumed text, agent terminal events, process survival, hashes |
| B-E2 | Change A exploration lines 161-169 | exact rendered-value answer cycle, `cancelled:false`, answer `A`, terminal events, hashes |
| B-E3 | Change A exploration lines 188-202 | historical `300708 ms` unanswered/no-timeout run, raw excerpt, hash |
| B-E4 | Change A exploration lines 171-186 | installed source/docs conditional/undefined timeout semantics and unchanged backstop |
| B-E5 | Change A exploration lines 221-284; Engram #5199 | duplicate runtime exit `1`, `4,224 ms`, stderr excerpt/hash, `--exclude-tools` corroboration |
| B-E6 | CodeGraph current source; Engram #5209 | orphaned Android model/card/ViewModel/ChatScreen scaffolding and `sendUiResponse` serialization/route defect |
| B-E7 | Change A design/spec; Engram #5189, #5204, #5207 | pending/replay/terminal-state blockers, context isolation, route statuses, atomic write/error handling |
| B-E8 | Engram #5213 | explicit A/B split and dependency boundary |

## Result Contract

```yaml
status: success
verdict: PASS
change: pi-select-ui-requests
artifact: openspec/changes/pi-select-ui-requests/exploration.md
engram: sdd/pi-select-ui-requests/explore
source_change: live-cli-activity-visibility
new_cli_sessions: false
change_a_modified: false
production_or_tests_modified: false
proposal_spec_design_tasks_created: false
ready_for_proposal: true
next_recommended: "Owner review, then create the dependent proposal; preserve Change A as the SSE/activity dependency and keep all Pi decision behavior in Change B."
risks:
  - "Pending/replay/session context must never be global or cross-session."
  - "The old APK must not strand Pi; deadline cancellation must be deterministic."
  - "RealIduPiClient serialization and route contract are currently defective/orphaned."
  - "No extension workaround is viable: duplicate ask_user_question startup failure is reproduced."
```

## Key Learnings

1. Pi has no default select timeout when `opts.timeout` is absent; documented correlated cancellation is the verified safe boundary.
2. `cancelled:true` yields typed cancelled tool semantics (`answers:[]`), resumed agent text, terminal events, and a still-alive persistent process.
3. Pending replay must be authenticated and bound to session, project, engine, and request ID; global replay is unsafe.
4. The existing Android UiRequest path is scaffolding, not a producer; Change B must own its state machine and serialization fix.
