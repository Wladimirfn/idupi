# Pi Select UI Requests Specification

## Requirements

### Requirement: Pi-only correlated select
Pi `ask_user_question` with `extension_ui_request.method=select` belongs here. `confirm`, Claude, and OpenCode MUST remain excluded. IDUPI MUST preserve auth, context binding, options, reconnect, and exact values.

#### Scenario: Producer and rendering
- GIVEN Pi `select` with auth context and options
- WHEN IDUPI publishes it
- THEN Android renders `ui_request` with ID, title, message, options, and `SELECT`

### Requirement: Exact authenticated answer
Only an authenticated response bound to a pending request in the active session, project, and Pi engine MAY complete it. A valid answer MUST atomically transition pending→answered and send exactly one same-ID `extension_ui_response` with the exact value to Pi stdin. Unknown, stale, duplicate, wrong-context, and unissued values MUST be rejected before stdin.

#### Scenario: Answer wins
- GIVEN pending request receives a valid answer before its deadline
- WHEN transition wins
- THEN Pi receives `cancelled:false`, resumes, emits `agent_end`/`agent_settled`, and remains alive

### Requirement: Client-owned cancellation deadline
IDUPI MUST maintain one correlated human-decision deadline, positive and strictly below `AGENT_CLI_TIMEOUT_MS=300000ms`. If it wins, IDUPI MUST atomically transition pending→expired, make UI terminal `expired/no selection`, and send exactly one documented `extension_ui_response {id,cancelled:true}` to the same persistent Pi stdin. Pi MUST interpret dismissal as `tool_execution_end` with `cancelled:true` and empty answers (or equivalent undefined), then continue, end, or settle alive.

#### Scenario: Deadline wins
- GIVEN unanswered pending request reaches its deadline
- WHEN expiry wins
- THEN one cancellation is sent, UI has no selection, Pi continues/settles, and no kill occurs

### Requirement: Backstop coordination and failure terminality
While pending, the existing five-minute `AGENT_CLI_TIMEOUT_MS` backstop MUST be suspended/deferred so it cannot taskkill a legitimate question. After answered, expired, cancelled, or terminal error, it MUST resume/reset; the constant and taskkill implementation MUST remain unchanged. If cancellation writing fails, pending MUST become terminal error, the backstop MUST resume, and IDUPI MUST NOT claim acceptance or wait indefinitely.

#### Scenario: Cancellation write failure
- GIVEN deadline wins but persistent stdin write fails
- WHEN recorded
- THEN the request is terminal error, the backstop resumes, and cancellation is not retried

### Requirement: Exactly-once races and compatibility
Answer-versus-deadline resolution MUST be exactly once. Late or duplicate responses MUST be rejected before stdin. An old APK ignoring `ui_request` MUST not leave Pi waiting; IDUPI MUST send deadline cancellation. On SSE reconnect, pending `ui_request` replay MUST go only to an authenticated subscriber whose session, project, engine=`pi-cli`, and request id match the PendingSelectRegistry binding; it MUST be point-to-subscriber, not global broadcast. Reconnect MAY re-emit idempotently, but MUST preserve one terminal transition.

#### Scenario: Boundary race
- GIVEN answer and deadline become ready together
- WHEN the first atomic transition wins
- THEN only answered or expired is recorded, and its corresponding stdin message is sent

#### Scenario: Old APK
- GIVEN an old APK ignores `ui_request`
- WHEN the deadline expires
- THEN IDUPI sends cancellation and Pi reaches documented dismissal without taskkill

#### Scenario: Reconnect
- GIVEN Android/SSE drops while selection is pending
- WHEN the request is re-emitted and one valid answer arrives
- THEN the binding completes once and late responses are rejected before stdin

#### Scenario: Isolated reconnect replay
- GIVEN pending request binds authenticated session, project, engine=`pi-cli`, and request id
- WHEN SSE reconnects with matching, different, or missing subscriber context
- THEN only the matching subscriber receives replay; others MUST receive no replay and no options, title, or message, while Android deduplicates a matching id

### Requirement: Scope and proof boundaries
Implementation MUST NOT add a custom Pi extension, flags, filtering/configuration mutation, fork, dependency, or node_modules edit. Live proof MUST retain Pi select, auth binding, reconnect, exact value, cancellation, terminal UI, transcript/screenshot evidence, process survival, and late-response rejection.

#### Scenario: Proof boundary
- GIVEN live Pi select evidence is captured
- WHEN the evidence is reviewed
- THEN it proves answer/cancellation paths without changing the constant, taskkill behavior, or unrelated engines
