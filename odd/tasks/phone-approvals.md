# Phone approval queue (phase 2, block B1)

Locator: `odd/tasks/phone-approvals.md` · Engram mirror: `odd/phone-approvals/tasks`
Branch: `feat/phone-approvals` (from main f6ba549)

## Objective

Phone control (`idupi-phone` and the future phone agent) may read and navigate
freely, but every outbound or irreversible action (send, pay, delete, approve)
waits for the owner's explicit approval on the phone. Block B2 (a phone agent
started from a floating IDUPI button) builds on this gate.

## Product rules (owner, 2026-11-25)

- Observe everything, even with the screen off. Risky actions need explicit
  approval; an auto-approve mode is a future feature, only on owner request.
- An unanswered approval expires and the action does NOT run (never
  auto-approve on expiry, unlike `ui-request-registry`).
- Neutral Latin American Spanish in UI copy.

## Current state (exploration 2026-11-26, explore-1)

- Transport: HTTP long-poll. `POST /api/v1/phone/commands` -> phone
  `GET /commands/next?wait=25` -> `POST /commands/{id}/result`.
  `lib/phone-routes.mjs`, `lib/phone-store.mjs` (in-memory, 60 s TTL,
  `COMMAND_TYPES`, `normalizeCommandArgs`, `createCommand`, `takeNext`).
- App executor: `data/phone/control/` (`PhoneControlPoller`, `CommandParser`,
  `PhoneCommandExecutor.tap/type`, `PASSWORD_FIELD` refusal precedent).
- Only gate today: master switch `phoneControlEnabled`.
- Closest approval pattern: calendar proposals (persisted store, 7-day TTL,
  409 EXPIRED/ALREADY_DECIDED, phone poll + local notification + card).
- No agent drives the phone yet.

## Design

- Server classifies every command at `createCommand`. Free: screen, scroll,
  back/home/recents/notifications/quick-settings, open, wait, status. Gated:
  `type` with submit, tap/long-press whose target text matches a risky verb
  (send/pay/delete/approve/confirm/buy/transfer, Spanish and English), and any
  tap/type/long-press by `--xy` or `--id` while a messaging/payment/banking app
  is in the foreground.
- A gated command is parked as `awaiting_approval` in a persisted approval
  store (`apr-` ids, 10 min TTL, file under `data/`), with requester, command,
  foreground app and a short reason. Approve -> the command enters the normal
  queue with a fresh TTL and an `approvalId`; reject/expire -> it fails with
  `APPROVAL_REJECTED` / `APPROVAL_EXPIRED`.
- Phone second check: an unapproved tap that resolves to a node whose text
  matches a risky verb is refused with `APPROVAL_REQUIRED`; the server turns
  that refusal into an approval request carrying the node text. An approved
  tap whose matched node text differs from the approved target is refused.
- CLI: `idupi-phone` prints "Esperando tu aprobación en el teléfono" and waits
  (bounded) for the decision; `idupi-phone approvals` lists pending ones.
- App: poll pending approvals while phone control is on; local notification
  with Aprobar/Rechazar actions (auth required on the lock screen); an
  "Aprobaciones pendientes" card in Asistente del teléfono.

## Tasks

- [x] T1 Server: classifier, persisted approval store, `awaiting_approval`
      state, routes `GET /api/v1/phone/approvals`,
      `POST /api/v1/phone/approvals/{id}/decision` (409 EXPIRED /
      ALREADY_DECIDED), `APPROVAL_REQUIRED` result -> approval, CLI wait +
      `approvals`, docs/phone.md. Tests.
- [x] T2 App: approvals client + poll, notification with actions (auth on lock
      screen), pending card, phone-side risky-node check and approved-target
      check. Local approval ledger: the phone only executes a command carrying
      an `approvalId` that the owner approved ON THIS PHONE (a PC process with
      the bearer token can call the decision route, so the server decision is
      not proof). Tests.
- [ ] T4 App (owner request after the live check, 2026-11-26): approvals also
      appear in Alertas with their detail (what, which app, why, who asked,
      time left, expandable exact target/typed text) and Aprobar/Rechazar.
      One process-wide pending StateFlow fed by the poller; any decision from
      any surface (notification, Alertas, Ajustes card) removes the item and
      cancels its notification, so it disappears everywhere. Tests.
- [ ] T3 Release: version bump 1.51, build, push APK (off-LAN delivery),
      server restart (check `idupi-term ls` first), live check, PR.

## Acceptance criteria

- `idupi-phone tap "Enviar"` never taps until the owner approves on the phone;
  rejecting or ignoring it for 10 min leaves the screen untouched.
- Reading and navigating commands run without any prompt.
- A tap by coordinates that lands on a send button is stopped by the phone.
- No path auto-approves.

## Checks

- Server: `node --test idupi-server/test/*.test.mjs`
- App: `./gradlew testDebugUnitTest assembleRelease` (working JDK, see Engram
  "Android Studio jbr path")
- Manual on the phone over Tailscale.

TDD: on (source: project practice in phases 1–2; runners: node:test, JUnit via Gradle).

Delivery: about 900 authored lines forecast; one PR for the feature with
`size:exception`, same as PR #27.

## Progress

- 2026-11-26: exploration done (explore-1). Owner chose the floating button for
  B2 and asked to start with B1. Branch created.
- 2026-11-26: T1 server done on `feat/phone-approvals`. RED first: the two new
  modules did not exist and the CLI lacked `failureMessage`; GREEN: 952 tests
  pass with `node --test idupi-server/test/*.test.mjs`. New modules
  `lib/phone-risk.mjs` and `lib/phone-approval-store.mjs`; `awaiting_approval`
  gate in `lib/phone-store.mjs`; approval routes in `lib/phone-routes.mjs`;
  `idupi-phone approvals` + wait/results in `bin/idupi-phone.mjs`; docs in
  `docs/phone.md`. Commit `b0647be`. Parent re-ran: 952/952 pass. Gap found
  in review: the decision route shares the PC bearer token, so a PC agent could
  approve itself; closed on the phone in T2 with a local approval ledger.
  RDD: off (not enabled by the owner); ordinary checks only.
- 2026-11-26: T2 app done (work-2) 713e599. Local ledger (`ApprovalLedger`,
  one use, bounded 100, fail-closed), pure `ApprovalGate` (APPROVAL_REQUIRED /
  APPROVAL_NOT_GRANTED_ON_PHONE / APPROVED_TARGET_MISMATCH; sensitive_app bound
  to the exact id/xy), `PhoneRiskText` port, poller (10 s, backoff), "Aprobaciones"
  channel with auth-required actions, non-exported receiver, immutable
  PendingIntents, pending card in Asistente del teléfono. RED: unit tests did not
  compile on the missing APIs. GREEN (parent re-run): 1009 tests, 0 failures;
  `assembleRelease` green.
- 2026-11-26: T3 partial. 1.51-aprobaciones (versionCode 52) 1c60a48, APK pushed
  off-LAN (dlv-0d3e0acb6980). Server restart pending: one live PTY (e2060b).
  Known limit: with adb connected (home Wi-Fi) a PC process can tap the
  approval UI through `adb input`, which bypasses accessibility; the
  accessibility path itself is gated (the "Aprobar" button text is a risky verb).

- 2026-11-26: server restarted (23:24) on this branch. Live check, approve path:
  `idupi-phone tap "Enviar"` on the launcher parked `apr-6ece657f12db`, the CLI
  waited, the owner approved on the phone, the phone accepted it through the
  local ledger and the tap failed harmlessly ("no se encontró el texto").
  Caveat: for a text target the executor returns NotFound before
  `ApprovalGate.checkNode`, so this run proves the queue and decision flow but
  not the phone ledger; a strict live check needs a real "Enviar" on screen.
- 2026-11-26: reject path live: `apr-2933223b51f2` rejected on the phone, CLI
  printed "Rechazado en el teléfono." and exited 1; nothing was tapped.

## Next step

Open the PR (`size:exception`). Follow-up: live ledger check with a real
"Enviar" button on screen (unit tests already cover the ledger).
