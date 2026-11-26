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
  approval on the phone. The bubble never approves a pending card.
- Update 2026-11-26 (owner): a task the owner sends from the bubble IS the
  approval for its messaging steps (hands-free); money and destructive steps
  still wait for an explicit approval. Autonomous/proactive agents keep the
  full gate.
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
- [x] T8 First on-device release: 1.53-burbuja (versionCode 54) pushed as a
      release-signed APK (dlv-d9ba3eadd7c0). Owner verdict: works well, design
      needs a full pass (T9-T11).
- [x] T9 App: glass look. The bubble and panel take the owner's theme
      (`ThemePalettes.resolve(themeSettings)`), not hard-coded colours;
      translucent "liquid glass" surfaces (alpha fill + light rim + soft
      highlight; real blur behind on API 31+ when cross-window blur is enabled,
      plain translucency otherwise). Collapsed = a circle with the IDUPI
      hedgehog as a translucent white mark (from `docs/assets/icon-512-transparent.png`)
      and a subtle idle animation (breathing glow / shimmer) so it never looks
      solid. Pure colour/alpha mapping tested.
- [x] T10 App: edge docking. After a short idle the circle slides half off the
      nearest edge (liquid tuck), at reduced opacity; touch brings it back with
      a spring; drag still works. Pure dock-position maths tested.
- [ ] T11 App: in-bubble dictation. Replace the Google recognizer sheet with
      `SpeechRecognizer` driven from the overlay: listening state inside the
      panel (animated level bars from `onRmsChanged`, live partial text into the
      field), never auto-sends. Needs `RECORD_AUDIO`: requested once from
      Ajustes when the bubble is enabled or via a tiny transparent permission
      activity on first tap; falls back to the current `VoiceInputActivity`
      when the permission is denied or no recognizer exists. Tests.
- [x] T13 Server: owner-initiated turns run without approvals (owner 2026-11-26:
      "cuando envío una tarea no pedir aprobaciones", hands-free use). A
      command created by a running bubble turn (per-turn secret the runner
      passes to the CLI; never trusted from `requestedBy` alone) is approved
      automatically and recorded as decided by the owner's request, so the
      phone ledger accepts it and the phone never jumps to Alertas mid-task.
      Still gated even inside a turn: money (sensitive bank/wallet apps,
      pay/buy/transfer words) and destructive words (delete/borrar/eliminar).
      Agent prompt updated (no "esperando aprobación" for messaging). Tests.
- [x] T14 Server: agent tap reliability. Prompt teaches `tap --id` (e.g.
      `com.whatsapp:id/send`) and disambiguation when "varios elementos
      coinciden"; `type --submit` that leaves text in a chat field falls back
      to tapping the send button by id. Tests on prompt/CLI.
- [x] T15 App: movable panel. The expanded chat panel drags by its header
      like the circle instead of staying fixed at the centre; position
      clamped to the screen and remembered while the service lives. Tests.
- [x] T16 App: keyboard and navigation. While the panel has focus the
      owner can hide the keyboard (back key closes the IME first, a second
      back collapses the panel; a tap outside the panel releases focus via
      `FLAG_WATCH_OUTSIDE_TOUCH`), and system back/home keep working. Tests on
      the pure key/focus reducer.
- [ ] T17 App: voice follow-up. When the last request was dictated and the
      agent answers with a question, the bubble opens, shows the answer and
      starts listening again (in-bubble dictation from T11), never auto-sends.
      Tests.
- [x] T18 App: phone-side acceptance of owner-request approvals. The phone
      ledger accepts an approval with `decidedBy = owner-request:<turnId>` ONLY
      when that turn id was started from this phone's bubble and is still
      queued/running (the bubble controller registers it on POST and clears it
      on any terminal status), and only for the gated categories the server
      may auto-approve (never money/destructive). Without that match the
      executor keeps refusing (`APPROVAL_NOT_GRANTED_ON_PHONE`). Keeps the
      phone-side guarantee: a PC can't approve anything the owner did not ask
      for on the phone. Tests.
- [ ] T19 Per-app integration (owner 2026-11-26: Outlook, Gmail, Messenger,
      Instagram, Telegram, YouTube, Google, Brave, Chrome, WhatsApp): (a)
      deep-link command (`mailto:`, `wa.me`, `t.me`, http(s) search/URL with
      an explicit target package) behind a scheme allowlist; (b) per-app
      playbooks (send button / compose field / search ids, how to confirm a
      send) captured from the owner's real screen dumps, injected into the
      agent prompt for the app in use; (c) generic fallback. Research + tests.
- [ ] T12 Release: version bump, assembleRelease (same cert), push, live check,
      PR.

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
- 2026-11-26: T8 on device. A debug APK was refused ("la firma no coincide");
  rebuilt with `assembleRelease` (cert SHA-256 d8ba0fc9...7f00, same as earlier
  deliveries) and pushed as dlv-d9ba3eadd7c0. Owner: the system works very
  well; the bubble looks solid and plain, and dictation shows the Google sheet
  over the app. Design pass T9-T11 added.
- 2026-11-26: T9 + T10 (work-2 wrote them, then crashed with exit 8 before
  committing; git history intact, parent finished). RED: `Unresolved reference
  'undocked'`, `'BubbleDock'`, `'FLAG_LAYOUT_NO_LIMITS'`. Parent fixed two
  call sites (`animateDock(dockedTarget = ...)`). GREEN:
  `testDebugUnitTest` = 1120 tests, 0 failures (+17). `BubbleGlassStyle` maps
  the owner's palette to glass fill/rim/mark; the circle shows
  `drawable-nodpi/idupi_mark.png` (white hedgehog extracted from
  `docs/assets/icon-512-transparent.png`), breathes while idle, blurs behind
  the panel only on API 31+ with cross-window blur; `BubbleDock` tucks the
  circle half off the edge after idle (`FLAG_LAYOUT_NO_LIMITS` on the collapsed
  window only). Commit `7441066`. 1.54-burbuja-vidrio (versionCode 55, `b27cc80`)
  release-signed, same cert, pushed as dlv-e1ed0aeba3d6. Awaiting owner's look.
- 2026-11-26: owner on 1.54: "estéticamente es hermoso". New asks: the chat
  panel must move like the circle; the keyboard cannot be hidden and back/home
  are unavailable while typing; approvals block hands-free use (turns
  pat-b28176e79c15 / pat-55184a14e093 / pat-c802bb85febe: the WhatsApp contact
  was found but the approval jump to Alertas broke typing, `type --submit` left
  the text unsent, `tap "Enviar"` matched several nodes, the agent did not use
  `tap --id`); after a dictated request answered with a question, reopen with
  dictation ready; dictation must be integrated, not the Google sheet. Added
  T13-T17; owner product rule updated (owner-initiated turns need no approval,
  money and destructive steps still do).
- 2026-11-26: T13 green. RED: the new grant tests failed to load
  (`ERR_MODULE_NOT_FOUND`); the owner-turn store tests got `awaiting_approval`
  where `pending` was expected; the risk, route, runner and CLI tests failed
  because the grant module, the categories and the header helper did not exist.
  GREEN: full suite 1018 pass, 0 fail (+28). A queued/running turn mints a
  32-byte secret bound to its id (`lib/phone-turn-grants.mjs`, memory only,
  `timingSafeEqual` lookup); the runner passes it as
  `IDUPI_PHONE_TURN_GRANT` (the env scrub drops inherited grants and the prompt
  never sees it) and the CLI forwards it as `X-Idupi-Turn-Grant` on command
  creation. The command route resolves it server-side; `requestedBy` never
  authorizes. `createCommand` instantly approves `message`-category steps with
  `decidedBy: "owner-request:<turnId>"` (type_submit, messaging text, messaging
  apps) and requeues them with the same `approvalId`/`approvedTarget` a manual
  approval produces; money, destructive, confirmation and bank/wallet apps stay
  pending. Grants are revoked on done/failed/cancelled/superseded and never
  appear in turn JSON, steps, answers or logs. Commit `c251c7f`.
  Note: the phone's `ApprovalGate` still consumes only local ledger grants, so
  hands-free execution needs the app-side half (accept an owner-request
  approval or record its grant) before T12; server side is complete.
- 2026-11-26: T14 green. RED: the new prompt test failed because the prompt had
  no `tap --id`, no "varios elementos coinciden" guidance and no send
  verification. GREEN: full suite 1019 pass, 0 fail (+1). The prompt now
  explains the `#resource-id` token in the screen dump, `tap --id` (WhatsApp
  `com.whatsapp:id/send`), disambiguating an ambiguous text tap, and that
  `type --submit` may only add a newline: read the screen, tap the send button
  by id and confirm the field is empty or the bubble appears. The blanket
  coordinate ban is gone because the phone still checks the node under a point
  (`ApprovalGate.checkNode` + `textsAtPoint`); `tap --xy` is a last resort.
  Commit `2174d26`.
- 2026-11-26: T18 BLOCKED at inspection; no app code written. The delivered
  command shape (`applyApproval` + `takeNext` in `phone-store.mjs`;
  `docs/phone.md` "Approved command delivered to the phone") carries only
  `approvalId`, `approvedTarget`, `approvalReason` and `approvalTarget`.
  `createCommand({ ownerTurnId })` uses the turn id to mark the approval
  (`decidedBy: "owner-request:<turnId>"`, persisted in
  `phone-approval-store.mjs`) and never stores it on the command. The app's
  `CommandApproval` has `approvalId`/`approvedTarget`/`reason` only, and
  `PhoneApproval` has no `decidedBy`; the app has no single-approval fetch.
  So the phone cannot bind an incoming approval to a turn started by THIS
  bubble (rule a of T18) from what it receives. The approval GET route would
  return the `decidedBy` field (the store clones the whole record), but that is
  a new app-only lookup, not data "on the command", and it was not implemented
  under the task's stop rule. Missing wire field: `decidedBy` (or an equivalent
  `ownerTurnId`) on the delivered command.
- 2026-11-26: T15 green. RED: `BubbleOverlayModelTest` failed to compile with
  `Unresolved reference 'panel'` (4 call sites). GREEN: `BubbleOverlayModelTest`
  = 17 tests, 0 failures (+3). `BubbleOverlayPosition.panel` centers the first
  expansion and clamps a remembered spot on a smaller display;
  `PhoneBubbleOverlay` drags the panel by its header with raw touch deltas,
  updates the window on the main thread, remembers `panelPosition` while the
  service lives, and leaves Minimizar as a clickable child so its taps never
  start a drag; the rotation path re-clamps the panel. Commit `2fdd16d`.
- 2026-11-26: T16 green. RED: `BubbleFocusReducerTest` failed to compile
  (`BubbleFocusState`, `BubbleFocusReducer`, `BubbleFocusEvent`,
  `BubbleFocusAction` absent); the model tests also wanted
  `FLAG_WATCH_OUTSIDE_TOUCH` and `withFocus`. GREEN: 8 reducer tests + 20
  overlay-model tests; full suite 1134 tests, 0 failures (+14 since T14);
  `assembleRelease` success. BACK hides the IME and clears the field first, a
  second BACK collapses; `ACTION_OUTSIDE` and the new "⌨" glass button
  (contentDescription "Ocultar teclado") hide the IME and set
  `FLAG_NOT_FOCUSABLE`; tapping the field clears that flag and re-shows the IME.
  Every `WindowManager` call stays on the main thread. Commit `4a44efe`.
- 2026-11-26: T18 green. RED: the new delivered-command assertions failed with
  `actual: undefined, expected: 'pat-0123456789ab'` (store `takeNext` and the
  HTTP `/phone/commands/next` route), and the app failed to compile with
  `Unresolved reference 'OwnerTurnRegistry'` (gate, controller and their
  tests). A first implementation pass still failed 2 gate tests: re-recording
  the grant on every call made an owner-request approval reusable; fixed with
  `OwnerTurnRegistry.claimOwnerApproval` (one materialization per approval id).
  GREEN: server 1020 pass, 0 fail (baseline 1019; one new store test plus the
  route assertion); app `testDebugUnitTest` 1155 tests, 0 failures (+21: 11
  gate, 3 registry, 5 controller, 2 parser); `assembleRelease` success.
  Wire: an auto-approved command carries `args.ownerTurnId` and
  `args.approvalCategory: "message"`; manual approvals and free commands omit
  both. App: `CommandApproval` gains `ownerTurnId`, `category`, `expiresAt`
  (defaults null, parser reads args then top level). `ApprovalGate` records a
  one-use ledger grant only when the category is `message`, the reason is one
  the server issues for a bubble turn (`type_submit`, `risky_text`,
  `sensitive_app`), the expiry is still valid and the turn is active in
  `OwnerTurnRegistry`; otherwise the command keeps failing with
  `APPROVAL_NOT_GRANTED_ON_PHONE`. The controller registers on a successful
  POST, unregisters on done/failed/cancelled, `close()` and a new request, and
  registers again on `resume()`. Commits `5f776ca` (server) and `2b7c72d`
  (app).

## Next step

T11 in-bubble dictation and T17 voice follow-up, then T12 (release: version
bump, `assembleRelease`, push, live check, PR). T18 is done end to end: the
server names the owner turn on the delivered command and the phone accepts its
auto-approved messaging steps only while that turn is active on the device.
Before T12, deliver only `assembleRelease` APKs (a debug APK fails with a
signature mismatch on the phone).
