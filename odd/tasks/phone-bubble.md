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

- App: no overlay, no speech input, no chat UI. `IdupiAccessibilityService` is
  the natural host (`TYPE_ACCESSIBILITY_OVERLAY`, no new permission). Drag math
  exists: `domain/model/ScreenBubble.kt` `snapBubbleToEdge`.
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
- [ ] T4 App: bubble overlay in the accessibility service (pill, drag + snap,
      expanded panel, text input), setting "Burbuja de IDUPI" (default off).
      Tests (pure reducer, window params, gate).
- [ ] T5 App: voice input (`RecognizerIntent`, es-CL, editable transcript,
      graceful fallback). Tests.
- [ ] T6 App: turn client (`IduPiClient` + Real + Fake), long-poll with cancel,
      steps/answer rendering, error mapping. Tests.
- [ ] T7 App: approval state in the bubble ("Esperando tu aprobación", tap
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

## Next step

T4 app: bubble overlay.
