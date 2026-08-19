# Exploration: Live CLI Activity Visibility

**Status: Success — RPC cancellation verified.** The Claude evidence gap and the prior duplicate-tool blocker evidence remain intact. A fresh bounded real Pi 0.84.0 run with normal discovery and the exact RPC command proved that IDUPI can cancel a live `ask_user_question` `select` by correlated `extension_ui_response {cancelled:true}`; Pi reports a cancelled tool result, resumes text generation, emits `agent_end` and `agent_settled`, and remains alive until deliberate post-terminal cleanup.

## Executive Summary

The effective Claude configuration registers exactly three local MCP servers: `codegraph`, `context7`, and `engram`. There is no Playwright entry. A real Claude CLI run first proved the negative case (CodeGraph was requested but not invoked), and a second bounded run invoked Context7 twice through the deferred-tool path. Its raw `assistant.tool_use.name` values were `mcp__context7__resolve-library-id` and `mcp__context7__query-docs`, confirming the structural `mcp__<server>__<tool>` pattern during the activity, before the final result.

The evidence supports a generic rule: detect provider-native structural MCP signals when they appear, expose generic MCP activity immediately when only tool activity is known, and enrich the activity with a server at the first structurally supported point. OpenCode has no server signal at start in the captured raw records and only identifies the server in `result.details.server` at end. Pi has generic MCP status during cold-start through `extension_ui_request.method=setStatus` with `statusKey=mcp`, and identifies the server at end through `result.details.server`. No engine may be classified through a hardcoded tool/server list.

## Current State

### Effective Claude registration

Evidence was read from the real user configuration, with user-specific paths, account identifiers, credentials, and session identifiers excluded from this artifact:

- `~/.claude.json`, `mcpServers`: exactly `codegraph`, `context7`, and `engram`.
- `~/.claude.json`, project entry for `<repo>`: `mcpServers: {}` and `enabledMcpjsonServers: []`; this does not remove the globally registered servers.
- `~/.claude/settings.json`: `permissions.allow` contains `mcp__codegraph__*`; default mode is `bypassPermissions`.
- No `playwright` key exists in the inspected local `mcpServers` object.
- Registered commands, sanitized: `codegraph serve --mcp`; `npx -y --package=@upstash/context7-mcp@2.2.5 -- context7-mcp`; and the local Engram executable with its user path redacted.

The Claude runtime also exposed connected Claude-hosted connectors in its startup inventory. Those are not entries in the inspected local `mcpServers` object and are not used as evidence for the local registration claim.

### Server flow

- `idupi-server/index.mjs` selects one engine in `/api/v1/chat/message`.
- Claude runs `claude --output-format stream-json --verbose --permission-mode bypassPermissions -p ...` through `runClaudeCli`.
- OpenCode runs `opencode run --format json --auto ...` through `runOpenCodeCli`.
- Pi is a persistent `node <PI_CLI_JS> --mode rpc` child process; `PiRpcManager.sendPrompt` waits for `agent_end`.
- Existing server parsing maps selected engine events into `CHAT_EVENTS`; raw unmapped events and stderr are not currently published to Android SSE.
- Android already has generic tool activity and an orphaned `UiRequest` path, but no current server event produces `UiRequestReceived`.

### Ratified Pi decision scope

- The end-to-end decision channel is Pi-only.
- This change handles `method=select` only.
- The response is the correlated `extension_ui_response` with either the exact rendered option value or one documented `cancelled:true` dismissal.
- No custom extension, `-e`, `--no-extensions`, settings/filter logic, or deduplication logic is required for cancellation.
- IDUPI may own the decision deadline and send the documented cancellation response once. The UI/registry timer is authoritative only for **when** the client dismisses; Pi remains authoritative for cancellation meaning and continuation.
- Claude and OpenCode decision paths are explicitly out of scope.

## Capture Method

Claude was executed from `<repo>` using the real CLI command and the exact server mode requested:

```text
claude --output-format stream-json --verbose --permission-mode bypassPermissions -p <read-only MCP prompt>
```

Each attempt wrote stdout JSONL and stderr to separate temporary files. Event types, nested tool-use fields, ordering, timestamps, exit status, latency, and hashes were recorded. The temporary capture directory was outside the repository and was deleted after sanitised excerpts and metadata were recorded. No production or test file was changed.

Maximum attempts were respected: two bounded attempts were made. Attempt 1 targeted CodeGraph; attempt 2 used the different registered Context7 server after attempt 1 produced no MCP invocation.

## Claude Capture Results

### Attempt 1 — CodeGraph requested, no invocation

- Start: `2026-08-18T17:56:49.2198383Z`
- End: `2026-08-18T17:56:56.3215122Z`
- Elapsed: approximately `7,102 ms`; exit `0`; no timeout.
- stdout SHA-256: `379B9FD9E1F4980F730CC7FE4F4FEE905DAF76F42DC6EF1B432B8BD9A3870B10`
- stderr SHA-256: `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` (empty)
- Observed top-level types, first appearance: `system`, `assistant`, `rate_limit_event`, `result`.
- The first `system.subtype=init` contained the runtime tool inventory, then Claude returned ordinary assistant text without an MCP `tool_use`.

Sanitized excerpt:

```text
L1 {"type":"system","subtype":"init","cwd":"<repo>","tools":["..."],"session_id":"<redacted>"}
L2 {"type":"assistant","message":{"content":[{"type":"text","text":"The repo is an Android app (`app/`) paired with a Node.js server (`idupi-server/`, `server-bridge/`)."}]}}
L3 {"type":"result","subtype":"success","is_error":false,"result":"<same summary>"}
```

This is a confirmed negative result only: the prompt did not cause a CodeGraph MCP invocation. It does not refute the configured-server evidence.

### Attempt 2 — Context7 invoked successfully

- Start: `2026-08-18T18:02:15.4993665Z`
- End: `2026-08-18T18:02:35.6447191Z`
- Elapsed: approximately `20,145 ms`; exit `0`; no timeout.
- stdout SHA-256: `BB7ECB79C7DF4636540A1AC5303CA6C43014DC355FA61923ABB0625C2836154B`
- stderr SHA-256: `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` (empty)
- Observed top-level types, first appearance: `system`, `assistant`, `user`, `rate_limit_event`, `result`.
- The first MCP-related action was a `ToolSearch` lookup because Context7 tools were deferred. The actual MCP calls followed in `assistant` `message.content[]` as `type=tool_use`.
- The actual MCP tool-use fields were `type`, `id`, `name`, `input`, and `caller`; `tool_use_meta` also carried `display_name` and `server_display_name` on the actual Context7 calls.

Sanitized excerpt, preserving order:

```text
L1  {"type":"system","subtype":"init","cwd":"<repo>","tools":["..."],"session_id":"<redacted>"}
L2  {"type":"assistant","message":{"content":[{"type":"tool_use","name":"ToolSearch","input":{"query":"context7 documentation lookup","max_results":5}}]}}
L3  {"type":"user","message":{"content":[{"type":"tool_result","content":[{"type":"tool_reference","tool_name":"mcp__context7__query-docs"}]}]}}
L4  {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__context7__resolve-library-id","input":{"libraryName":"Node.js","query":"JSON.parse error handling"}}]},"tool_use_meta":[{"server_display_name":"Context7"}]}
L5  {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"<redacted>","content":"<Node.js library resolution>"}]}}
L6  {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__context7__query-docs","input":{"libraryId":"/nodejs/node","query":"JSON.parse error handling"}}]},"tool_use_meta":[{"server_display_name":"Context7"}]}
L7  {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"<redacted>","content":"<documentation excerpt>"}]}}
L8  {"type":"assistant","message":{"content":[{"type":"text","text":"Node.js documents ... JSON.parse ... try/catch ..."}]}}
L9  {"type":"result","subtype":"success","is_error":false,"result":"<same summary>","duration_ms":16185}
```

### Claude pattern verdict

The pattern `mcp__<server>__<tool>` is confirmed in the actual `assistant.tool_use.name` field. It is available at the start of each actual MCP invocation, before the matching `user.tool_result` and before the final `result`; it is not merely a final-result artifact. The initial `system.subtype=init` inventory did not expose Context7 in its first non-truncated local MCP list because these tools were deferred, so an implementation must also tolerate a preceding generic `ToolSearch` event. Detection must use the structural prefix and provider fields, never the observed server or tool literals.

## Existing OpenCode and Pi Evidence Re-evaluation

No OpenCode or Pi session was repeated. The existing raw excerpts contain the fields required for this decision.

### OpenCode

- Start/activity: `tool_use` has `part.type=tool`, `part.tool`, `part.state.status`, and input. `tool_execution_start` has `toolCallId`, `toolName`, and `args`; no server field was present.
- End: `tool_execution_end.result.details.server` identifies the provider generically; the same object also contained `details.tool`.
- Cold-start status: no provider-native generic MCP status signal was captured before or during the first activity.
- Provider identification: use the structural `result.details.server` at end; do not infer it from `toolName`.
- Limitation: if `details.server` appears only at end, the UX can show generic tool activity at start and enrich it with MCP/provider information later. It must not claim the provider early.

Sanitized existing excerpt:

```text
L1 {"type":"tool_use","part":{"type":"tool","tool":"<redacted-tool>","state":{"status":"completed","input":{},"output":"<redacted>"}}}
L2 {"type":"tool_execution_start","toolCallId":"<redacted>","toolName":"<redacted-tool>","args":{}}
L3 {"type":"tool_execution_end","toolCallId":"<redacted>","toolName":"<redacted-tool>","result":{"details":{"server":"<server>","tool":"<provider-tool>"}},"isError":false}
```

### Pi

- Start/activity: `tool_execution_start` has `toolCallId`, `toolName`, and `args`; it did not contain `result.details.server`.
- Cold-start status: `extension_ui_request.method=setStatus` with `statusKey=mcp` and a `statusText` such as `MCP: 7 servers enabled (1 connected)` provides generic MCP progress/status. In the captured order it followed `tool_execution_start` and preceded `tool_execution_end`.
- End: `tool_execution_end.result.details.server` identifies the server generically; `details.tool` is provider metadata.
- Provider identification: use the provider-native status key while only generic status is available, then use `result.details.server` for server enrichment at end.
- Limitation: `tool_execution_start` alone does not identify the server. A UI that requires a server label before end must remain generic.

Sanitized existing excerpt:

```text
L1 {"type":"tool_execution_start","toolCallId":"<redacted>","toolName":"<redacted-tool>","args":{}}
L2 {"type":"extension_ui_request","method":"setStatus","statusKey":"mcp","statusText":"MCP: <count> servers enabled (<count> connected)"}
L3 {"type":"tool_execution_end","toolCallId":"<redacted>","toolName":"<redacted-tool>","result":{"details":{"server":"<server>","tool":"<provider-tool>"}},"isError":false}
```

## Generic Detection Rule

The rule proposed by exploration is provider-structural:

1. **Claude:** classify an MCP activity when an actual tool-use name has the provider's confirmed structural `mcp__<server>__<tool>` form. Prefer `tool_use_meta.server_display_name` when present; retain the complete structural name and `tool_use_id` for correlation. Treat `ToolSearch` as generic deferred-tool activity, not as proof of MCP execution.
2. **OpenCode:** classify generic tool activity at `tool_execution_start`/`tool_use`; classify or enrich it as MCP when `result.details.server` appears at end.
3. **Pi:** classify generic MCP status from `extension_ui_request.method=setStatus` plus `statusKey=mcp`; enrich the completed activity from `result.details.server`.
4. **All engines:** preserve unknown provider-native fields as bounded, redacted metadata. Never compare against `playwright_browser_navigate`, `browser_navigate`, `playwright`, `codegraph`, `context7`, `engram`, or any future literal allowlist.

This gives immediate honest UX: generic activity before provider identity, then provider/server enrichment when the engine supplies it. It avoids the false claim that a server is known at start when the raw protocol does not provide it.

## MCP Start / End / Status Matrix

| Engine | Start signal available | End signal available | Generic cold-start status | Provider/server identification | Limitation |
|---|---|---|---|---|---|
| Claude | `assistant.message.content[].type=tool_use` with structural `name=mcp__<server>__<tool>`; optional `tool_use_meta.server_display_name` is already present on the captured call | Matching `user.tool_result` confirms completion of that tool use, but does not add a new server field; final `result` is aggregate | Deferred `ToolSearch` is generic only; no separate MCP status event captured | Structural tool name and optional `tool_use_meta.server_display_name` | Initial `system.init` may omit deferred MCP tools; do not require startup inventory membership |
| OpenCode | `tool_use` / `tool_execution_start` exposes tool activity, call ID, and args, but no server in the raw start record | `tool_execution_end.result.details.server` and `.tool` | None captured | `result.details.server` at end | Server identity may be unavailable until completion; UX must remain generic first |
| Pi | `tool_execution_start` exposes tool activity, call ID, and args, but no server in the raw start record | `tool_execution_end.result.details.server` and `.tool` | `extension_ui_request.method=setStatus`, `statusKey=mcp`, `statusText` | Status key for generic provider status, then `result.details.server` at end | Status is generic and server identity is delayed; status appeared after start in the capture |

## Pi Decision Scope

The captured Pi cycle remains valid and is deliberately retained as a separate decision path:

- `extension_ui_request.method=select` carried a correlated request ID and rendered option values.
- The harness returned `extension_ui_response` with the exact rendered first option value, not the display label alone.
- `tool_execution_end` reported `cancelled:false` and answer `A`, followed by resumed text, `agent_end`, and `agent_settled`.
- Existing evidence hashes: corrective Pi stdout `8F720E8E476FCEA48474A48034338D92C43C71F35920F8A4E0D231219F72B552B`; stderr empty hash `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.
- This exploration does not connect the Android `UiRequest` path, change production timeout behavior, or add Claude/OpenCode decisions.

## Pi Timeout Defect Verification

The installed provider was inspected directly, not inferred from the prompt. User-specific filesystem prefixes are sanitized below as `<USER_HOME>`; repository paths are shown as `<REPO>`.

### Exact installed source and documentation evidence

| Claim | Verified evidence |
|---|---|
| Installed version is `0.84.0` | `<USER_HOME>/AppData/Roaming/npm/node_modules/@earendil-works/pi-coding-agent/package.json:3`, package field `version` is `"0.84.0"`. |
| Dialog timer is conditional | `dist/modes/rpc/rpc-mode.js:47-78`, symbol `createDialogPromise`: `let timeoutId` is declared, but `setTimeout` is created only inside `if (opts?.timeout)` at lines 64-68. No timeout is created when the option is absent or undefined. |
| `select` forwards the provider-native timeout and defaults to `undefined` | `dist/modes/rpc/rpc-mode.js:84`, `createExtensionUIContext().select`: calls `createDialogPromise(opts, undefined, { method: "select", title, options, timeout: opts?.timeout }, ...)`. The second argument is the auto-resolve default `undefined`, and the forwarded request field is `opts?.timeout`. |
| Responses are correlated by request id | `dist/modes/rpc/rpc-mode.js:611-622`, symbol `handleInputLine`: accepts `type === "extension_ui_response"`, looks up `pendingExtensionRequests.get(response.id)`, and resolves only that matching pending request. The request id is generated and emitted at lines 50 and 77. |
| Documentation declares conditional timeout behavior | `<USER_HOME>/AppData/Roaming/npm/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md:1150-1153` says dialog methods block for a matching response and that, **if a dialog method includes a `timeout` field**, the agent auto-resolves on expiry. The `select` contract at lines 1171-1186 says the timeout is included in milliseconds and the result is `undefined` on native expiry. |
| IDUPI's last-resort backstop remains five minutes | `<REPO>/idupi-server/index.mjs:3304`, constant `AGENT_CLI_TIMEOUT_MS = 5 * 60 * 1000`; `PiRpcManager.sendPrompt` arms the persistent-process backstop at lines 1864-1888 and kills the process tree with `taskkill /F /T` at lines 1879-1880. |

**Verified conclusion:** Pi has no default `select` timeout, and its documented cancellation response is sufficient to terminate the pending decision without a custom extension. On `cancelled:true`, Pi's `select` parser returns `undefined`; the installed `rpiv-ask-user-question` path converts that into a typed cancelled tool result (`details.cancelled:true`, empty answers), then the agent continues and settles. An unanswered human decision must therefore suspend/defer the existing `AGENT_CLI_TIMEOUT_MS` timer and resume it only after answer/cancel terminal; the constant and taskkill backstop remain unchanged. The answer-vs-deadline transition must be atomic, and a late deadline response must be rejected before stdin after the decision is already terminal.

## Restored Historical Evidence: First Exploration Capture

The following evidence is restored from the first exploration capture. It is historical provenance, **not a newly regenerated run**. The original capture recorded a Pi decision attempt lasting `300708 ms`; the harness timed out while waiting for an answer. No `tool_execution_end` or `agent_end` appeared before cleanup, and cleanup left no orphaned process.

The original sanitized raw excerpts were:

```text
{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","toolCall":{"name":"ask_user_question","arguments":{"questions":[{"question":"Choose exactly A or B before I continue?","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}}}
{"type":"tool_execution_start","toolCallId":"<redacted-id>","toolName":"ask_user_question","args":{"questions":[{"question":"Choose exactly A or B before I continue?","header":"Selection","options":[{"label":"A"},{"label":"B"}],"multiSelect":false}]}}
{"type":"extension_ui_request","method":"select","title":"[Selection] Choose exactly A or B before I continue?","options":["1. A","2. B","3. Type something."]}
```

Original sanitized capture SHA-256: `9f51e3a1ddcd9318bae1a58f1f830142f66f3e999b08d55bcb9d8872b3e9d79f`.

This historical record is consistent with the installed source: the emitted `select` request contained no `timeout` field, so Pi created no native timer. It must not be described as a new empirical run or as evidence that Pi has a default timeout.

## Risks

- Provider metadata and raw tool arguments can expose credentials, filesystem paths, URLs, or private content; bounds and redaction are mandatory.
- Claude deferred-tool loading adds a generic `ToolSearch` event before the actual MCP invocation.
- OpenCode and Pi cannot honestly show server identity at tool start from the captured records.
- Generic MCP status must not be confused with a completed tool result.
- Pi's persistent process lifetime is distinct from `agent_end` and `agent_settled`; explicit native select expiry must occur before the unchanged five-minute process backstop.
- A native select expiry resolves to `undefined`; terminal UI and agent-visible handling must be specified before implementation.
- Existing Android `UiRequest` scaffolding is not evidence of a producer and must remain disconnected until its Pi-only contract is specified.

## No-Change and Cleanup Verification

- Only `openspec/changes/live-cli-activity-visibility/exploration.md` was changed permanently by this exploration.
- No production code, tests, session routes, timeout constants, or process-kill guards were changed.
- Claude stdout and stderr were captured separately; both attempts exited `0` without timeout and stderr was empty.
- Temporary Claude capture files and harness state were deleted after hashes and sanitised excerpts were recorded.

## Pi Duplicate Tool Conflict: Bounded Runtime Reproduction

### Source reconciliation

The installed source resolves the apparent contradiction precisely:

1. `dist/core/extensions/loader.js:createExtensionAPI().registerTool` stores tools in the registering extension's own `Map`; a second same-name registration inside one extension replaces that entry.
2. `dist/core/extensions/runner.js:ExtensionRunner.getAllRegisteredTools` explicitly implements **first registration per name wins** while flattening tools across extensions.
3. `dist/core/resource-loader.js:addExtensionConflictDiagnostics` scans all loaded extensions, detects duplicate tool names, and appends `{ path, error: 'Tool "<name>" conflicts with <existingPath>' }` to `extensionsResult.errors`. Its comment says extensions remain loaded and precedence is load-order based.
4. `dist/main.js:createRuntime` maps every `extensionsResult.errors` entry to an error diagnostic with `Failed to load extension ...`; after reporting diagnostics, `main.js` exits with code `1` when any error diagnostic exists and prints the extension-load hint.

Therefore the gate was **correct** to treat the duplicate as a startup blocker, but “first-registration-wins silently” is incomplete: first-wins exists as an in-memory runner rule only after the resource-loader diagnostic gate. It does not permit the process to start silently.

The conflict is specifically a **duplicate tool-name conflict**, not extension identity, import/transpilation, missing path, or generic extension-load failure. The installed `@juicesharp/rpiv-ask-user-question` package is version `1.13.0`, exports a valid factory from `index.ts`, and registers `ask_user_question` with `QuestionParamsSchema`; its execute path uses `ctx.ui.custom`, so the temporary provider-supported extension was independently schema-compatible and used `ctx.ui.select` with a positive timeout.

### Real harness and exact invocation

Temporary files were created only under `<TEMP_OPENCODE>` and were not part of the repository. The provider-supported extension registered the same name, used sentinel `IDUPI_TEMP_OVERRIDE`, and its execute function called `ctx.ui.select("IDUPI_TEMP_OVERRIDE", ["A", "B"], { timeout: 3000 })` before returning a sentinel result containing the selected value. The real Pi command used normal discovery and did not use `--no-extensions`:

```text
node C:\Users\dev\AppData\Roaming\npm\node_modules\@earendil-works\pi-coding-agent\dist\cli.js --extension <TEMP_OPENCODE>\idupi-pi-conflict-extension.mjs --mode rpc
```

Sanitized exact argv JSON:

```json
["C:\\Program Files\\nodejs\\node.exe","<PI_CLI_JS>","--extension","<TEMP_OPENCODE>\\idupi-pi-conflict-extension.mjs","--mode","rpc"]
```

Cwd was `<REPO>`. The harness sent one inocuous RPC prompt requesting exactly one A/B `ask_user_question` call and sent no response to any UI request.

### Attempts and raw evidence

Attempt 1 used the same exact Pi command and a valid temporary extension, but the harness incorrectly waited for a startup JSONL event before sending its RPC prompt. Pi emitted no startup JSONL before the harness's 9-second bounded cleanup; stdout and stderr were empty and the harness terminated the still-running child with `SIGTERM`. This was a harness transport mistake, not an extension failure.

Attempt 2 corrected the harness once by sending the RPC prompt immediately after spawn. Pi then exited during startup with code `1` after `4,224 ms`.

Raw stderr excerpt, complete for the non-empty stream:

```text
Error: Failed to load extension "<USER_HOME>\\.pi\\agent\\npm\\node_modules\\@juicesharp\\rpiv-ask-user-question\\index.ts": Tool "ask_user_question" conflicts with <TEMP_OPENCODE>\\idupi-pi-conflict-extension.mjs
Hint: Start without extensions using "pi -ne".
```

Raw stdout was empty. No `extension_ui_request`, no `timeout:3000`, no sentinel result, no `tool_execution_end`, no `agent_end`, and no `agent_settled` were emitted because startup failed before the prompt could execute. The temporary implementation therefore did not win; there is no effective runtime winner.

Attempt 2 capture metadata:

- Sanitized argv: the JSON shown above.
- Elapsed: `4,224 ms`.
- Exit: `1` (startup failure; not deliberate post-settled cleanup).
- stdout SHA-256: `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` (empty).
- stderr SHA-256: `1B357E8503DB6E07610EB2EEC943588992F1DEAFE20FE538CB35D77FCC07E037`.
- Captures were written temporarily as `stdout.jsonl` and `stderr.txt` under `<TEMP_OPENCODE>\\idupi-pi-conflict-captures`.

### Read-only `--exclude-tools` check

Because normal startup did not reach first-wins execution, the optional read-only check was run with the same extension and `--exclude-tools ask_user_question`. It produced the identical duplicate-tool startup error, code `1`, in `3,660 ms`; stdout remained empty and stderr had the same SHA-256. This proves `--exclude-tools` filters tool availability after extension loading/diagnostics; it does not prevent duplicate-name conflict detection and does not eliminate both registrations at the gate.

### Cleanup and verdict

The persistent Pi child was deliberately terminated only after the bounded observation window in Attempt 1; Attempt 2 exited naturally with code `1`. The temporary extension, runner, and capture directory were removed after hashes and sanitized excerpts were recorded. A post-cleanup process inspection found no remaining Pi CLI or harness process. No repository file other than this `exploration.md` was changed.

**Explicit verdict: `BLOCKER_REPRODUCED`.** Pi 0.84.0 does contain first-registration-wins in `getAllRegisteredTools`, but duplicate tool registration is not silently accepted: `resource-loader.js` appends a duplicate-tool error to `extensionsResult.errors`, `main.js` promotes it to an error diagnostic, and startup exits with code `1` before any `ask_user_question` implementation can execute.

## Pi RPC Cancellation Response Verification

### Source and documentation proof

The installed package is `@earendil-works/pi-coding-agent` `0.84.0`. Direct inspection retained the following evidence:

- `docs/rpc.md:1150-1153` documents that dialog methods block for a matching response and that `timeout` is conditional; `:1171-1186` documents `select` response values or `cancelled:true`.
- `dist/modes/rpc/rpc-mode.js:47-78` creates the pending request and parses the response; `:84` maps a cancelled `select` response to `undefined`.
- `dist/modes/rpc/rpc-mode.js:601-622` parses `extension_ui_response`, looks up the exact request ID, resolves it, and ignores an ID that is no longer pending.

### Real bounded runtime

The proof used the normal installed discovery set, no temporary extension, no `-e`, no `--no-extensions`, and exactly:

```text
node <PI_CLI_JS> --mode rpc
```

The cwd was the IDUPI repository. The harness sent one harmless prompt requesting exactly one real A/B `ask_user_question` select, captured JSONL stdout and stderr separately, sent exactly one cancellation after a deterministic 750 ms delay, and cleaned up only after `agent_settled`.

Second-run capture metadata (the first bounded run is also retained in the tool capture history; it used the same protocol and required the outer deadline because Pi has no `shutdown` RPC command):

- Start: `2026-08-19T09:30:16.862Z`.
- `extension_ui_request.method=select`: `9,717 ms`; request ID `e39b608a-dbde-4b49-ae3d-d87fc73791cf`; options were `1. A — Choose option A.`, `2. B — Choose option B.`, and the provider-added `3. Type something.`; no `timeout` field was present.
- Client response: `10,470 ms` (`753 ms` after request), exactly `{"type":"extension_ui_response","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","cancelled":true}`.
- `tool_execution_end`: `10,474 ms`, `isError:false`, `result.details.answers:[]`, `result.details.cancelled:true`, content `User declined to answer questions`.
- Resumed text: `13,550 ms`, exact text `No selection was made, so I’ll stop here.`
- `agent_end`: `13,556 ms`; `agent_settled`: `13,556 ms`.
- Post-terminal survival: `14,060 ms`, `alive:true`, `exitCode:null`, `killed:false`.
- Cleanup: deliberate `SIGTERM` at `14,060 ms`, after terminal; the subsequent process inspection found no Pi or harness orphan. This is recorded as cleanup behavior, not an agent failure.
- stdout SHA-256: `e20dbd1d7d8f8192003271ab5d474c2bce4109a4507f2467d50feb7a69779a3d`; stderr SHA-256: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (empty).

Raw sanitized excerpt, preserving the decisive order:

```text
{"type":"tool_execution_start","toolName":"ask_user_question"}
{"type":"extension_ui_request","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","method":"select","title":"[Choose] Which option do you choose?","options":["1. A — Choose option A.","2. B — Choose option B.","3. Type something."]}
{"type":"extension_ui_response","id":"e39b608a-dbde-4b49-ae3d-d87fc73791cf","cancelled":true}
{"type":"tool_execution_end","toolName":"ask_user_question","result":{"content":[{"type":"text","text":"User declined to answer questions"}],"details":{"answers":[],"cancelled":true}},"isError":false}
{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"No selection was made, so I’ll stop here."}]}}
{"type":"agent_end"}
{"type":"agent_settled"}
```

**Runtime verdict: `PASS`.** The response was accepted and correlated; Pi interpreted dismissal as `undefined`, the actual tool path emitted typed cancellation semantics, and the agent continued normally. No inference was used for rejected or failed behavior because neither occurred. The proof supports a single documented IDUPI deadline cancellation, with an unanswered human decision suspending/deferring the existing backstop timer, an atomic answer-vs-deadline transition, and late-response rejection before stdin.

## Ready for Proposal

**Yes.** The Claude gap and duplicate-registration blocker remain evidenced, and the new real Pi RPC proof establishes `PASS` for correlated `cancelled:true`: Pi emits typed cancellation semantics, resumes text, reaches `agent_end`/`agent_settled`, and keeps the persistent process alive until deliberate cleanup. Next design work should remove the custom timeout-extension approach and specify the IDUPI deadline gate, timer suspension/resumption, atomic race handling, and late-response rejection.

## Result Contract

```yaml
status: success
verdict: PASS
executive_summary: "A fresh bounded Pi 0.84.0 RPC run with normal discovery and no temporary extension emitted a real select, accepted exactly one correlated extension_ui_response with cancelled:true after 753 ms, produced tool_execution_end details.cancelled:true with empty answers, resumed text, emitted agent_end and agent_settled, and remained alive until deliberate post-terminal SIGTERM cleanup."
artifacts:
  - openspec/changes/live-cli-activity-visibility/exploration.md
  - engram:sdd/live-cli-activity-visibility/explore
next_recommended: "After owner review, revise the Pi strategy to use one IDUPI-owned cancellation deadline with no custom extension, suspend/defer AGENT_CLI_TIMEOUT_MS while a human decision is pending, resume it after answer/cancel terminal, make answer-vs-deadline atomic, and reject late responses before stdin. Do not create spec, design, or tasks in this phase."
risks:
  - "OpenCode and Pi server identity is unavailable at tool start in the captured raw records."
  - "Claude deferred-tool lookup must not be mistaken for MCP execution."
  - "Raw metadata forwarding requires strict redaction and bounds."
  - "Pi 0.84.0 duplicate tool registration is a reproduced startup blocker despite runner-level first-registration-wins precedence."
  - "Pi select has no default timeout, but documented correlated cancelled:true is sufficient for client-owned deadline dismissal."
  - "The installed rpiv path turns cancelled select into empty answers plus details.cancelled:true, then Pi resumes and settles."
skill_resolution:
  - "sdd-explore: loaded from the injected exact path and followed"
  - "cognitive-doc-design: loaded from the injected exact path and applied"
   - "Pi source/docs inspected directly at sanitized user paths; no claim was inferred from the prompt"
   - "Real Pi cancellation proof executed with exact node <PI_CLI_JS> --mode rpc and a post-terminal orphan check"
```

## Key Learnings

1. Claude deferred MCP tools expose structural names only when the actual tool-use event is emitted.
2. OpenCode server identity appears at completion, not in the captured start metadata.
3. Pi provides generic MCP status before completion but delays server identity until result details.
4. Structural provider signals prevent future MCP integrations from requiring tool-name allowlists.
