# Alerts that flow (phase 2, block A)

Locator: `odd/tasks/alerts-flow.md` · Engram mirror: `odd/alerts-flow/tasks`
Branch: `feat/alerts-flow` (from main 5afcd82)

## Objective

Alertas must reduce load, not add it. Captured notifications leave the phone
shade, promotions collapse into one reviewable digest, the owner can hand any
alert to an agent with an instruction ("revisa esto en mis proyectos"), and
important alerts arrive with the work already done (investigation or reply
draft) ready to review.

## Product rules (owner, 2026-11-25)

- IDUPI is proactive and flows on its own. Everything aimed at the owner happens
  automatically and lands ready to review.
- Only outbound-to-others or irreversible actions need approval (sending a reply
  to a third party, paying, deleting). Replies are never sent without the owner.
- Neutral Latin American Spanish in UI copy and agent output.

## Current state (exploration 2026-11-25)

- App: only silenced chats are cancelled from the shade
  (`InboxNotificationListener.kt` `cancelSilencedNotification`); every other
  captured notification stays. `REASON_LISTENER_CANCEL` is already ignored as a
  user dismissal (`InboxRemovalRules.kt`), and reply actions survive a listener
  cancel (proven by the silence path).
- Server: inbox agent runs with tools disabled in an empty temp cwd
  (`inbox-agent.mjs`); category is free text; digests are per app+conversation;
  no route takes an owner instruction; projects live in `projects.json` with
  read-only file routes guarded by `project-files.mjs`.

## Tasks

- [x] A1 Clear captured notifications from the shade. New setting "Quitar de la
      bandeja lo que IDUPI ya capturó" (default on). Cancel only after the PC
      accepted the upload (offline -> stays visible). Reply action stays usable.
      One silent IDUPI summary notification ("N alertas: X importantes") replaces
      the pile and opens Alertas. App only. Tests.
- [x] A2 Promotions bucket. Agent contract gets a fixed category enum (adds
      `promocion`); promotions/newsletters skip per-item noise, group into one
      "Promociones" card with one digest (server digest scope by category) and
      "Marcar todo revisado". Server + app. Tests.
- [x] A3 Interactuar. Card button opens a sheet: instruction text + optional
      linked project. `POST /api/v1/inbox/{id}/interact` runs an agent with
      READ-ONLY tools in the project cwd (alert content stays an untrusted block,
      the instruction is trusted); answer stored on the item and shown on the
      card; local notification when ready. Server + app. Tests incl. tool
      allowlist and untrusted wrapping.
- [ ] A4 Proactive. For high-importance alerts: technical alerts (deploy/CI/
      error mails) auto-run the A3 investigation against the matching project;
      messages/mails with a reply action get a "Respuesta lista" notification.
      Setting to turn it off. Nothing is ever sent automatically.
- [ ] A5 Release: version bump, build, auto-push the APK to the phone, server
      restart (check `idupi-term ls` first), PRs.

## Acceptance criteria

- After capture, the shade only shows IDUPI's single summary notification
  (when the setting is on and the PC is reachable).
- Promotions never appear as individual pending cards.
- Interactuar returns an answer that cites what it checked in the project; the
  agent cannot write files or run commands.
- An important email arrives in Alertas with a ready draft and, for technical
  alerts, an investigation result, without the owner pressing anything.

## Checks

- Server: `node --test idupi-server/test/*.test.mjs`
- App: `./gradlew testDebugUnitTest` (JDK from Android Studio), `assembleRelease`
- Manual on the phone (hidden display captures only with owner consent).

TDD: on (source: project practice in phases 1–2; runners: node:test, JUnit via Gradle).

Delivery: about 900–1200 authored lines forecast; stacked PRs to main per task
(A1, A2, A3+A4), same strategy as phase 1.

## Progress

- 2026-11-25: exploration done (explore-1 app map, explore-2 server map). Branch created.
- 2026-11-25: A1 done (work-1) 419f36c. 879 app tests pass (11 new). Review fix: summary omits "· 0 importantes" (importance arrives later from the PC).
  `clearCapturedFromShade` setting (default on, DataStore key
  `inbox_clear_captured_from_shade`), shade rule in `InboxShadePolicy`
  (ACCEPTED + setting, or silenced), generic safe `cancelCapturedNotification`,
  silent summary in `InboxNotifier` + process-wide `InboxCaptureSummary`
  (reset when Alertas opens). RED: new tests failed to compile on the missing
  APIs; GREEN: 879 app unit tests, 0 failures (11 new); `assembleDebug` green.
- 2026-11-25: A2 done. Agent contract uses a boolean `promotion` (explicit A2
  instruction) instead of a category enum; the parser forces promotion ->
  importance low + empty suggestedReply, missing/invalid -> false. Store
  persists the flag (old files decode false) and owns `openPromotions` /
  `closePromotions`. Digest scope `scope\0promotions` reuses the conversation
  caps, untrusted blocks and hash cache; prompt asks for offer bullets and flags
  possible non-promotions, never a reply. `POST /api/v1/inbox/digest
  {scope:"promotions"}` (and `GET ?scope=promotions`), `POST
  /api/v1/inbox/promotions/state {state:"handled"}` closes all and drops the
  digest. App: `InboxItem.promotion`, grouping pulls promotions into one last
  group (`PROMOTIONS_KEY`), one "Promociones" card (count line, app chips,
  Resumir, Ver todas, Marcar todo revisado, no reply buttons), reminder/
  auto-reply policy skips promotions. RED: app compile failed on the missing
  promotion APIs; GREEN: server 870/870 (13 new), app 892/892 (13 new),
  `assembleDebug` green.
- 2026-11-25: A3 done (server earlier by work-2; app now). App:
  `InboxItem.interaction` + `InboxInteraction`/`InboxInteractionStatus`
  (all fields defaulted) and `InboxInteractionRequest`;
  `IduPiClient.interactWithInboxItem(id, instruction, projectId?)` posting to
  `/api/v1/inbox/{id}/interact` (202 -> `{interaction}`, projects reused from
  `getProjects`); `AlertsViewModel.interact`/`retryInteraction` start an
  optimistic `running` block and let the existing 10 s poll finish it; a
  running -> done transition posts `InboxAgentAnswer` ("Respuesta del agente
  lista", first instruction line, tap opens Alertas) once per answer (a done
  item on first load never pings, failures stay silent); "Interactuar" button
  in the actions FlowRow of the single-item and conversation cards (never on
  Promociones) opens an `AgentInteractionSheet` (instruction field, optional
  project dropdown, "Enviar al agente"); running/done/failed render inline
  (progress, "Respuesta del agente · proyecto" + SelectionContainer + Copiar,
  short error + Reintentar). RED: app compile failed on the missing APIs;
  GREEN: app 905/905 with `testDebugUnitTest assembleDebug` (13 new: 4
  `InboxAgentAnswerTest`, 6 model, 7 ViewModel cases), 0 failures.

## Next step

A4.
