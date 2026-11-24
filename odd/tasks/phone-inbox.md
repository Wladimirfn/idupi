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
- [ ] T4 App policy: reminder/auto-reply decision logic (pure, tested) wired in
  the listener; local reminder notifications; RemoteInput send.
- [ ] T5 App UI: Alertas inbox list + actions; Ajustes > Alertas config.
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
- Unverified in unit tests (no Robolectric): RemoteInput delivery through
  PendingIntent, listener rebind behaviour and the real 2 s removal ordering.
  Parser, session, registry decision logic and reason classification are
  covered; the on-device proof belongs to T6.
- Working tree note: `idupi-server/lib/inbox-routes.mjs` still carries an
  uncommitted hardening change that predates T2/T3 (error handling only, no
  wire-shape change). Left untouched.
