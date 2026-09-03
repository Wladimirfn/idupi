# Exploration: probe-comment-selection

## Current State

### The Bug
When OpenCode/Pi/Claude asks for a response selection (tool approval, comment, question), the app's selection UI doesn't surface it, leading to OpenCode timeout after 300s (5 minutes).

### Root Cause Chain — FIVE Independent Broken Links

The complete chain from CLI request → user approval → CLI continuation has **five independent failures**, not just one. Every link must be fixed for the feature to work.

---

#### Broken Link 1: Server Never Emits `ui_request` SSE Events

**File**: `idupi-server/chat-events.mjs` (lines 24-35)
**File**: `idupi-server/index.mjs` (line 2230, 2594)

The `CHAT_EVENTS` constant defines every event type the app can receive. There is **NO `ui_request` event type** defined.

Pi CLI emits `extension_ui_request` RPC events (line 2594). The server lists this in `MAPPED_RPC_EVENTS` — but only to suppress "unmapped event" logs. The ONLY actual handler (line 2230) is for MCP status signaling:

```javascript
if (event.type === "extension_ui_request" && event.method === "setStatus" && event.statusKey === "mcp") {
    // marks operation as MCP, nothing else
}
```

**Impact**: The server receives the CLI's request but never forwards it to the app via SSE.

---

#### Broken Link 2: App SSE Parser Has No `ui_request` Case

**File**: `app/src/main/java/com/idupi/app/data/remote/SseFrameParser.kt` (lines 109-214)

The `parseSseEvent()` function maps SSE event names to `ChatEvent` sealed class variants. It handles: `thinking`, `text_delta`, `tool_start`, `tool_end`, `subagent_*`, `message_end`, `engine_changed`, `activity_*`, and `error`.

There is **NO `"ui_request"` case**. Even if the server emitted this event type, the parser would log "Ignoring unknown SSE event type: ui_request" and discard it.

---

#### Broken Link 3: ChatScreen Collects `activeUiRequest` But Never Renders It

**File**: `app/src/main/java/com/idupi/app/ui/screens/ChatScreen.kt` (line 63)

```kotlin
val activeUiRequest by viewModel.activeUiRequest.collectAsState()
```

The variable is collected but **never used anywhere in the UI composition**. There is no dialog, no overlay, no sheet, no card — nothing renders when `activeUiRequest` is non-null.

Additionally, the `ChatBubble` composable in `ChatScreen.kt` (line 568) does NOT have an `onUiResponse` parameter, so even the inline `UiRequestCard` rendering in the old `components/ChatBubble.kt` (line 100) is unreachable from the current chat flow.

---

#### Broken Link 4: Server Has No `/api/v1/chat/ui-response/:requestId` Route

**File**: `idupi-server/index.mjs`

The app's `RealIduPiClient.sendUiResponse()` sends POST to `/api/v1/chat/ui-response/$requestId`. But grep across the entire server finds **ZERO route handlers** for this path. The POST would return 404.

Even if the UI showed the approval dialog and the user tapped "Aceptar", the response would be lost.

---

#### Broken Link 5: CLI Processes Spawned with stdin = "ignore"

**File**: `idupi-server/index.mjs` (lines 3884, 3901, 3908, 4149)
**File**: `idupi-server/lib/agent-cmdline.mjs`

ALL CLI spawns use `stdio: ["ignore", "pipe", "pipe"]`:
- Claude (lines 3884, 3891, 3901, 3908): `stdio: ["ignore", "pipe", "pipe"]`
- OpenCode (line 4149): `stdio: ["ignore", "pipe", "pipe"]`

The first element `"ignore"` means stdin is not connected. There is no pipe to write an approval response back to the CLI process.

Additionally, Claude is spawned with `--permission-mode bypassPermissions` (agent-cmdline.mjs line 20), which auto-approves everything and never asks. OpenCode uses `--auto` (line 28), which may have similar behavior but is not guaranteed to cover all cases (e.g., comment selection, question prompts).

---

### Engine Comparison

| Aspect | Claude | OpenCode | Pi CLI |
|--------|--------|----------|--------|
| Spawn stdio | `["ignore", "pipe", "pipe"]` | `["ignore", "pipe", "pipe"]` | RPC via stdin/stdout pipe |
| Permission mode | `bypassPermissions` | `--auto` | N/A (RPC mode) |
| stdin usable? | No (`"ignore"`) | No (`"ignore"`) | Yes (pipe) |
| Will it ask for approval? | No (bypassed) | Possibly (edge cases) | Yes (extension_ui_request) |
| 300s timeout risk | Low (bypassed) | Medium | High (blocked on approval) |

---

### Existing UI Infrastructure (Already Built, Just Not Wired)

The app already has the full UI infrastructure for approval/selection — it's just not connected:

1. **`UiRequest` data class** (`UiRequest.kt`): `id`, `method` (CONFIRM/SELECT), `title`, `message`, `options`
2. **`UiRequestCard` composable** (`UiRequestCard.kt`): Full UI with Confirm/Reject buttons or option list
3. **`ChatEvent.UiRequestReceived`** (`ChatEvent.kt`): Event variant exists
4. **`ChatViewModel.observeChatEvents()`**: Handler exists (line 344-351) — sets `_activeUiRequest` and appends a system message
5. **`ChatViewModel.respondToUiRequest()`**: Method exists (line 433-445) — calls `client.sendUiResponse()`
6. **`RealIduPiClient.sendUiResponse()`**: HTTP client method exists (line 558-563) — POST to `/api/v1/chat/ui-response/$requestId`
7. **`components/ChatBubble.kt`**: Old composable has `onUiResponse` callback and renders `UiRequestCard`

---

## Affected Areas

- `idupi-server/chat-events.mjs` — missing `UI_REQUEST` event type in CHAT_EVENTS
- `idupi-server/index.mjs` — missing route handler for `/api/v1/chat/ui-response/:requestId`; `extension_ui_request` not forwarded as SSE event
- `app/src/main/java/com/idupi/app/data/remote/SseFrameParser.kt` — missing `"ui_request"` case in `parseSseEvent()`
- `app/src/main/java/com/idupi/app/ui/screens/ChatScreen.kt` — `activeUiRequest` collected but never rendered; ChatBubble missing `onUiResponse` wiring
- `idupi-server/lib/agent-cmdline.mjs` — Claude uses `bypassPermissions`, OpenCode uses `--auto`; neither exposes stdin for approval routing
- `idupi-server/index.mjs` (spawn sections) — `stdio: ["ignore", "pipe", "pipe"]` on all CLI spawns

---

## Approaches

### Approach 1: Server-Side Bypass Approval (Auto-Approve on Server)

**Description**: Intercept `extension_ui_request` events from Pi CLI on the server and automatically respond with a default approval/selection, forwarding the decision as a log entry but never surfacing it to the app.

**Pros**:
- Zero app changes — no APK update needed
- Eliminates the 300s timeout immediately
- Simplest to implement (server-only)
- Preserves existing flows completely

**Cons**:
- User loses the ability to choose/comment/approve — the server decides for them
- Not suitable for cases where user input genuinely matters (e.g., question prompts)
- Masking the real issue rather than solving it

**Effort**: Low

---

### Approach 2: Full Bidirectional UI Request Pipeline

**Description**: Wire all five broken links to create a complete round-trip: CLI → server → SSE → app → approval dialog → HTTP response → server → stdin pipe → CLI.

**Changes required** (5 files):
1. `chat-events.mjs`: Add `UI_REQUEST: "ui_request"` to `CHAT_EVENTS`
2. `index.mjs`: Add route handler for `POST /api/v1/chat/ui-response/:requestId`; forward `extension_ui_request` as SSE `ui_request` event with id/method/title/message/options
3. `SseFrameParser.kt`: Add `"ui_request"` case that decodes payload into `ChatEvent.UiRequestReceived`
4. `ChatScreen.kt`: Render `activeUiRequest` as an `AlertDialog` or overlay when non-null; wire `onUiResponse` callback to `viewModel.respondToUiRequest()`
5. `agent-cmdline.mjs` + spawn sections: Change stdin from `"ignore"` to `"pipe"` for CLI processes that emit approval requests; write approval responses to child.stdin

**Pros**:
- Complete fix — user sees and responds to approval requests
- Enables real interactive CLI sessions from the phone
- Leverages existing UI infrastructure (UiRequestCard, ChatViewModel handler, RealIduPiClient method)

**Cons**:
- Most complex approach — touches both server and app
- Requires APK update
- stdin piping for CLI processes introduces state management complexity
- Risk of deadlock if CLI reads stdin synchronously while server writes asynchronously

**Effort**: High

---

### Approach 3: Hybrid — Server Auto-Approve + App Notification (Recommended)

**Description**: Two-phase approach:
- **Phase A** (server-only, immediate): Auto-approve `extension_ui_request` on the server to prevent 300s timeout. Log the auto-approval. This unblocks OpenCode immediately.
- **Phase B** (server + app, later): Wire the full pipeline but with a twist — instead of piping stdin (complex), have the server emit a `ui_request` SSE event AND accept the app's response via HTTP, but auto-approve with a 30s grace period. If the user responds in time, use their choice; if not, use the default.

**Pros**:
- Phase A is a quick win — server-only, no APK needed
- Phase B gives users choice when they're paying attention, auto-approves when they're not
- Grace period avoids the deadlock risk of stdin piping
- The 300s timeout is eliminated in Phase A itself

**Cons**:
- Phase B still requires app changes (SSE parser + UI rendering + route handler)
- Auto-approve as default means users may miss decisions if not watching
- Two-phase delivery adds coordination overhead

**Effort**: Medium (Phase A: Low, Phase B: Medium)

---

## Recommendation

**Approach 3 (Hybrid)** is recommended:
- Phase A provides immediate relief with zero risk (server-only, auto-approve + log)
- Phase B delivers the full UX without the complexity of stdin piping
- The grace-period pattern is battle-tested (e.g., permission dialogs on Android auto-deny after timeout)

The key insight is that stdin piping (Approach 2, step 5) is the most fragile part — it requires the CLI to read from stdin at exactly the right moment, and both OpenCode and Claude may buffer or block unpredictably. The HTTP-based response + server-side replay to stdin is strictly safer.

---

## Risks

1. **OpenCode `--auto` behavior uncertainty**: It's unclear whether `--auto` already covers all approval scenarios. Some edge cases (comment selection, multi-choice questions) may not be auto-approved even with `--auto`.
2. **Pi CLI `extension_ui_request` event shape**: The exact JSON shape of the approval request from Pi needs to be captured from a live run to map it to `UiRequest` correctly.
3. **Claude `bypassPermissions` scope**: This flag may not cover all Claude Code scenarios (e.g., MCP server approval, custom tool confirmation).
4. **APK update cycle**: Any app-side fix requires a new APK build and distribution, which adds latency to Phase B delivery.
5. **Concurrent UI requests**: If the CLI emits multiple approval requests in sequence, the current `activeUiRequest: MutableStateFlow<UiRequest?>` can only hold one at a time — earlier requests would be lost.

---

## Prior Work Discovery

An existing OpenSpec change `pi-select-ui-requests` already covers the **Pi-only** scope of this bug in detail:

- **`openspec/changes/pi-select-ui-requests/exploration.md`**: 149 lines of verified evidence including real Pi select RPC captures, `extension_ui_request` event shapes, `cancelled:true` semantics, timeout behavior, and the existing Android scaffolding defect.
- **`openspec/changes/pi-select-ui-requests/proposal.md`**: Full proposal for Pi-only `ask_user_question` `select` channel with `PendingSelectRegistry`, deadline cancellation, and old-APK safety.
- **Dependencies**: Explicitly depends on `live-cli-activity-visibility` (Change A) for authenticated session-bound SSE infrastructure.

**This probe CONFIRMS and EXTENDS those findings:**
1. The prior work is accurate — all 5 broken links it identifies are verified.
2. This probe ADDS the Claude/OpenCode dimension — the prior work explicitly scoped those as out-of-scope ("Claude/OpenCode decisions, confirm... out of scope").
3. The 300s timeout root cause is confirmed: `AGENT_CLI_TIMEOUT_MS = 300000` (5 min) kills the process tree when approval blocks.
4. Claude's `bypassPermissions` and OpenCode's `--auto` mean the urgency is primarily for Pi CLI, but edge cases in OpenCode (multi-choice questions, comment selection) may still trigger the bug.

## Ready for Proposal

**Yes** — the exploration is complete with a clear root cause (5 broken links), existing infrastructure (UI components already built), verified prior work (`pi-select-ui-requests`), and a recommended approach (hybrid auto-approve + grace period). The orchestrator should proceed to proposal, building on the existing `pi-select-ui-requests` proposal and extending it to cover OpenCode edge cases.
