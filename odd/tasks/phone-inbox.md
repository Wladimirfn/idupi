# Phone inbox (Alertas)

## Objective

Turn "Alertas" into a management inbox. The phone captures WhatsApp, Gmail and
Outlook notifications. The PC server runs a configurable AI agent (agent + model
+ effort) that classifies importance, summarizes and drafts a reply. The app
shows each alert with that analysis, reminds the owner every X minutes while an
important message is unanswered and, if enabled, sends a personalized
auto-reply through the notification's own reply action.

## Problem / why

The owner misses important work messages (e.g. an equipment maintenance mail)
and wants triage plus a ready answer without opening every app. WhatsApp has no
official API for personal accounts and unofficial clients risk a ban, so the
safe channel is the phone's notification listener plus the notification's
RemoteInput reply action (how auto-reply apps work). Deep mail search
(Gmail/Outlook APIs) is a later phase.

## Scope

In:
- Server: inbox store, one-shot agent runner (opencode/claude/codex/pi with
  model + effort), inbox routes, agent config persisted on the PC.
- App: NotificationListenerService capture for WhatsApp (+Business), Gmail,
  Outlook; reply-action registry; reminder + auto-reply policy; Alertas UI with
  summary/suggested reply/actions; Ajustes section for listener access,
  monitored apps, reminder interval, auto-reply, and agent/model/effort.

Out (later phases): Gmail/Outlook API search of history, browser automation,
per-contact rules.

## Constraints

- Zero-dependency Node server; route factory after requireAuth; execFile
  without a shell; deps injected for tests.
- Auto-reply is OFF by default and only fires for unanswered important items
  whose reply action is still live. Message text leaves the phone only to the
  owner's PC and the agent the owner configured.
- Code/comments/commits in English; UI copy in Spanish.
- Each APK delivered to the phone with idupi-push and a versionCode above the
  installed one.

## Tasks

- [x] T1 Server inbox: `lib/inbox-agent.mjs` (pure argv builder per agent with
  effort + runner + JSON result parser), `lib/inbox-store.mjs` (JSON file,
  gitignored), `lib/inbox-routes.mjs` (POST messages → 202 + async analysis,
  GET list, POST status, GET/PUT config). Tests with injected runner.
- [x] T2 App data layer: inbox models, IduPiClient/RealIduPiClient/Fake
  methods, inbox settings in DataStore.
- [x] T3 App capture: notification parser (pure, tested), listener service +
  manifest, reply-action registry, upload to server, handled-on-removal.
- [x] T4 App policy: reminder/auto-reply decision logic (pure, tested) wired in
  the listener; local reminder notifications; RemoteInput send.
- [x] T5 App UI: Alertas inbox list + actions; Ajustes > Alertas config.
- [ ] T6 Deliver: version bump, release APK to phone, server restart (after
  checking live PTYs).

## Acceptance

- A WhatsApp/Gmail/Outlook notification appears in Alertas with importance,
  summary and a suggested reply produced by the configured agent/model/effort.
- Unanswered important items re-notify every configured interval; opening or
  answering the source notification stops reminders.
- With auto-reply enabled, an unanswered important WhatsApp message gets the
  personalized reply after the configured delay.

## Checks

- Server: `node --test idupi-server/test/<file>.test.mjs` per file (CI style).
- App: `./gradlew :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config); ordinary functional checks.

## Delivery

Branch `feat/phone-inbox`, stacked on `feat/settings-commands` →
`feat/terminal-context`. Forecast well above 400 authored lines; slicing into
PRs decided at delivery.

## Decisions

- Auto-reply sends ONLY the owner's fixed personalized template, never
  AI-generated text: a crafted message could steer the model (codex's
  read-only sandbox can still read files), and auto-sending its output back to
  the sender would be an exfiltration path. AI suggestions are sent only when
  the owner taps them.
- POST /api/v1/inbox/messages takes `postedAt` as an ISO-8601 string.

## Progress

- Baseline: 1.39-commands-alertas (versionCode 40) delivered to the phone
  (dlv-2b00b210a166), cert d8ba0fc9.
- T1 done: cbc76c2 feat(server): inbox analysis with a configurable agent.
  Tests: inbox-agent 17/17, inbox-routes 11/11, inbox-store 11/11; full suite
  CI-style 59/59 files. No-tools probe per agent (worker, 2026-11-24): opencode
  ran `whoami` through the shared service until `--standalone` was added,
  then denied; claude, codex, pi safe. Live e2e on the restarted server
  (opencode-go/deepseek-v4.1-flash, max): WhatsApp maintenance message → ready
  in 20 s, importance high, Spanish summary + reply, injected "run whoami"
  ignored. Default config: opencode / opencode-go/deepseek-v4.1-flash / max
  (no non-flash deepseek-v4.1 in `opencode models`).
- T2 done: 7642c10 feat(app): inbox data layer and settings. InboxModelsTest
  6/6 (wire decode + request encode), InboxSettingsDataStoreTest 7/7
  (round-trip, empty set, unknown importance, interval clamp), in-memory twin
  7/7. Inbox methods added to IduPiClient/RealIduPiClient/FakeIduPiClient.
- T3 done: eb2a302 feat(app): capture WhatsApp, Gmail and Outlook
  notifications. NotificationParserTest 17/17 (WhatsApp 1:1/group/without
  MessagingStyle/Business, ES+EN aggregation rows, group summary, ongoing,
  Gmail, Outlook, blank text, ISO postedAt), InboxSessionTest 10/10,
  ReplyActionRegistryTest 6/6, InboxRemovalRulesTest 3/3. Removal rule:
  CLICK/CANCEL/APP_CANCEL marks the conversation's open items `handled` after a
  2 s grace; a same-key re-post inside the window cancels it (and a lastSeen
  newer than the removal also wins), so WhatsApp cancel+repost updates are not
  mistaken for "read". Listener configures the shared client from the saved
  profile (`IduPiClientProvider.configureFromStorage`, also used by
  MainActivity). Verification: `:app:testDebugUnitTest :app:assembleRelease`
  380/380, release APK built.
- T4 done: 534f58b feat(app): inbox reminders and template auto-reply.
  `InboxReminderPolicy` is pure: open items only, monitored apps only,
  importance gate (high, or high+medium; analyzing/failed count as medium and a
  ready item without a level counts as low), reminders at
  `now - max(receivedAt, lastRemindedAt) >= interval`, auto-reply only for
  WhatsApp/Business with the delay elapsed, a live reply action, a non-blank
  template and not done yet, always sending `autoReplyTemplate` and never the
  AI suggestion. One auto-reply per conversation per tick; an item being
  auto-replied is not reminded in the same tick. InboxReminderPolicyTest 30/30
  (time math, boundaries, gates, idempotence, dedupe, template-only). Host:
  60 s coroutine loop in `InboxNotificationListener`, started on
  onListenerConnected and cancelled on onListenerDisconnected/onDestroy; on
  success the item becomes `auto_replied`, failure keeps reminding.
  Reminders post on the new `idupi_inbox_reminders` channel ("Recordatorios
  IDUPI") with sender + summary and tap through to Alertas
  (`InboxNotifier.EXTRA_OPEN_ALERTS` → MainActivity → AppNavigationIntents).
  The first observation of a ready high item is announced once ("Mensaje
  importante de <sender>"); items that were already ready before the process
  started are not re-announced. No message text is logged.
- T5 done: 91eb4af feat(app): inbox alerts screen and settings. Alertas now
  reads `getInboxItems(50)` (the server `/api/v1/alerts` stub is unused) and
  polls every 10 s only while the screen is composed, plus a manual refresh
  icon; filters Pendientes/Todas/Importantes; cards show app label, sender,
  relative time, importance chip, category, summary (or Analizando… / error +
  Reintentar), expandable original text and the suggested reply, with Enviar
  sugerencia / Editar y enviar / Copiar / Atendida / Descartar. Sending uses
  the live `ReplyActionRegistry` action for the conversation and marks the item
  `replied`; without one the card shows Copiar plus "Respondé desde la app
  original". The drawer badge now counts open high items. Ajustes > ALERTAS
  adds listener access status (re-checked on resume) + settings intent,
  monitored-app switches, reminder interval and minimum importance, auto-reply
  toggle (off by default) + fixed warning + delay + template, and the PC agent
  (agent / model / effort, opencode models from the server) with explicit save
  and inline error/success feedback. AlertsViewModelTest 16/16,
  InboxSettingsViewModelTest 13/13, InboxFormatTest 8/8.
  Verification: `:app:testDebugUnitTest :app:assembleRelease` 440/440, release
  APK built (50,222,002 bytes).
- Unverified in unit tests (no Robolectric): RemoteInput delivery through
  PendingIntent (manual send and auto-reply), listener rebind behaviour, the
  2 s removal ordering, notification permission/channel behaviour on the
  phone, PendingIntent extras surviving a cold start, and that sending a
  template auto-reply does not leave the source notification's action usable
  for a second reply. Policy, session, registry decision logic, formatting and
  both ViewModels are covered; the on-device proof belongs to T6. Interpretations
  beyond the letter of T4/T5: reminders/auto-replies also stop for apps the
  owner stopped monitoring, and at most one auto-reply per conversation is sent
  per tick. Alertas renders a refresh icon rather than a pull-to-refresh
  gesture.
- Working tree note: the `idupi-server/lib/inbox-routes.mjs` hardening change
  that predated T2/T3 was committed as 8ff3044 (error handling only, no
  wire-shape change); the tree was clean when T4 started.
- Parent review: 8ff3044 fix(server) (unhandled-rejection hardening found
  uncommitted after T1). 995a64f fix(app): per-conversation 6 h auto-reply
  cooldown (the per-tick rule allowed one template per tick to a bursting
  sender); InboxReminderPolicyTest 33/33.
- T6 (partial): 2c7f223 version 41 / 1.40-alertas-inbox; app 443 tests /
  0 failures, release APK cert d8ba0fc9, delivered dlv-7b17e5b82afb; server
  restarted on feat/phone-inbox (no live PTYs). Pending: on-device proof
  (listener access, real WhatsApp capture, RemoteInput send, reminders).
