# Phone agent bubble (phase 2, blocks B2 + E)

Locator: `odd/tasks/phone-bubble.md` · Engram mirror: `odd/phone-bubble/tasks`
Branch: `feat/phone-bubble` (stacked on `feat/phone-approvals`, PR #28)

## Objective

A floating IDUPI bubble on the phone, over any app, where the owner types or
dictates a request ("revisa mi correo", "abre WhatsApp y dime qué me escribió
Juan", "agenda reunión mañana a las 10"). An agent on the PC carries it out with
phone control and answers back in the bubble. Reading and navigating are free;
every outbound or irreversible step goes through the approval queue (B1).

## Product rules (owner)

- Observe everything, even with the screen off; risky actions need explicit
  approval on the phone. The bubble never approves. No auto-approve.
- Neutral Latin American Spanish in UI copy and agent answers (tú, no voseo).
- IDUPI flows on its own; only outbound-to-others or irreversible actions wait.

## Current state (exploration 2026-11-26, explore-1)

- App: phase 2 implemented T4-T7. The bubble is drawn by
  `IdupiAccessibilityService` with `TYPE_ACCESSIBILITY_OVERLAY` (no new
  permission), using classic Views; the pill drags and snaps with
  `domain/model/ScreenBubble.kt` `snapBubbleToEdge`. Dictation goes through a
  transparent `VoiceInputActivity`; the chat is a pure reducer over the turn
  client; the approval banner links to Alertas.
- Server: agent runner `lib/inbox-agent.mjs` (`createInboxAgentRunner`, A3
  read-only profiles for claude/opencode; codex/pi not viable). Phone commands:
  `lib/phone-store.mjs` `createCommand` -> `lib/phone-risk.mjs` -> approval
  queue. CLI `bin/idupi-phone.mjs`. No endpoint takes a free-form request.
- Job pattern to copy: inbox `interact` (202 + serialized chain + supersede).

## Design

- Server: `POST /api/v1/phone/agent/turns {text}` -> 202 `{turn}`;
  `GET /api/v1/phone/agent/turns/{id}?wait=30` -> `{status, answer, steps}`;
  `GET /api/v1/phone/agent/turns?limit=` for reconnect. Persisted last 20 turns
  (`pat-` ids) under `data/`.
- Agent runs through the runner with a new profile that grants ONLY the
  `idupi-phone` CLI (claude: `Bash(node <abs>/idupi-server/bin/idupi-phone.mjs:*)`,
  everything else denied, `--strict-mcp-config`, `dontAsk`; opencode analogue
  only if a shell allowlist rule is verified). Every action goes through
  `createCommand` + classifier + approval queue: one enforcement point.
- Prompt: owner request trusted, screen dumps are untrusted data, step budget
  per turn, final answer in neutral Spanish, at most ~600 chars, never claim an
  action that did not happen.
- App: bubble drawn by the accessibility service, gated by a new setting
  (default off) and the accessibility grant; collapsed pill <-> expanded panel;
  keyboard input; voice through `RecognizerIntent` (no permission); answers by
  long-poll of the turn; "esperando tu aprobación" state when a step is gated.

## Tasks

- [x] T1 Server: turn store + routes (POST 202, GET long-poll, list), auth,
      validation (text <= 2000 chars), serialized chain + supersede. Tests.
- [x] T2 Server: phone agent profile in the runner (CLI-only allowlist), prompt
      with trusted/untrusted split, step budget, answer cap, injection tests.
- [x] T3 Server: safety rails (budget exhaustion, redacted logs: ids only),
      docs `idupi-server/docs/phone.md` "Bubble agent turns". Tests.
- [x] T4 App: bubble overlay in the accessibility service (pill, drag + snap,
      expanded panel, text input), setting "Burbuja de IDUPI" (default off).
      Tests (pure reducer, window params, gate).
- [x] T5 App: voice input (`RecognizerIntent`, es-CL, editable transcript,
      graceful fallback). Tests.
- [x] T6 App: turn client (`IduPiClient` + Real + Fake), long-poll with cancel,
      steps/answer rendering, error mapping. Tests.
- [x] T7 App: approval state in the bubble ("Esperando tu aprobación", tap
      opens Alertas); no approve action inside the bubble. Tests.
- [ ] T8 Release: version bump, build, push APK, server restart (check
      `idupi-term ls` first), live e2e, PR.

## Acceptance criteria

- A request typed or dictated in the bubble returns an answer in the bubble.
- Reading/navigating runs without prompts; a send/pay/delete step waits for
  approval and does not run if rejected or expired.
- Screen text never reaches the prompt outside an untrusted block; the agent
  has no tool other than the phone CLI.

## Checks

- Server: `node --test idupi-server/test/*.test.mjs`
- App: `./gradlew testDebugUnitTest assembleDebug` (JAVA_HOME = Android Studio1 jbr)
- Manual on the phone.

TDD: on (source: project practice in phases 1–2; runners: node:test, JUnit via Gradle).

Delivery: about 2000+ authored lines forecast; strategy `exception-ok`, one PR
stacked on #28 with `size:exception`, same as #27/#28.

## Progress

- 2026-11-26: exploration done (explore-1). Branch created on feat/phone-approvals.
- 2026-11-26: T1 green. RED: the new store and route tests failed with
  `ERR_MODULE_NOT_FOUND` (modules absent). GREEN: 19 targeted tests (9 store +
  10 routes). `phone-agent-turns.json`, `pat-` ids, last 20 turns, text 1..2000;
  POST 202 / 400 / 503, long poll `wait=0..30`, list 1..20; a new turn cancels
  the previous queued/running one and aborts its signal.
  Commit `4290674`.
- 2026-11-26: T2 green. RED: `phone-agent.test.mjs` failed with
  `ERR_MODULE_NOT_FOUND`. GREEN: 14 tests. Claude-only profile:
  `Bash(node <abs>/idupi-phone.mjs:*)`, `dontAsk`, strict MCP with an empty
  config, empty temp cwd, `IDUPI_*TOKEN*` scrubbed (the CLI reads
  `.idupi-token` itself), stream-json frames become `cli` steps.
  Commit `b5169e1`.
- 2026-11-26: T3 green. RED: the 16th-call test hung until the 5 min turn
  timeout because nothing enforced the budget. GREEN: 27 targeted tests (11
  routes + 16 agent) and `node --test idupi-server/test/*.test.mjs` = 988 pass,
  0 fail. The 16th CLI call kills the child and fails the turn; route logs carry
  ids and statuses only; docs section "Bubble agent turns".
  Commit `ffddd2a`.
- 2026-11-26: parent review. Turns used the inbox config, whose default agent is
  opencode, so the route would answer 503. Bubble turns now have their own
  config in `index.mjs` (`claude`, model `claude-sonnet-5-5` or
  `IDUPI_PHONE_AGENT_MODEL`, effort medium). Live runner check against the phone
  (no server restart, one PTY alive): "¿Qué app tengo abierta y cuánta batería
  me queda?" -> one `status` CLI call, neutral Spanish answer in 8 s.
  Server 988 pass, 0 fail.
- 2026-11-26: T6 green. RED: `PhoneAgentTurnWireTest` and
  `BubbleChatControllerTest` failed to compile (`PhoneAgentTurn`,
  `BubbleChatController` absent). GREEN: 20 targeted tests (6 wire + 14
  controller). `IduPiClient` gained `startPhoneAgentTurn` /
  `getPhoneAgentTurn(id, wait)` / `listPhoneAgentTurns`; the Real client maps
  400/413 to `Invalid`, 503 `AGENT_UNAVAILABLE` to `Unavailable`, everything
  else to `Failure`, and its poll timeout is `wait + 15 s` like the command
  long poll. The controller POSTs, follows the turn with `wait=25` until a
  terminal status, appends steps by count (skipping the duplicate `answer`
  step), the answer once, and the failed/cancelled copy; three consecutive poll
  failures fail the turn, `close()` cancels, `resume()` reattaches.
  Commit `ab9791f`.
- 2026-11-26: T4 green. RED: `BubbleOverlayModelTest` failed to compile
  (`BubbleGate`, `BubbleWindowSpecs` absent); the new DataStore and ViewModel
  tests failed on `phoneBubbleEnabled`. GREEN: 35 targeted tests (8 model + 4
  DataStore + 23 ViewModel). Setting `phone_bubble_enabled`, default OFF, next
  to "Control desde la PC" with copy that names both requirements. Gate =
  setting AND phone control AND the accessibility grant (the bound service).
  The overlay uses **classic Views**, not Compose: it lives in a Service with no
  Activity, so Compose would need a hand-built lifecycle/saved-state/
  recomposer owner for a window added and removed at runtime; the panel is a
  few widgets and plain Views keep IME focus and window lifecycle obvious.
  `TYPE_ACCESSIBILITY_OVERLAY`, no new permission; pill drags and snaps with
  `snapBubbleToEdge`; expanded drops `FLAG_NOT_FOCUSABLE` so the field can take
  input; the panel is clamped to the screen. Commit `0b49873`; the window is
  added/removed on the main thread (`c430491`) and the panel resizes for the
  keyboard (`106e94a`).
- 2026-11-26: T5 green. RED: `VoiceInputContractTest` failed to compile
  (`VoiceInputContract` absent). GREEN: 7 tests. `VoiceInputActivity` is
  transparent, not exported, `excludeFromRecents`, `noHistory`, launched with
  `FLAG_ACTIVITY_NEW_TASK`; it opens `ACTION_RECOGNIZE_SPEECH` with `es-CL` on a
  Spanish phone and the device tag otherwise, publishes the transcript (or the
  failure event) on the process-wide `VoiceInputChannel`, and the bubble places
  it in the field for editing, never auto-sends. No RECORD_AUDIO. Dictation
  needs the on-device check in T8 (the `noHistory` trampoline relies on the
  recognizer sheet not stopping it). Commit `47ad7cb`.
- 2026-11-26: T7 green. RED: `BubbleApprovalStateTest` failed to compile
  (`BubbleApprovalState` absent). GREEN: 5 tests. `BubbleApprovalState.of(phase,
  pendingCount)` drives the banner ("Esperando tu aprobación" + "Ver en
  Alertas" -> `MainActivity` with `InboxNotifier.EXTRA_OPEN_ALERTS`) only while
  a turn runs, and the pill badge whenever an approval is pending. The bubble
  never approves or rejects. Commit `7019388`.
- 2026-11-26: full app check:
  `./gradlew testDebugUnitTest assembleDebug` = 1103 tests, 0 failures
  (baseline 1057 + 46 new), APK built.

## Next step

T8 release: version bump, build, push APK, server restart (check `idupi-term ls`
first), live e2e, PR.
